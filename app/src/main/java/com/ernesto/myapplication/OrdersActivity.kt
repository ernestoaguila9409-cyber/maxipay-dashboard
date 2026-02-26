package com.ernesto.myapplication

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class OrdersActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var ordersContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)

        ordersContainer = findViewById(R.id.ordersContainer)

        loadOrders()
    }

    private fun loadOrders() {
        ordersContainer.removeAllViews()

        db.collection("Orders")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {
                    addOrderView(doc)
                }
            }
    }

    private fun addSectionTitle(title: String) {
        val textView = TextView(this)
        textView.text = title
        textView.textSize = 18f
        textView.setPadding(16, 32, 16, 16)
        textView.setTypeface(null, android.graphics.Typeface.BOLD)

        ordersContainer.addView(textView)
    }

    private fun addOrderView(doc: com.google.firebase.firestore.DocumentSnapshot) {

        val employeeName = doc.getString("employeeName") ?: "Unknown"
        val total = doc.getDouble("total") ?: 0.0
        val status = doc.getString("status") ?: ""
        val paymentType = doc.getString("paymentType") ?: ""

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(24, 24, 24, 24)

        val title = TextView(this)
        title.text = "$employeeName  -  $${String.format(Locale.US, "%.2f", total)}"
        title.textSize = 16f
        title.setTypeface(null, android.graphics.Typeface.BOLD)

        val subtitle = TextView(this)
        subtitle.text =
            if (status == "CLOSED")
                "Paid via $paymentType"
            else
                "Still Open"

        container.addView(title)
        container.addView(subtitle)

        val divider = TextView(this)
        divider.text = "-------------------------------------"
        divider.setPadding(0, 16, 0, 16)

        container.addView(divider)

        container.setOnClickListener {
            val intent = android.content.Intent(this, OrderDetailActivity::class.java)
            intent.putExtra("ORDER_ID", doc.id)
            startActivity(intent)
        }
        ordersContainer.addView(container)
    }
}