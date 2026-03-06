package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OrdersActivity : AppCompatActivity() {

    private lateinit var recyclerOrders: RecyclerView
    private lateinit var btnMultiDelete: ImageButton

    private lateinit var chipAll: Chip
    private lateinit var chipOpen: Chip
    private lateinit var chipClosed: Chip

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var adapter: OrdersAdapter
    private val orders = mutableListOf<OrderRow>()

    private var listener: ListenerRegistration? = null

    // Filters: "ALL", "OPEN", "CLOSED"
    private var filter: String = "ALL"

    // Selection mode
    private var selectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)

        recyclerOrders = findViewById(R.id.recyclerOrders)
        btnMultiDelete = findViewById(R.id.btnMultiDelete)

        chipAll = findViewById(R.id.chipAll)
        chipOpen = findViewById(R.id.chipOpen)
        chipClosed = findViewById(R.id.chipClosed)

        adapter = OrdersAdapter(
            onOrderClick = { order ->
                if (selectionMode) {
                    adapter.toggleSelected(order.id)
                    updateDeleteButtonState()
                } else {
                    val intent = Intent(this, OrderDetailActivity::class.java)
                    intent.putExtra("orderId", order.id)
                    startActivity(intent)
                }
            },
            onOrderLongPress = { order ->
                if (!selectionMode) {
                    selectionMode = true
                    adapter.toggleSelected(order.id)
                    updateDeleteButtonState()
                    true
                } else {
                    false
                }
            }
        )

        recyclerOrders.layoutManager = LinearLayoutManager(this)
        recyclerOrders.adapter = adapter

        btnMultiDelete.setOnClickListener {
            if (!selectionMode) {
                selectionMode = true

                updateDeleteButtonState()
            } else {
                val selected = adapter.getSelectedIds()
                if (selected.isEmpty()) {
                    selectionMode = false
                    adapter.clearSelection()
                    updateDeleteButtonState()
                } else {
                    deleteSelectedOrders(selected)
                }
            }
        }

        chipAll.setOnClickListener { setFilter("ALL") }
        chipOpen.setOnClickListener { setFilter("OPEN") }
        chipClosed.setOnClickListener { setFilter("CLOSED") }
    }

    override fun onStart() {
        super.onStart()
        startListening()
    }

    override fun onStop() {
        super.onStop()
        listener?.remove()
        listener = null
    }

    private fun setFilter(newFilter: String) {
        filter = newFilter
        startListening()
    }

    private fun startListening() {
        listener?.remove()
        listener = null

        var query: Query = db.collection("Orders")
            .orderBy("createdAt", Query.Direction.DESCENDING)

        when (filter) {
            "OPEN" -> query = query.whereEqualTo("status", "OPEN")
            "CLOSED" -> query = query.whereIn("status", listOf("CLOSED", "VOIDED", "REFUNDED"))
            else -> { /* ALL: no status filter */ }
        }

        listener = query.addSnapshotListener { snap, err ->
            if (err != null) {
                Toast.makeText(this, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            Toast.makeText(this, "Orders found: ${snap.size()}", Toast.LENGTH_SHORT).show()

            orders.clear()

            for (doc in snap.documents) {
                val id = doc.id

                val status = doc.getString("status") ?: "OPEN"

                // 🔥 Your Firestore stores total as Double (ex: 0.04)
                val totalCents = doc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L

                val employee = doc.getString("employeeName") ?: "—"
                // For VOIDED orders show who performed the void (like refund shows refundedBy)
                val displayEmployee = if (status == "VOIDED") {
                    doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: employee
                } else {
                    employee
                }

                val createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()

                orders.add(
                    OrderRow(
                        id = id,
                        status = status,
                        totalCents = totalCents,
                        totalRefundedInCents = totalRefundedInCents,
                        employeeName = displayEmployee,
                        createdAt = createdAt
                    )
                )
            }

            adapter.submit(orders)
            updateDeleteButtonState()
        }
    }

    private fun updateDeleteButtonState() {
        val selectedCount = adapter.getSelectedIds().size
        btnMultiDelete.alpha = if (!selectionMode) 1f else if (selectedCount == 0) 0.6f else 1f
    }

    private fun deleteSelectedOrders(selectedIds: Set<String>) {
        lifecycleScope.launch {
            try {
                btnMultiDelete.isEnabled = false

                withContext(Dispatchers.IO) {
                    for (orderId in selectedIds) {
                        deleteOrderWithItems(orderId)
                    }
                }

                Toast.makeText(this@OrdersActivity, "Deleted ${selectedIds.size} orders", Toast.LENGTH_SHORT).show()

                selectionMode = false
                adapter.clearSelection()
                updateDeleteButtonState()

            } catch (e: Exception) {
                Toast.makeText(this@OrdersActivity, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnMultiDelete.isEnabled = true
            }
        }
    }

    private suspend fun deleteOrderWithItems(orderId: String) {
        val orderRef = db.collection("Orders").document(orderId)

        val itemsSnap = orderRef.collection("items").get().await()
        for (item in itemsSnap.documents) {
            item.reference.delete().await()
        }

        orderRef.delete().await()
    }
}