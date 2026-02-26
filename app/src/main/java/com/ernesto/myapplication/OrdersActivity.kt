package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

class OrdersActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recyclerOrders: RecyclerView
    private lateinit var adapter: OrdersAdapter

    // What the adapter displays
    private val orderList = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

    // Full list from Firestore (source of truth)
    private val allOrders = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

    private var currentFilter = "ALL" // ALL | OPEN | CLOSED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)

        recyclerOrders = findViewById(R.id.recyclerOrders)
        recyclerOrders.layoutManager = LinearLayoutManager(this)

        adapter = OrdersAdapter(orderList)
        recyclerOrders.adapter = adapter

        recyclerOrders.addItemDecoration(
            object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect,
                    view: android.view.View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    outRect.top = 16
                    outRect.left = 8
                    outRect.right = 8
                    outRect.bottom = 16
                }
            }
        )

        // Filter chips (must exist in activity_orders.xml)
        val chipAll = findViewById<com.google.android.material.chip.Chip>(R.id.chipAll)
        val chipOpen = findViewById<com.google.android.material.chip.Chip>(R.id.chipOpen)
        val chipClosed = findViewById<com.google.android.material.chip.Chip>(R.id.chipClosed)

        chipAll.setOnClickListener {
            currentFilter = "ALL"
            applyFilter()
        }
        chipOpen.setOnClickListener {
            currentFilter = "OPEN"
            applyFilter()
        }
        chipClosed.setOnClickListener {
            currentFilter = "CLOSED"
            applyFilter()
        }

        loadOrders()
    }

    private fun loadOrders() {
        db.collection("Orders")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                allOrders.clear()
                allOrders.addAll(documents.documents)
                applyFilter()
            }
    }

    private fun applyFilter() {
        orderList.clear()

        when (currentFilter) {
            "OPEN" -> orderList.addAll(allOrders.filter { it.getString("status") == "OPEN" })
            "CLOSED" -> orderList.addAll(allOrders.filter { it.getString("status") == "CLOSED" })
            else -> orderList.addAll(allOrders)
        }

        adapter.notifyDataSetChanged()
    }

    inner class OrdersAdapter(
        private val orders: List<com.google.firebase.firestore.DocumentSnapshot>
    ) : RecyclerView.Adapter<OrdersAdapter.OrderViewHolder>() {

        inner class OrderViewHolder(view: android.view.View) :
            RecyclerView.ViewHolder(view) {

            val txtStatus: android.widget.TextView = view.findViewById(R.id.txtStatus)
            val txtTotal: android.widget.TextView = view.findViewById(R.id.txtTotal)
            val txtEmployee: android.widget.TextView = view.findViewById(R.id.txtEmployee)
            val txtTime: android.widget.TextView = view.findViewById(R.id.txtTime)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): OrderViewHolder {
            val view = layoutInflater.inflate(R.layout.item_order, parent, false)
            return OrderViewHolder(view)
        }

        override fun getItemCount(): Int = orders.size

        override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
            val doc = orders[position]

            val status = doc.getString("status") ?: ""
            val total = doc.getDouble("total") ?: 0.0
            val employee = doc.getString("employeeName") ?: ""
            val date = doc.getDate("createdAt")

            holder.txtStatus.text = status
            holder.txtTotal.text = "$${String.format(Locale.US, "%.2f", total)}"
            holder.txtEmployee.text = employee

            if (date != null) {
                val format = SimpleDateFormat("MMM dd • hh:mm a", Locale.US)
                holder.txtTime.text = format.format(date)
            } else {
                holder.txtTime.text = ""
            }

            // Status styling
            if (status == "OPEN") {
                holder.txtStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                holder.txtStatus.setBackgroundResource(R.drawable.status_badge_background)
            } else {
                holder.txtStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
                holder.txtStatus.setBackgroundResource(R.drawable.status_badge_closed)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@OrdersActivity, OrderDetailActivity::class.java)
                intent.putExtra("ORDER_ID", doc.id)
                startActivity(intent)
            }
        }
    }
}