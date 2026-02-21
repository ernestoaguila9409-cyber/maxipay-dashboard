package com.ernesto.myapplication

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class MenuActivity : AppCompatActivity() {

    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: GridLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var txtTotal: TextView

    private val db = FirebaseFirestore.getInstance()

    private var totalAmount = 0.0
    private val cartItems = mutableListOf<Pair<String, Double>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)
        cartContainer = findViewById(R.id.cartContainer)
        txtTotal = findViewById(R.id.txtTotal)

        loadCategories()
    }

    // =====================================
    // LOAD CATEGORIES
    // =====================================

    private fun loadCategories() {

        categoryContainer.removeAllViews()

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val categoryId = doc.id
                    addCategoryButton(name, categoryId)
                }
            }
    }

    private fun addCategoryButton(name: String, categoryId: String) {

        val button = Button(this)
        button.text = name
        button.setBackgroundColor(Color.LTGRAY)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 20, 0)
        button.layoutParams = params

        button.setOnClickListener {
            loadItems(categoryId)
        }

        categoryContainer.addView(button)
    }

    // =====================================
    // LOAD ITEMS INTO GRID
    // =====================================

    private fun loadItems(categoryId: String) {

        itemContainer.removeAllViews()

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0

                    addItemButton(name, price)
                }
            }
    }

    private fun addItemButton(name: String, price: Double) {

        val button = Button(this)
        button.text = "$name\n$${String.format(Locale.US, "%.2f", price)}"
        button.setBackgroundColor(Color.parseColor("#6A4FB3"))
        button.setTextColor(Color.WHITE)

        val params = GridLayout.LayoutParams()
        params.width = 0
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        params.setMargins(12, 12, 12, 12)

        button.layoutParams = params

        button.setOnClickListener {
            addToCart(name, price)
        }

        itemContainer.addView(button)
    }

    // =====================================
    // CART LOGIC
    // =====================================

    private fun addToCart(name: String, price: Double) {

        cartItems.add(Pair(name, price))
        totalAmount += price

        refreshCart()
    }

    private fun refreshCart() {

        cartContainer.removeAllViews()

        for ((name, price) in cartItems) {

            val textView = TextView(this)
            textView.text = "$name - $${String.format(Locale.US, "%.2f", price)}"
            textView.setPadding(8, 8, 8, 8)

            cartContainer.addView(textView)
        }

        txtTotal.text = "Total: $${String.format(Locale.US, "%.2f", totalAmount)}"
    }
}