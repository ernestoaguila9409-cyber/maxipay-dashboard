package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class MenuActivity : AppCompatActivity() {

    // name -> (quantity, price)
    private val cartMap = mutableMapOf<String, Pair<Int, Double>>()

    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: GridLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnCheckout: Button

    private val db = FirebaseFirestore.getInstance()
    private var totalAmount = 0.0

    // ✅ MODERN RESULT HANDLER
    private val paymentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK) {
                clearCart()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)
        cartContainer = findViewById(R.id.cartContainer)
        txtTotal = findViewById(R.id.txtTotal)
        btnCheckout = findViewById(R.id.btnCheckout)

        loadCategories()

        btnCheckout.setOnClickListener {

            if (cartMap.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("TOTAL_AMOUNT", totalAmount)
            paymentLauncher.launch(intent)
        }
    }

    // ==========================
    // CLEAR CART AFTER PAYMENT
    // ==========================
    private fun clearCart() {

        cartMap.clear()
        totalAmount = 0.0

        cartContainer.removeAllViews()
        txtTotal.text = "Total: $0.00"
    }

    // ==========================
    // LOAD CATEGORIES
    // ==========================
    private fun loadCategories() {

        categoryContainer.removeAllViews()

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: continue
                    val categoryId = doc.id

                    val button = Button(this)
                    button.text = name
                    button.setPadding(40, 20, 40, 20)

                    button.setOnClickListener {
                        loadItems(categoryId)
                    }

                    categoryContainer.addView(button)
                }
            }
    }

    // ==========================
    // LOAD ITEMS
    // ==========================
    private fun loadItems(categoryId: String) {

        itemContainer.removeAllViews()

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0

                    val button = Button(this)
                    button.text = name
                    button.textSize = 16f
                    button.setTextColor(Color.WHITE)
                    button.setBackgroundResource(R.drawable.item_tile)

                    button.setOnClickListener {
                        addToCart(name, price)
                    }

                    val params = GridLayout.LayoutParams()
                    params.width = 0
                    params.height = GridLayout.LayoutParams.WRAP_CONTENT
                    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    params.setMargins(16, 16, 16, 16)

                    button.layoutParams = params
                    itemContainer.addView(button)
                }
            }
    }

    // ==========================
    // ADD TO CART
    // ==========================
    private fun addToCart(name: String, price: Double) {

        val current = cartMap[name]

        if (current != null) {
            cartMap[name] = Pair(current.first + 1, price)
        } else {
            cartMap[name] = Pair(1, price)
        }

        refreshCart()
    }

    // ==========================
    // REFRESH CART
    // ==========================
    private fun refreshCart() {

        cartContainer.removeAllViews()
        totalAmount = 0.0

        for ((name, data) in cartMap) {

            val quantity = data.first
            val price = data.second
            val itemTotal = quantity * price

            totalAmount += itemTotal

            val textView = TextView(this)
            textView.text =
                "$name x$quantity - $${String.format(Locale.US, "%.2f", itemTotal)}"
            textView.textSize = 16f
            textView.setPadding(8, 8, 8, 8)

            cartContainer.addView(textView)
        }

        updateTotal()
    }

    // ==========================
    // UPDATE TOTAL
    // ==========================
    private fun updateTotal() {
        txtTotal.text =
            "Total: $${String.format(Locale.US, "%.2f", totalAmount)}"
    }
}