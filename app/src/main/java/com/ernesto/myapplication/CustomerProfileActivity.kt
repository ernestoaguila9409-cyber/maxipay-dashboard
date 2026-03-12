package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

class CustomerProfileActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtProfileName: TextView
    private lateinit var txtProfilePhone: TextView
    private lateinit var txtProfileEmail: TextView
    private lateinit var txtLifetimeSpend: TextView
    private lateinit var txtVisits: TextView
    private lateinit var txtLastVisit: TextView
    private lateinit var txtEmptyOrders: TextView
    private lateinit var recyclerOrderHistory: RecyclerView

    private val orderHistoryAdapter = OrderHistoryAdapter { orderId ->
        val intent = Intent(this, OrderDetailActivity::class.java)
        intent.putExtra("orderId", orderId)
        startActivity(intent)
    }

    private var customerId: String = ""
    private var customerName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_profile)

        customerId = intent.getStringExtra("customerId") ?: ""
        customerName = intent.getStringExtra("customerName") ?: ""

        if (customerId.isBlank()) {
            Toast.makeText(this, "Invalid customer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        txtProfileName = findViewById(R.id.txtProfileName)
        txtProfilePhone = findViewById(R.id.txtProfilePhone)
        txtProfileEmail = findViewById(R.id.txtProfileEmail)
        txtLifetimeSpend = findViewById(R.id.txtLifetimeSpend)
        txtVisits = findViewById(R.id.txtVisits)
        txtLastVisit = findViewById(R.id.txtLastVisit)
        txtEmptyOrders = findViewById(R.id.txtEmptyOrders)
        recyclerOrderHistory = findViewById(R.id.recyclerOrderHistory)

        recyclerOrderHistory.layoutManager = LinearLayoutManager(this)
        recyclerOrderHistory.adapter = orderHistoryAdapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        loadCustomerInfo()
        loadOrderHistory()
    }

    private fun loadCustomerInfo() {
        db.collection("Customers").document(customerId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                val nameField = doc.getString("name") ?: ""
                val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                    "$firstName $lastName".trim()
                } else {
                    nameField
                }
                val phone = doc.getString("phone") ?: ""
                val email = doc.getString("email") ?: ""

                txtProfileName.text = fullName.ifBlank { "Customer" }

                if (phone.isNotBlank()) {
                    txtProfilePhone.text = formatPhone(phone)
                    txtProfilePhone.visibility = View.VISIBLE
                }

                if (email.isNotBlank()) {
                    txtProfileEmail.text = email
                    txtProfileEmail.visibility = View.VISIBLE
                }
            }
    }

    private fun loadOrderHistory() {
        db.collection("Orders")
            .whereEqualTo("customerId", customerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val orders = mutableListOf<OrderHistoryItem>()
                var totalSpendCents = 0L
                var latestTimestamp: Timestamp? = null
                val dateFormat = SimpleDateFormat("MMM dd", Locale.US)

                for (doc in snap.documents) {
                    val orderNumber = doc.getLong("orderNumber") ?: 0L
                    val orderType = doc.getString("orderType") ?: ""
                    val totalInCents = doc.getLong("totalInCents") ?: 0L
                    val createdAt = doc.getTimestamp("createdAt")
                    val status = doc.getString("status") ?: ""

                    if (status != "VOIDED") {
                        totalSpendCents += totalInCents
                    }

                    if (latestTimestamp == null && createdAt != null) {
                        latestTimestamp = createdAt
                    }

                    val dateStr = if (createdAt != null) {
                        dateFormat.format(createdAt.toDate())
                    } else ""

                    orders.add(
                        OrderHistoryItem(
                            orderId = doc.id,
                            orderNumber = orderNumber,
                            orderType = orderType,
                            totalInCents = totalInCents,
                            dateStr = dateStr
                        )
                    )
                }

                txtLifetimeSpend.text = MoneyUtils.centsToDisplay(totalSpendCents)
                txtVisits.text = orders.size.toString()

                if (latestTimestamp != null) {
                    txtLastVisit.text = dateFormat.format(latestTimestamp.toDate())
                } else {
                    txtLastVisit.text = "—"
                }

                if (orders.isEmpty()) {
                    txtEmptyOrders.visibility = View.VISIBLE
                    recyclerOrderHistory.visibility = View.GONE
                } else {
                    txtEmptyOrders.visibility = View.GONE
                    recyclerOrderHistory.visibility = View.VISIBLE
                    orderHistoryAdapter.submitList(orders)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load orders: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun formatPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "${digits.take(3)}-${digits.drop(3).take(3)}-${digits.takeLast(4)}"
            digits.length == 11 && digits.startsWith("1") -> "${digits.drop(1).take(3)}-${digits.drop(4).take(3)}-${digits.takeLast(4)}"
            else -> phone
        }
    }
}

data class OrderHistoryItem(
    val orderId: String,
    val orderNumber: Long,
    val orderType: String,
    val totalInCents: Long,
    val dateStr: String
)

class OrderHistoryAdapter(
    private val onOrderClick: (orderId: String) -> Unit
) : RecyclerView.Adapter<OrderHistoryAdapter.VH>() {

    private var items: List<OrderHistoryItem> = emptyList()

    fun submitList(list: List<OrderHistoryItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_history, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtOrderNumber: TextView = itemView.findViewById(R.id.txtOrderNumber)
        private val txtOrderType: TextView = itemView.findViewById(R.id.txtOrderType)
        private val txtOrderTotal: TextView = itemView.findViewById(R.id.txtOrderTotal)
        private val txtOrderDate: TextView = itemView.findViewById(R.id.txtOrderDate)

        fun bind(item: OrderHistoryItem) {
            txtOrderNumber.text = "#${item.orderNumber}"
            txtOrderTotal.text = MoneyUtils.centsToDisplay(item.totalInCents)
            txtOrderDate.text = item.dateStr

            val typeLabel = when (item.orderType) {
                "DINE_IN" -> "DINE IN"
                "TO_GO" -> "TO GO"
                "BAR_TAB", "BAR" -> "BAR TAB"
                else -> item.orderType
            }
            txtOrderType.text = typeLabel

            val bgColor = when (item.orderType) {
                "DINE_IN" -> "#E8F5E9"
                "TO_GO" -> "#E3F2FD"
                "BAR_TAB", "BAR" -> "#FFF3E0"
                else -> "#F5F5F5"
            }
            val textColor = when (item.orderType) {
                "DINE_IN" -> "#2E7D32"
                "TO_GO" -> "#1565C0"
                "BAR_TAB", "BAR" -> "#E65100"
                else -> "#555555"
            }

            val badge = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 12f
            }
            txtOrderType.background = badge
            txtOrderType.setTextColor(Color.parseColor(textColor))

            itemView.setOnClickListener { onOrderClick(item.orderId) }
        }
    }
}
