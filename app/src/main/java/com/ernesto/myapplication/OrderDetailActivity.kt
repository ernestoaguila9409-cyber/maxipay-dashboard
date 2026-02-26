package com.ernesto.myapplication

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView

    private val itemDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
    private lateinit var adapter: OrderItemsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        recycler = findViewById(R.id.recyclerOrderItems)
        txtEmptyItems = findViewById(R.id.txtEmptyItems)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = OrderItemsAdapter(itemDocs)
        recycler.adapter = adapter

        // spacing between item cards
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

        val orderId = intent.getStringExtra("ORDER_ID") ?: return

        loadHeader(orderId)
        loadItems(orderId)
    }

    private fun loadHeader(orderId: String) {
        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                val employee = doc.getString("employeeName") ?: ""
                val createdAt = doc.getDate("createdAt")

                val txtEmployee = findViewById<TextView>(R.id.txtHeaderEmployee)
                val txtTime = findViewById<TextView>(R.id.txtHeaderTime)

                txtEmployee.text = employee

                if (createdAt != null) {
                    txtTime.text =
                        SimpleDateFormat("MMM dd • hh:mm a", Locale.US).format(createdAt)
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

                // show a friendly message instead of a blank screen
                if (itemDocs.isEmpty()) {
                    txtEmptyItems.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                } else {
                    txtEmptyItems.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                }
            }
    }
}