package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

// =============================================
// CART MODEL
// =============================================
data class CartItem(
    val itemId: String,
    val name: String,
    var quantity: Int,
    val price: Double,
    val stock: Long
)

class MenuActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    // itemId -> CartItem
    private val cartMap = mutableMapOf<String, CartItem>()

    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: GridLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnCheckout: Button

    private var totalAmount = 0.0

    // =============================================
    // PAYMENT RESULT HANDLER
    // =============================================
    private val paymentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK) {

                deductStockTransaction(
                    onSuccess = {
                        clearCart()
                        Toast.makeText(
                            this,
                            "Stock updated successfully",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Reload visible items to refresh stock display
                        loadCategories()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            error,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
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

    // =============================================
    // LOAD CATEGORIES
    // =============================================
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

    // =============================================
    // LOAD ITEMS
    // =============================================
    private fun loadItems(categoryId: String) {

        itemContainer.removeAllViews()

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val itemId = doc.id
                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0
                    val stock = doc.getLong("stock") ?: 0L

                    val button = Button(this)

                    button.text =
                        "$name\n$${String.format(Locale.US, "%.2f", price)}\nStock: $stock"

                    button.textSize = 14f
                    button.setTextColor(Color.WHITE)

                    when {
                        stock <= 0 -> {
                            button.setBackgroundColor(Color.parseColor("#D32F2F"))
                            button.isEnabled = false
                        }

                        stock <= 5 -> {
                            button.setBackgroundColor(Color.parseColor("#F57C00"))
                        }

                        else -> {
                            button.setBackgroundColor(Color.parseColor("#6A4FB3"))
                        }
                    }

                    button.setOnClickListener {
                        addToCart(itemId, name, price, stock)
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

    // =============================================
    // ADD TO CART (UI STOCK PROTECTION)
    // =============================================
    private fun addToCart(
        itemId: String,
        name: String,
        price: Double,
        stock: Long
    ) {

        val existingItem = cartMap[itemId]

        if (existingItem != null) {

            if (existingItem.quantity >= stock) {
                Toast.makeText(
                    this,
                    "Only $stock in stock.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            existingItem.quantity += 1

        } else {

            if (stock <= 0) {
                Toast.makeText(
                    this,
                    "Out of stock.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            cartMap[itemId] = CartItem(
                itemId = itemId,
                name = name,
                quantity = 1,
                price = price,
                stock = stock
            )
        }

        refreshCart()
    }

    // =============================================
    // REFRESH CART
    // =============================================
    private fun refreshCart() {

        cartContainer.removeAllViews()
        totalAmount = 0.0

        for ((_, item) in cartMap) {

            val itemTotal = item.quantity * item.price
            totalAmount += itemTotal

            val textView = TextView(this)
            textView.text =
                "${item.name} x${item.quantity} - $${String.format(Locale.US, "%.2f", itemTotal)}"
            textView.textSize = 16f
            textView.setPadding(8, 8, 8, 8)

            cartContainer.addView(textView)
        }

        txtTotal.text =
            "Total: $${String.format(Locale.US, "%.2f", totalAmount)}"
    }

    // =============================================
    // FIRESTORE TRANSACTION (MULTI DEVICE SAFE)
    // =============================================
    private fun deductStockTransaction(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {

        db.runTransaction { transaction ->

            for ((_, cartItem) in cartMap) {

                val itemRef = db.collection("MenuItems")
                    .document(cartItem.itemId)

                val snapshot = transaction.get(itemRef)

                val currentStock = snapshot.getLong("stock") ?: 0L

                if (currentStock < cartItem.quantity) {
                    throw Exception("Stock changed. Not enough inventory.")
                }

                val newStock = currentStock - cartItem.quantity

                transaction.update(itemRef, "stock", newStock)
            }

            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener {
                onFailure(it.message ?: "Transaction failed")
            }
    }

    // =============================================
    // CLEAR CART
    // =============================================
    private fun clearCart() {
        cartMap.clear()
        totalAmount = 0.0
        cartContainer.removeAllViews()
        txtTotal.text = "Total: $0.00"
    }
}