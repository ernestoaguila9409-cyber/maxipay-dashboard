package com.ernesto.myapplication

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class MenuOnlyActivity : AppCompatActivity() {

    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: LinearLayout

    private var selectedCategoryId: String? = null

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_only)

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)

        // ➕ Add Category
        findViewById<ImageButton>(R.id.btnAddCategory).setOnClickListener {
            showAddCategoryDialog()
        }

        // ➕ Add Item
        findViewById<ImageButton>(R.id.btnAddItem).setOnClickListener {

            if (selectedCategoryId == null) {
                Toast.makeText(this, "Select a category first", Toast.LENGTH_SHORT).show()
            } else {
                showAddItemDialog()
            }
        }

        loadCategories()
    }

    // =========================================================
    // CATEGORY SECTION
    // =========================================================

    private fun showAddCategoryDialog() {

        val input = EditText(this)
        input.hint = "Category name"

        AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveCategory(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCategory(name: String) {

        val category = hashMapOf(
            "name" to name
        )

        db.collection("Categories")
            .add(category)
            .addOnSuccessListener {
                loadCategories()
            }
    }

    private fun loadCategories() {

        categoryContainer.removeAllViews()

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val categoryId = doc.id
                    addCategoryView(name, categoryId)
                }
            }
    }

    private fun addCategoryView(name: String, categoryId: String) {

        val textView = TextView(this)
        textView.text = name
        textView.setPadding(40, 20, 40, 20)
        textView.setBackgroundColor(Color.parseColor("#6A4FB3"))
        textView.setTextColor(Color.WHITE)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 20, 0)
        textView.layoutParams = params

        textView.setOnClickListener {

            selectedCategoryId = categoryId
            highlightSelectedCategory(textView)
            loadItems(categoryId)
        }

        categoryContainer.addView(textView)
    }

    private fun highlightSelectedCategory(selectedView: TextView) {

        for (i in 0 until categoryContainer.childCount) {
            val child = categoryContainer.getChildAt(i) as TextView
            child.setBackgroundColor(Color.parseColor("#6A4FB3"))
        }

        selectedView.setBackgroundColor(Color.parseColor("#4CAF50"))
    }

    // =========================================================
    // ITEMS SECTION
    // =========================================================

    private fun showAddItemDialog() {

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val nameInput = EditText(this)
        nameInput.hint = "Item name"

        val priceInput = EditText(this)
        priceInput.hint = "Price"
        priceInput.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        layout.addView(priceInput)

        AlertDialog.Builder(this)
            .setTitle("Add Item")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->

                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull()

                if (name.isNotEmpty() && price != null && selectedCategoryId != null) {

                    val item = hashMapOf(
                        "name" to name,
                        "price" to price,
                        "categoryId" to selectedCategoryId
                    )

                    db.collection("MenuItems")
                        .add(item)
                        .addOnSuccessListener {
                            loadItems(selectedCategoryId!!)
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadItems(categoryId: String) {

        itemContainer.removeAllViews()

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                if (documents.isEmpty) {
                    showNoItems()
                    return@addOnSuccessListener
                }

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0
                    addItemView(name, price)
                }
            }
    }

    private fun addItemView(name: String, price: Double) {

        val textView = TextView(this)
        textView.text = "$name - $${String.format("%.2f", price)}"
        textView.textSize = 16f
        textView.setPadding(40, 40, 40, 40)
        textView.setBackgroundColor(Color.WHITE)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 30)
        textView.layoutParams = params

        itemContainer.addView(textView)
    }

    private fun showNoItems() {

        val textView = TextView(this)
        textView.text = "No items yet"
        textView.setPadding(40, 40, 40, 40)
        textView.setBackgroundColor(Color.LTGRAY)

        itemContainer.addView(textView)
    }
}