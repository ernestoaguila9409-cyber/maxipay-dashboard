package com.ernesto.myapplication

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import java.util.Calendar

class OrdersActivity : AppCompatActivity() {

    private lateinit var recyclerOrders: RecyclerView
    private lateinit var btnMultiDelete: ImageButton
    private lateinit var btnFilter: ImageButton

    private lateinit var chipAll: Chip
    private lateinit var chipOpen: Chip
    private lateinit var chipClosed: Chip

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var adapter: OrdersAdapter
    private val orders = mutableListOf<OrderRow>()
    private val allOrders = mutableListOf<OrderRow>() // unfiltered for status/date filter

    private var listener: ListenerRegistration? = null

    // Filters: "ALL", "OPEN", "CLOSED" (chips) — kept for quick tabs
    private var filter: String = "ALL"

    // Advanced filter (from filter dialog)
    private var statusFilter: String = "ALL" // ALL, OPEN, CLOSED, VOIDED, REFUNDED_FULLY, PARTIALLY_REFUNDED
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null

    // Selection mode
    private var selectionMode = false

    private var currentEmployeeName: String = ""
    private var filterBatchId: String? = null
    private var currentOrderView: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)
        currentEmployeeName = intent.getStringExtra("employeeName") ?: ""
        currentOrderView = intent.getBooleanExtra("CURRENT_ORDER", false)
        filterBatchId = intent.getStringExtra("BATCH_ID")?.takeIf { it.isNotBlank() }
        if (currentOrderView) title = "Current Order"

        recyclerOrders = findViewById(R.id.recyclerOrders)
        btnMultiDelete = findViewById(R.id.btnMultiDelete)
        btnFilter = findViewById(R.id.btnFilter)

        chipAll = findViewById(R.id.chipAll)
        chipOpen = findViewById(R.id.chipOpen)
        chipClosed = findViewById(R.id.chipClosed)

        adapter = OrdersAdapter(
            onOrderClick = { order ->
                if (selectionMode) {
                    if (order.status != "OPEN") {
                        Toast.makeText(this, "Only open orders can be deleted.", Toast.LENGTH_SHORT).show()
                        return@OrdersAdapter
                    }
                    adapter.toggleSelected(order.id)
                    updateDeleteButtonState()
                } else {
                    val intent = Intent(this, OrderDetailActivity::class.java)
                    intent.putExtra("orderId", order.id)
                    intent.putExtra("employeeName", currentEmployeeName)
                    startActivity(intent)
                }
            },
            onOrderLongPress = { order ->
                if (!selectionMode) {
                    selectionMode = true
                    if (order.status == "OPEN") adapter.toggleSelected(order.id)
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
                    // Only open orders can be deleted
                    val openOrderIds = orders.filter { it.status == "OPEN" }.map { it.id }.toSet()
                    val toDelete = selected.intersect(openOrderIds)
                    val skipped = selected.size - toDelete.size
                    if (toDelete.isEmpty()) {
                        Toast.makeText(this, "Only open orders can be deleted. Select open orders.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (skipped > 0) {
                        adapter.clearSelection()
                        toDelete.forEach { adapter.toggleSelected(it) }
                    }
                    deleteSelectedOrders(toDelete)
                }
            }
        }

        chipAll.setOnClickListener { setFilter("ALL") }
        chipOpen.setOnClickListener { setFilter("OPEN") }
        chipClosed.setOnClickListener { setFilter("CLOSED") }

        btnFilter.setOnClickListener { showFilterDialog() }
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
        statusFilter = newFilter
        startListening()
    }

    private fun startListening() {
        listener?.remove()
        listener = null

        var query: Query = db.collection("Orders")

        if (filterBatchId != null) {
            query = query.whereEqualTo("batchId", filterBatchId)
        }
        query = query.orderBy("createdAt", Query.Direction.DESCENDING)

        when (statusFilter) {
            "OPEN" -> query = query.whereEqualTo("status", "OPEN")
            "CLOSED", "VOIDED", "REFUNDED_FULLY", "PARTIALLY_REFUNDED" -> {
                when (statusFilter) {
                    "VOIDED" -> query = query.whereEqualTo("status", "VOIDED")
                    "PARTIALLY_REFUNDED" -> query = query.whereEqualTo("status", "CLOSED")
                    else -> query = query.whereIn("status", listOf("CLOSED", "VOIDED", "REFUNDED"))
                }
            }
            else -> { /* ALL: no status filter */ }
        }

        if (dateFromMillis != null) {
            query = query.whereGreaterThanOrEqualTo("createdAt", Timestamp(java.util.Date(dateFromMillis!!)))
        }
        if (dateToMillis != null) {
            query = query.whereLessThanOrEqualTo("createdAt", Timestamp(java.util.Date(dateToMillis!!)))
        }

        listener = query.addSnapshotListener { snap, err ->
            if (err != null) {
                Toast.makeText(this, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            allOrders.clear()
            for (doc in snap.documents) {
                val id = doc.id
                val status = doc.getString("status") ?: "OPEN"
                val totalCents = doc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val employee = doc.getString("employeeName") ?: "—"
                val displayEmployee = if (status == "VOIDED") {
                    doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: employee
                } else {
                    employee
                }
                val createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                allOrders.add(
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

            orders.clear()
            orders.addAll(applyStatusFilter(allOrders))
            adapter.submit(orders)
            updateDeleteButtonState()

            if (currentOrderView && orders.isEmpty()) {
                AlertDialog.Builder(this)
                    .setMessage("NO ORDERS AT THE MOMENT")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun applyStatusFilter(list: List<OrderRow>): List<OrderRow> {
        return when (statusFilter) {
            "OPEN" -> list.filter { it.status == "OPEN" }
            "CLOSED" -> list.filter { it.status == "CLOSED" && it.totalRefundedInCents == 0L }
            "VOIDED" -> list.filter { it.status == "VOIDED" }
            "REFUNDED_FULLY" -> list.filter { it.status == "REFUNDED" || it.totalRefundedInCents >= it.totalCents }
            "PARTIALLY_REFUNDED" -> list.filter { it.status == "CLOSED" && it.totalRefundedInCents > 0L && it.totalRefundedInCents < it.totalCents }
            else -> list
        }
    }

    private fun showFilterDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_orders_filter, null)
        val radioAll = view.findViewById<RadioButton>(R.id.radioAll)
        val radioOpen = view.findViewById<RadioButton>(R.id.radioOpen)
        val radioClosed = view.findViewById<RadioButton>(R.id.radioClosed)
        val radioVoided = view.findViewById<RadioButton>(R.id.radioVoided)
        val radioRefundedFully = view.findViewById<RadioButton>(R.id.radioRefundedFully)
        val radioPartiallyRefunded = view.findViewById<RadioButton>(R.id.radioPartiallyRefunded)
        val txtDateFrom = view.findViewById<TextView>(R.id.txtDateFrom)
        val txtDateTo = view.findViewById<TextView>(R.id.txtDateTo)

        when (statusFilter) {
            "OPEN" -> radioOpen.isChecked = true
            "CLOSED" -> radioClosed.isChecked = true
            "VOIDED" -> radioVoided.isChecked = true
            "REFUNDED_FULLY" -> radioRefundedFully.isChecked = true
            "PARTIALLY_REFUNDED" -> radioPartiallyRefunded.isChecked = true
            else -> radioAll.isChecked = true
        }

        val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        fun formatDate(ms: Long?) = if (ms != null) dateFormat.format(ms) else "Any"
        txtDateFrom.text = "From: ${formatDate(dateFromMillis)}"
        txtDateTo.text = "To: ${formatDate(dateToMillis)}"

        txtDateFrom.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            dateFromMillis?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                dateFromMillis = cal.timeInMillis
                txtDateFrom.text = "From: ${formatDate(dateFromMillis)}"
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        txtDateTo.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            dateToMillis?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                cal.set(java.util.Calendar.MILLISECOND, 999)
                dateToMillis = cal.timeInMillis
                txtDateTo.text = "To: ${formatDate(dateToMillis)}"
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<android.widget.Button>(R.id.btnFilterClear).setOnClickListener {
            statusFilter = "ALL"
            dateFromMillis = null
            dateToMillis = null
            filter = "ALL"
            chipAll.isChecked = true
            chipOpen.isChecked = false
            chipClosed.isChecked = false
            dialog.dismiss()
            startListening()
        }
        view.findViewById<android.widget.Button>(R.id.btnFilterApply).setOnClickListener {
            val checkedId = view.findViewById<android.widget.RadioGroup>(R.id.radioGroupStatus).checkedRadioButtonId
            statusFilter = when (checkedId) {
                R.id.radioOpen -> "OPEN"
                R.id.radioClosed -> "CLOSED"
                R.id.radioVoided -> "VOIDED"
                R.id.radioRefundedFully -> "REFUNDED_FULLY"
                R.id.radioPartiallyRefunded -> "PARTIALLY_REFUNDED"
                else -> "ALL"
            }
            filter = if (statusFilter in listOf("ALL", "OPEN", "CLOSED")) statusFilter else "ALL"
            chipAll.isChecked = (filter == "ALL")
            chipOpen.isChecked = (filter == "OPEN")
            chipClosed.isChecked = (filter == "CLOSED")
            dialog.dismiss()
            startListening()
        }
        dialog.show()
    }

    private fun updateDeleteButtonState() {
        val selectedCount = adapter.getSelectedIds().size
        btnMultiDelete.alpha = if (!selectionMode) 1f else if (selectedCount == 0) 0.6f else 1f
    }

    private fun deleteSelectedOrders(selectedIds: Set<String>) {
        // selectedIds are already restricted to OPEN orders by caller
        lifecycleScope.launch {
            try {
                btnMultiDelete.isEnabled = false

                withContext(Dispatchers.IO) {
                    for (orderId in selectedIds) {
                        deleteOrderWithItems(orderId)
                    }
                }

                Toast.makeText(this@OrdersActivity, "Deleted ${selectedIds.size} order(s)", Toast.LENGTH_SHORT).show()

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