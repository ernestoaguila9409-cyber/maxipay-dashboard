package com.ernesto.myapplication

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class MenuOnlyActivity : AppCompatActivity() {

    private lateinit var categoryRecycler: RecyclerView
    private lateinit var itemRecycler: RecyclerView

    private val db = FirebaseFirestore.getInstance()
    private var selectedCategoryId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_only)

        categoryRecycler = findViewById(R.id.categoryRecycler)
        itemRecycler = findViewById(R.id.itemRecycler)

        categoryRecycler.layoutManager = LinearLayoutManager(this)
        itemRecycler.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnAddCategory).setOnClickListener {
            showAddCategoryDialog()
        }

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
    // LOAD CATEGORIES
    // =========================================================

    private fun loadCategories() {

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->

                val categoryList = mutableListOf<Pair<String, String>>() // id, name

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    categoryList.add(Pair(doc.id, name))
                }

                categoryRecycler.adapter =
                    CategoryAdapter(
                        categories = categoryList,
                        onCategoryClick = { categoryId ->
                            selectedCategoryId = categoryId
                            loadItems(categoryId)
                        },
                        context = this,
                        onDataChanged = {
                            loadCategories()
                        }
                    )}
    }

    // =========================================================
    // LOAD ITEMS
    // =========================================================

    private fun loadItems(categoryId: String) {

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                val itemList = mutableListOf<ItemModel>()

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0
                    val stock = doc.getLong("stock") ?: 0L

                    itemList.add(
                        ItemModel(
                            id = doc.id,
                            name = name,
                            price = price,
                            stock = stock
                        )
                    )
                }

                itemRecycler.adapter = ItemAdapter(this, itemList) {
                    loadItems(categoryId)
                }
            }
    }

    // =========================================================
    // ADD CATEGORY
    // =========================================================

    private fun showAddCategoryDialog() {

        val input = EditText(this)
        input.hint = "Category name"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(input)
            .setPositiveButton("Add", null) // we override later
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {

            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false

            input.addTextChangedListener(object : android.text.TextWatcher {

                override fun afterTextChanged(s: android.text.Editable?) {}

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {

                    val name = text.toString().trim()

                    if (name.isEmpty()) {
                        input.error = null
                        addButton.isEnabled = false
                        return
                    }

                    checkCategoryExists(name) { exists ->

                        if (exists) {
                            input.error = "Category already exists"
                            addButton.isEnabled = false
                        } else {
                            input.error = null
                            addButton.isEnabled = true
                        }
                    }
                }
            })

            addButton.setOnClickListener {
                val name = input.text.toString().trim()
                saveCategory(name)
                dialog.dismiss()
            }
        }

        dialog.show()
    }
    private fun checkCategoryExists(name: String, callback: (Boolean) -> Unit) {

        val normalizedName = name.trim().lowercase()

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->

                var exists = false

                for (doc in documents) {
                    val existingName = doc.getString("name") ?: continue
                    if (existingName.trim().lowercase() == normalizedName) {
                        exists = true
                        break
                    }
                }

                callback(exists)
            }
    }
    private fun saveCategory(name: String) {

        val normalizedName = name.trim().lowercase()

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->

                var exists = false

                for (doc in documents) {
                    val existingName = doc.getString("name") ?: continue
                    if (existingName.trim().lowercase() == normalizedName) {
                        exists = true
                        break
                    }
                }

                if (exists) {
                    Toast.makeText(
                        this,
                        "Category already exists",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {

                    val category = hashMapOf("name" to name)

                    db.collection("Categories")
                        .add(category)
                        .addOnSuccessListener {
                            loadCategories()
                            Toast.makeText(
                                this,
                                "Category added",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }
    }

    // =========================================================
    // ADD ITEM
    // =========================================================

    private fun showAddItemDialog() {

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
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
}