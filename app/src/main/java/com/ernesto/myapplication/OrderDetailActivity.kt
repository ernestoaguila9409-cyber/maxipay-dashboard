package com.ernesto.myapplication

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var itemsContainer: LinearLayout
    private lateinit var txtHeader: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        itemsContainer = findViewById(R.id.itemsContainer)
        txtHeader = findViewById(R.id.txtHeader)

        val orderId = intent.getStringExtra("ORDER_ID") ?: ""
        if (orderId.isBlank()) {
            txtHeader.text = "Missing ORDER_ID"
            return
        }

        loadOrder(orderId)
        loadItems(orderId)
    }

    private fun loadOrder(orderId: String) {
        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->
                val employee = doc.getString("employeeName") ?: "Unknown"
                val status = doc.getString("status") ?: "?"
                val total = doc.getDouble("total") ?: 0.0

                txtHeader.text = "$status • $employee • Total: $${String.format(Locale.US, "%.2f", total)}"
            }
    }

    private fun loadItems(orderId: String) {
        itemsContainer.removeAllViews()

        db.collection("Orders")
            .document(orderId)
            .collection("items")
            .get()
            .addOnSuccessListener { docs ->

                if (docs.isEmpty) {
                    val empty = TextView(this)
                    empty.text = "No items"
                    empty.textSize = 16f
                    itemsContainer.addView(empty)
                    return@addOnSuccessListener
                }

                for (doc in docs) {

                    val name = doc.getString("name") ?: "Item"
                    val qty = (doc.getLong("quantity") ?: 0L).toInt()
                    val unitPrice = doc.getDouble("unitPrice") ?: 0.0
                    val lineTotal = doc.getDouble("lineTotal") ?: 0.0

                    val block = LinearLayout(this)
                    block.orientation = LinearLayout.VERTICAL
                    block.setPadding(24, 24, 24, 24)

                    val title = TextView(this)
                    title.text = "$name  (Qty: $qty)"
                    title.textSize = 18f
                    title.setTypeface(null, android.graphics.Typeface.BOLD)
                    block.addView(title)

                    val price = TextView(this)
                    price.text = "Unit: $${String.format(Locale.US, "%.2f", unitPrice)}    Line: $${String.format(Locale.US, "%.2f", lineTotal)}"
                    price.textSize = 14f
                    block.addView(price)

                    // modifiers is saved as array of maps like: [{first:"Onion", second:0.0}, ...]
                    val modsAny = doc.get("modifiers")
                    val modsList = mutableListOf<Pair<String, Double>>()

                    if (modsAny is List<*>) {
                        for (m in modsAny) {
                            if (m is Map<*, *>) {
                                val first = m["first"]?.toString() ?: ""
                                val second = (m["second"] as? Number)?.toDouble() ?: 0.0
                                if (first.isNotBlank()) modsList.add(first to second)
                            }
                        }
                    }

                    if (modsList.isNotEmpty()) {
                        val modsTitle = TextView(this)
                        modsTitle.text = "Modifiers:"
                        modsTitle.setPadding(0, 12, 0, 4)
                        modsTitle.setTypeface(null, android.graphics.Typeface.BOLD)
                        block.addView(modsTitle)

                        for (mod in modsList) {
                            val t = TextView(this)
                            t.text = "• ${mod.first}  (+$${String.format(Locale.US, "%.2f", mod.second)})"
                            block.addView(t)
                        }
                    }

                    val divider = View(this)
                    divider.setBackgroundColor(android.graphics.Color.LTGRAY)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        2
                    )
                    params.setMargins(0, 24, 0, 24)
                    divider.layoutParams = params
                    block.addView(divider)

                    itemsContainer.addView(block)
                }
            }
    }
}