package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView

    // OPEN order action
    private lateinit var btnCheckout: com.google.android.material.button.MaterialButton

    // CLOSED order actions (bottom)
    private lateinit var bottomActions: View
    private lateinit var btnVoid: com.google.android.material.button.MaterialButton
    private lateinit var btnRefund: com.google.android.material.button.MaterialButton

    private val itemDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
    private lateinit var adapter: OrderItemsAdapter

    private var currentOrderId: String = ""
    private var currentOrderStatus: String = ""
    private var currentBatchId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        recycler = findViewById(R.id.recyclerOrderItems)
        txtEmptyItems = findViewById(R.id.txtEmptyItems)

        btnCheckout = findViewById(R.id.btnCheckout)

        bottomActions = findViewById(R.id.bottomActions)
        btnVoid = findViewById(R.id.btnVoid)
        btnRefund = findViewById(R.id.btnRefund)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = OrderItemsAdapter(itemDocs)
        recycler.adapter = adapter

        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.top = 12
                outRect.left = 6
                outRect.right = 6
                outRect.bottom = 12
            }
        })

        currentOrderId = intent.getStringExtra("ORDER_ID") ?: return

        loadHeader(currentOrderId)
        loadItems(currentOrderId)
    }

    private fun loadHeader(orderId: String) {
        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                val status = doc.getString("status") ?: ""
                currentOrderStatus = status

                val employee = doc.getString("employeeName") ?: ""
                val createdAt = doc.getDate("createdAt")
                currentBatchId = doc.getString("batchId")

                val txtEmployee = findViewById<TextView>(R.id.txtHeaderEmployee)
                val txtTime = findViewById<TextView>(R.id.txtHeaderTime)

                txtEmployee.text = employee
                txtTime.text = if (createdAt != null) {
                    SimpleDateFormat("MMM dd • hh:mm a", Locale.US).format(createdAt)
                } else ""

                // Reset buttons
                btnCheckout.visibility = View.GONE
                btnCheckout.setOnClickListener(null)
                bottomActions.visibility = View.GONE
                btnVoid.visibility = View.GONE
                btnRefund.visibility = View.GONE

                // ✅ OPEN -> show Checkout (bottom in your XML)
                if (status == "OPEN") {
                    btnCheckout.visibility = View.VISIBLE
                    btnCheckout.setOnClickListener {
                        val i = Intent(this@OrderDetailActivity, MenuActivity::class.java)
                        i.putExtra("ORDER_ID", orderId)

                        // Optional extras (safe even if empty)
                        i.putExtra("batchId", intent.getStringExtra("batchId") ?: "")
                        i.putExtra("employeeName", intent.getStringExtra("employeeName") ?: "")
                        startActivity(i)
                    }
                    return@addOnSuccessListener
                }

                // ✅ CLOSED -> show Refund always, show Void only if batch is still open
                if (status == "CLOSED") {
                    bottomActions.visibility = View.VISIBLE
                    btnRefund.visibility = View.VISIBLE

                    btnRefund.setOnClickListener { confirmRefund(orderId) }
                    btnVoid.setOnClickListener { confirmVoid(orderId) }

                    val batchId = currentBatchId
                    if (batchId.isNullOrBlank()) {
                        // can't determine batch -> refund only
                        btnVoid.visibility = View.GONE
                    } else {
                        db.collection("Batches").document(batchId)
                            .get()
                            .addOnSuccessListener { batchDoc ->
                                val batchClosed = batchDoc.getBoolean("closed") ?: true
                                btnVoid.visibility = if (!batchClosed) View.VISIBLE else View.GONE
                            }
                            .addOnFailureListener {
                                // fallback refund only
                                btnVoid.visibility = View.GONE
                            }
                    }
                }
            }
    }

    private fun deleteOrderIfEmpty(orderId: String) {
        db.collection("Orders").document(orderId)
            .collection("items")
            .limit(1)
            .get()
            .addOnSuccessListener { docs ->
                if (docs.isEmpty) {
                    db.collection("Orders").document(orderId)
                        .delete()
                        .addOnSuccessListener { finish() }
                }
            }
    }

    private fun loadItems(orderId: String) {
        db.collection("Orders").document(orderId)
            .collection("items")
            .get()
            .addOnSuccessListener { docs ->
                itemDocs.clear()
                itemDocs.addAll(docs.documents)
                adapter.notifyDataSetChanged()

                if (itemDocs.isEmpty()) {
                    txtEmptyItems.visibility = View.VISIBLE
                    recycler.visibility = View.GONE

                    // ✅ remove empty OPEN orders from database
                    deleteOrderIfEmpty(orderId)
                } else {
                    txtEmptyItems.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                }
            }
    }

    // ----------------------------
    // VOID / REFUND dialogs
    // ----------------------------

    private fun confirmVoid(orderId: String) {
        AlertDialog.Builder(this)
            .setTitle("Void Order")
            .setMessage("Void this order? (Only allowed if batch is still open)")
            .setPositiveButton("Void") { _, _ ->
                // For now: just show message. We can connect real logic next.
                Toast.makeText(this, "VOID pressed for $orderId", Toast.LENGTH_SHORT).show()

                // TODO (next step): mark SALE transaction voided, update order status, optionally restore stock.
                // voidOrder(orderId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRefund(orderId: String) {
        AlertDialog.Builder(this)
            .setTitle("Refund Order")
            .setMessage("Refund this order?")
            .setPositiveButton("Refund") { _, _ ->
                Toast.makeText(this, "REFUND pressed for $orderId", Toast.LENGTH_SHORT).show()

                // TODO (next step): create REFUND transaction, update order status, optionally restore stock.
                // refundOrder(orderId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}