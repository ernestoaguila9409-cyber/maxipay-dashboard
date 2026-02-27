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
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView

    private lateinit var btnCheckout: MaterialButton
    private lateinit var bottomActions: View
    private lateinit var btnVoid: MaterialButton
    private lateinit var btnRefund: MaterialButton

    private lateinit var adapter: OrderItemsAdapter
    private val itemDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

    private lateinit var orderId: String
    private var currentOrderStatus: String = ""
    private var currentBatchId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        // ✅ Correct key (must match OrdersActivity)
        orderId = intent.getStringExtra("orderId") ?: ""

        if (orderId.isBlank()) {
            Toast.makeText(this, "Invalid order ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
                outRect.set(12, 12, 12, 12)
            }
        })

        loadHeader()
        loadItems()
    }

    private fun loadHeader() {
        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) return@addOnSuccessListener

                val status = doc.getString("status") ?: ""
                currentOrderStatus = status
                currentBatchId = doc.getString("batchId")

                val employee = doc.getString("employeeName") ?: ""
                val createdAt = doc.getDate("createdAt")

                findViewById<TextView>(R.id.txtHeaderEmployee).text = employee
                findViewById<TextView>(R.id.txtHeaderTime).text =
                    createdAt?.let {
                        SimpleDateFormat("MMM dd • hh:mm a", Locale.US).format(it)
                    } ?: ""

                btnCheckout.visibility = View.GONE
                bottomActions.visibility = View.GONE
                btnVoid.visibility = View.GONE
                btnRefund.visibility = View.GONE

                if (status == "OPEN") {
                    btnCheckout.visibility = View.VISIBLE
                    btnCheckout.setOnClickListener {
                        val i = Intent(this, MenuActivity::class.java)
                        i.putExtra("ORDER_ID", orderId)
                        startActivity(i)
                    }
                }

                if (status == "CLOSED") {
                    bottomActions.visibility = View.VISIBLE
                    btnRefund.visibility = View.VISIBLE
                    btnRefund.setOnClickListener { confirmRefund() }

                    val batchId = currentBatchId
                    if (!batchId.isNullOrBlank()) {
                        db.collection("Batches").document(batchId)
                            .get()
                            .addOnSuccessListener { batchDoc ->
                                val batchClosed = batchDoc.getBoolean("closed") ?: true
                                btnVoid.visibility =
                                    if (!batchClosed) View.VISIBLE else View.GONE
                            }
                    }
                }
            }
    }

    private fun loadItems() {
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
                } else {
                    txtEmptyItems.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                }
            }
    }

    private fun confirmVoid() {
        AlertDialog.Builder(this)
            .setTitle("Void Order")
            .setMessage("Void this order?")
            .setPositiveButton("Void") { _, _ ->
                Toast.makeText(this, "VOID pressed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRefund() {
        AlertDialog.Builder(this)
            .setTitle("Refund Order")
            .setMessage("Refund this order?")
            .setPositiveButton("Refund") { _, _ ->
                Toast.makeText(this, "REFUND pressed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}