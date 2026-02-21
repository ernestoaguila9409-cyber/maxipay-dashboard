package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class MenuActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: GridLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnCheckout: Button

    private val cart = mutableMapOf<String, CartItem>()
    private var totalAmount = 0.0
    private var currentBatchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)
        cartContainer = findViewById(R.id.cartContainer)
        txtTotal = findViewById(R.id.txtTotal)
        btnCheckout = findViewById(R.id.btnCheckout)

        currentBatchId = intent.getStringExtra("batchId") ?: ""

        btnCheckout.setOnClickListener {

            if (cart.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("total", totalAmount)
            intent.putExtra("batchId", currentBatchId)

            // Send cart items
            intent.putExtra("cartItems", ArrayList(cart.values))

            startActivity(intent)

            cart.clear()
            refreshCart()
        }

        loadCategories()
    }

    // =========================
    // LOAD CATEGORIES
    // =========================
    private fun loadCategories() {

        categoryContainer.removeAllViews()

        db.collection("Categories")
            .whereEqualTo("active", true)
            .orderBy("position")
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: ""

                    val button = Button(this)
                    button.text = name.uppercase()

                    button.setOnClickListener {
                        loadItems(name)
                    }

                    categoryContainer.addView(button)
                }
            }
    }

    // =========================
    // LOAD ITEMS
    // =========================
    private fun loadItems(categoryName: String) {

        itemContainer.removeAllViews()

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryName)
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: ""
                    val price = doc.getDouble("basePrice") ?: 0.0

                    val button = Button(this)
                    button.text = "$name\n$%.2f".format(price)

                    button.setOnClickListener {
                        addToCart(name, price)
                    }

                    itemContainer.addView(button)
                }
            }
    }

    // =========================
    // ADD TO CART
    // =========================
    private fun addToCart(name: String, price: Double) {

        if (cart.containsKey(name)) {
            cart[name]!!.quantity++
        } else {
            cart[name] = CartItem(name, price, 1)
        }

        refreshCart()
    }

    // =========================
    // REFRESH CART UI
    // =========================
    private fun refreshCart() {

        cartContainer.removeAllViews()
        totalAmount = 0.0

        for (item in cart.values) {

            val lineTotal = item.price * item.quantity
            totalAmount += lineTotal

            val textView = TextView(this)
            textView.text = "${item.name} x${item.quantity}  $%.2f".format(lineTotal)
            textView.textSize = 16f

            cartContainer.addView(textView)
        }

        txtTotal.text = "Total: $%.2f".format(totalAmount)
    }

    // =========================
    // CART MODEL (Serializable)
    // =========================
    data class CartItem(
        val name: String,
        val price: Double,
        var quantity: Int
    ) : java.io.Serializable
}