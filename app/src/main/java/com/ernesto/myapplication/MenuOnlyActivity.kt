package com.ernesto.myapplication

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class MenuOnlyActivity : AppCompatActivity() {

    private lateinit var categoryRecycler: RecyclerView
    private lateinit var itemRecycler: RecyclerView
    private lateinit var editSearch: EditText
    private lateinit var txtItemCount: TextView

    private val db = FirebaseFirestore.getInstance()
    private var selectedCategoryId: String? = null
    private var selectedCategoryAvailability: List<String> = emptyList()
    private var stockCountingEnabled: Boolean = true
    private var currentAdapter: ItemAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_only)
        supportActionBar?.hide()

        categoryRecycler = findViewById(R.id.categoryRecycler)
        itemRecycler = findViewById(R.id.itemRecycler)
        editSearch = findViewById(R.id.editSearch)
        txtItemCount = findViewById(R.id.txtItemCount)

        categoryRecycler.layoutManager = LinearLayoutManager(this)
        itemRecycler.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btnAddCategory).setOnClickListener {
            showAddCategoryDialog()
        }

        findViewById<View>(R.id.btnAddItem).setOnClickListener {
            if (selectedCategoryId == null) {
                Toast.makeText(this, "Select a category first", Toast.LENGTH_SHORT).show()
            } else {
                showAddItemDialog()
            }
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentAdapter?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadStockSetting()
    }

    override fun onResume() {
        super.onResume()
        loadStockSetting()
    }

    private fun loadStockSetting() {
        db.collection("Settings").document("inventory").get()
            .addOnSuccessListener { doc ->
                stockCountingEnabled = doc.getBoolean("stockCountingEnabled") ?: true
                loadCategories()
            }
            .addOnFailureListener {
                stockCountingEnabled = true
                loadCategories()
            }
    }

    // =========================================================
    // LOAD CATEGORIES
    // =========================================================

    private fun loadCategories() {
        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->
                val categoryList = mutableListOf<CategoryModel>()

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val availableOrderTypes =
                        (doc.get("availableOrderTypes") as? List<String>) ?: emptyList()

                    categoryList.add(
                        CategoryModel(
                            id = doc.id,
                            name = name,
                            availableOrderTypes = availableOrderTypes
                        )
                    )
                }

                categoryList.sortBy { it.name.lowercase() }

                categoryRecycler.adapter =
                    CategoryAdapter(
                        categories = categoryList,
                        onCategoryClick = { categoryId ->
                            selectedCategoryId = categoryId
                            selectedCategoryAvailability =
                                categoryList.find { it.id == categoryId }?.availableOrderTypes
                                    ?: emptyList()
                            editSearch.setText("")
                            loadItems(categoryId)
                        },
                        context = this,
                        onDataChanged = {
                            loadCategories()
                        }
                    )

                if (selectedCategoryId != null) {
                    loadItems(selectedCategoryId!!)
                }
            }
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
                    @Suppress("UNCHECKED_CAST")
                    val availableOrderTypes =
                        doc.get("availableOrderTypes") as? List<String>

                    itemList.add(
                        ItemModel(
                            id = doc.id,
                            name = name,
                            price = price,
                            stock = stock,
                            availableOrderTypes = availableOrderTypes
                        )
                    )
                }

                itemList.sortBy { it.name.lowercase() }

                val count = itemList.size
                txtItemCount.text = "$count item${if (count != 1) "s" else ""}"

                val adapter = ItemAdapter(
                    context = this,
                    itemList = itemList,
                    categoryAvailability = selectedCategoryAvailability,
                    stockCountingEnabled = stockCountingEnabled,
                    refresh = { loadItems(categoryId) }
                )
                currentAdapter = adapter
                itemRecycler.adapter = adapter
            }
    }

    // =========================================================
    // ADD CATEGORY
    // =========================================================

    private fun showAddCategoryDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val nameLabel = TextView(this)
        nameLabel.text = "Category Name"
        nameLabel.textSize = 13f
        nameLabel.setTextColor(Color.DKGRAY)
        layout.addView(nameLabel)

        val input = EditText(this)
        input.hint = "Category name"
        layout.addView(input)

        val availLabel = TextView(this)
        availLabel.text = "Available In Order Types"
        availLabel.textSize = 13f
        availLabel.setTextColor(Color.DKGRAY)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 32
        availLabel.layoutParams = params
        layout.addView(availLabel)

        val checkBoxes = mutableMapOf<String, CheckBox>()
        for (orderType in CategoryAdapter.ALL_ORDER_TYPES) {
            val cb = CheckBox(this)
            cb.text = when (orderType) {
                "BAR_TAB" -> "BAR"
                "TO_GO" -> "TO GO"
                "DINE_IN" -> "DINE IN"
                else -> orderType
            }
            cb.isChecked = true
            layout.addView(cb)
            checkBoxes[orderType] = cb
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(layout)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false

            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {}
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
                val selectedTypes = checkBoxes.filter { it.value.isChecked }.keys.toList()
                saveCategory(name, selectedTypes)
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

    private fun saveCategory(name: String, availableOrderTypes: List<String>) {
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
                    Toast.makeText(this, "Category already exists", Toast.LENGTH_SHORT).show()
                } else {
                    val category = hashMapOf(
                        "name" to name,
                        "availableOrderTypes" to availableOrderTypes
                    )

                    db.collection("Categories")
                        .add(category)
                        .addOnSuccessListener {
                            loadCategories()
                            Toast.makeText(this, "Category added", Toast.LENGTH_SHORT).show()
                        }
                }
            }
    }

    // =========================================================
    // ADD ITEM
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

        val stockInput = EditText(this)
        stockInput.hint = "Stock"
        stockInput.inputType = InputType.TYPE_CLASS_NUMBER

        layout.addView(nameInput)
        layout.addView(priceInput)
        if (stockCountingEnabled) {
            layout.addView(stockInput)
        }

        val divider = android.view.View(this)
        divider.setBackgroundColor(Color.LTGRAY)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        ).apply { topMargin = 24; bottomMargin = 16 }
        layout.addView(divider)

        val useCategorySwitch = Switch(this)
        useCategorySwitch.text = "Use Category Availability"
        useCategorySwitch.isChecked = true
        layout.addView(useCategorySwitch)

        val checkBoxContainer = LinearLayout(this)
        checkBoxContainer.orientation = LinearLayout.VERTICAL
        checkBoxContainer.setPadding(0, 16, 0, 0)
        checkBoxContainer.visibility = android.view.View.GONE

        val availLabel = TextView(this)
        availLabel.text = "Custom Availability"
        availLabel.textSize = 13f
        availLabel.setTextColor(Color.DKGRAY)
        checkBoxContainer.addView(availLabel)

        val checkBoxes = mutableMapOf<String, CheckBox>()
        for (orderType in CategoryAdapter.ALL_ORDER_TYPES) {
            val cb = CheckBox(this)
            cb.text = when (orderType) {
                "BAR_TAB" -> "BAR"
                "TO_GO" -> "TO GO"
                "DINE_IN" -> "DINE IN"
                else -> orderType
            }
            cb.isChecked = true
            checkBoxContainer.addView(cb)
            checkBoxes[orderType] = cb
        }

        layout.addView(checkBoxContainer)

        useCategorySwitch.setOnCheckedChangeListener { _, isChecked ->
            checkBoxContainer.visibility =
                if (isChecked) android.view.View.GONE else android.view.View.VISIBLE
        }

        AlertDialog.Builder(this)
            .setTitle("Add Item")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull()
                val stock = if (stockCountingEnabled)
                    stockInput.text.toString().toLongOrNull() ?: 0L
                else
                    9999L

                if (name.isEmpty()) {
                    Toast.makeText(this, "Please enter item name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (price == null) {
                    Toast.makeText(this, "Please enter a valid price", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (selectedCategoryId == null) {
                    Toast.makeText(this, "No category selected", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val item = hashMapOf<String, Any>(
                    "name" to name,
                    "price" to price,
                    "stock" to stock,
                    "categoryId" to selectedCategoryId!!
                )

                if (!useCategorySwitch.isChecked) {
                    val selectedTypes =
                        checkBoxes.filter { it.value.isChecked }.keys.toList()
                    item["availableOrderTypes"] = selectedTypes
                }

                db.collection("MenuItems")
                    .add(item)
                    .addOnSuccessListener {
                        loadItems(selectedCategoryId!!)
                        Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
