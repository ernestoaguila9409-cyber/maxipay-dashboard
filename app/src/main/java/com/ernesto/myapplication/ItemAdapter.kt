package com.ernesto.myapplication

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class ItemAdapter(
    private val context: Context,
    private val itemList: List<ItemModel>,
    private val categoryAvailabilityMap: Map<String, List<String>> = emptyMap(),
    private val stockCountingEnabled: Boolean = true,
    private val subcategories: List<SubcategoryModel> = emptyList(),
    private val refresh: () -> Unit
) : RecyclerView.Adapter<ItemAdapter.ViewHolder>() {

    private fun getCategoryAvailability(categoryId: String): List<String> =
        categoryAvailabilityMap[categoryId] ?: emptyList()

    private val db = FirebaseFirestore.getInstance()
    private var filteredList: List<ItemModel> = itemList

    private val orderTypeShortLabels = mapOf(
        "BAR_TAB" to "BAR",
        "TO_GO" to "TO GO",
        "DINE_IN" to "DINE IN"
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtItemName: TextView = view.findViewById(R.id.txtItemName)
        val txtItemPrice: TextView = view.findViewById(R.id.txtItemPrice)
        val txtItemStock: TextView = view.findViewById(R.id.txtItemStock)
        val txtStockStatus: TextView = view.findViewById(R.id.txtStockStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = filteredList.size

    fun filter(query: String) {
        filteredList = if (query.isBlank()) {
            itemList
        } else {
            itemList.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredList[position]

        holder.txtItemName.text = item.name
        holder.txtItemPrice.text = "$${String.format("%.2f", item.getPrice("pos"))}"

        if (stockCountingEnabled) {
            holder.txtItemStock.visibility = View.VISIBLE
            holder.txtStockStatus.visibility = View.VISIBLE

            holder.txtItemStock.text = item.stock.toString()

            when {
                item.stock <= 0 -> {
                    holder.txtStockStatus.text = "Out of stock"
                    holder.txtStockStatus.setTextColor(0xFFDC2626.toInt())
                    holder.txtStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_red)
                }
                item.stock <= 10 -> {
                    holder.txtStockStatus.text = "Low stock"
                    holder.txtStockStatus.setTextColor(0xFFA16207.toInt())
                    holder.txtStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_yellow)
                }
                else -> {
                    holder.txtStockStatus.text = "In stock"
                    holder.txtStockStatus.setTextColor(0xFF16A34A.toInt())
                    holder.txtStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_green)
                }
            }
        } else {
            holder.txtItemStock.visibility = View.GONE
            holder.txtStockStatus.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            showOptionsDialog(item)
        }
    }

    // =========================================================
    // OPTIONS DIALOG
    // =========================================================

    private fun showOptionsDialog(item: ItemModel) {
        val options = arrayOf("Edit", "Delete", "Assign Modifiers", "Assign Taxes")

        AlertDialog.Builder(context)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(item)
                    1 -> confirmDelete(item)
                    2 -> {
                        val intent = Intent(context, AssignModifierToItemActivity::class.java)
                        intent.putExtra("ITEM_ID", item.id)
                        intent.putExtra("ITEM_NAME", item.name)
                        context.startActivity(intent)
                    }
                    3 -> showAssignTaxesDialog(item)
                }
            }
            .show()
    }

    // =========================================================
    // DELETE ITEM (with confirmation)
    // =========================================================

    private fun confirmDelete(item: ItemModel) {
        AlertDialog.Builder(context)
            .setTitle("Delete ${item.name}?")
            .setMessage("This will permanently remove this item.")
            .setPositiveButton("Delete") { _, _ -> deleteItem(item.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteItem(itemId: String) {
        db.collection("MenuItems")
            .document(itemId)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                refresh()
            }
    }

    // =========================================================
    // EDIT ITEM
    // =========================================================

    private fun showEditDialog(item: ItemModel) {
        db.collection("menus").get()
            .addOnSuccessListener { menuDocs ->
                val menus = menuDocs.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val isActive = doc.getBoolean("isActive") ?: true
                    if (!isActive) return@mapNotNull null
                    Pair(doc.id, name)
                }.filter { (menuId, _) ->
                    item.prices.containsKey(menuId)
                }.sortedBy { it.second }
                buildEditDialog(item, menus)
            }
            .addOnFailureListener { buildEditDialog(item, emptyList()) }
    }

    private fun buildEditDialog(item: ItemModel, menus: List<Pair<String, String>>) {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        fun createLabel(text: String): TextView {
            val label = TextView(context)
            label.text = text
            label.textSize = 13f
            label.setTextColor(Color.DKGRAY)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 8
            label.layoutParams = lp
            return label
        }

        val nameInput = EditText(context)
        nameInput.setText(item.name)

        layout.addView(createLabel("Item Name"))
        layout.addView(nameInput)

        val priceInputs = mutableMapOf<String, EditText>()
        if (menus.isEmpty()) {
            layout.addView(createLabel("Price"))
            val input = EditText(context)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val currentPrice = item.prices["default"] ?: item.price
            input.setText(currentPrice.toString())
            layout.addView(input)
            priceInputs["default"] = input
        } else {
            layout.addView(createLabel("Prices per Menu"))
            for ((menuId, menuName) in menus) {
                layout.addView(createLabel("$menuName Price"))
                val input = EditText(context)
                input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val existing = item.prices[menuId]
                if (existing != null) input.setText(existing.toString())
                layout.addView(input)
                priceInputs[menuId] = input
            }
        }

        val stockInput = EditText(context)
        stockInput.inputType = InputType.TYPE_CLASS_NUMBER
        stockInput.setText(item.stock.toString())

        if (stockCountingEnabled) {
            layout.addView(createLabel("Stock"))
            layout.addView(stockInput)
        }

        val itemSubs = subcategories.filter { it.categoryId == item.categoryId }
        var selectedSubId = item.subcategoryId
        if (itemSubs.isNotEmpty()) {
            layout.addView(createLabel("Subcategory"))
            val subSpinner = android.widget.Spinner(context)
            val subNames = listOf("None") + itemSubs.map { it.name }
            subSpinner.adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, subNames)
            val currentIdx = itemSubs.indexOfFirst { it.id == item.subcategoryId }
            subSpinner.setSelection(if (currentIdx >= 0) currentIdx + 1 else 0)
            subSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    selectedSubId = if (pos == 0) "" else itemSubs[pos - 1].id
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            subSpinner.setOnTouchListener { _, _ ->
                InventoryPriceKeypad.clearPriceFocusForSpinner(priceInputs.values)
                false
            }
            layout.addView(subSpinner)
        }

        val divider = View(context)
        divider.setBackgroundColor(Color.LTGRAY)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        ).apply { topMargin = 24; bottomMargin = 16 }
        layout.addView(divider)

        val useCategorySwitch = Switch(context)
        useCategorySwitch.text = "Use Category Availability"
        useCategorySwitch.isChecked = item.availableOrderTypes == null
        layout.addView(useCategorySwitch)

        val checkBoxContainer = LinearLayout(context)
        checkBoxContainer.orientation = LinearLayout.VERTICAL
        checkBoxContainer.setPadding(0, 16, 0, 0)
        checkBoxContainer.visibility =
            if (item.availableOrderTypes == null) View.GONE else View.VISIBLE

        val availLabel = createLabel("Custom Availability")
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelParams.bottomMargin = 8
        availLabel.layoutParams = labelParams
        checkBoxContainer.addView(availLabel)

        val checkBoxes = mutableMapOf<String, CheckBox>()
        for (orderType in CategoryAdapter.ALL_ORDER_TYPES) {
            val cb = CheckBox(context)
            cb.text = orderTypeShortLabels[orderType] ?: orderType
            cb.isChecked = if (item.availableOrderTypes != null) {
                item.availableOrderTypes.contains(orderType)
            } else {
                getCategoryAvailability(item.categoryId).contains(orderType)
            }
            checkBoxContainer.addView(cb)
            checkBoxes[orderType] = cb
            cb.setOnTouchListener { _, _ ->
                InventoryPriceKeypad.clearPriceFocusForSpinner(priceInputs.values)
                false
            }
        }

        layout.addView(checkBoxContainer)

        useCategorySwitch.setOnCheckedChangeListener { _, isChecked ->
            checkBoxContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
            InventoryPriceKeypad.clearPriceFocusForSpinner(priceInputs.values)
        }

        val dialogRoot = InventoryPriceKeypad.wrapFormWithDecimalKeypad(
            context,
            layout,
            priceInputs.values
        )

        AlertDialog.Builder(context)
            .setTitle("Edit Item")
            .setView(dialogRoot)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newPrices = mutableMapOf<String, Double>()
                for ((key, input) in priceInputs) {
                    val v = input.text.toString().toDoubleOrNull()
                    if (v != null && v >= 0) newPrices[key] = v
                }
                val newStock = if (stockCountingEnabled)
                    stockInput.text.toString().toLongOrNull()
                else
                    item.stock

                if (newName.isNotEmpty() && newPrices.isNotEmpty() && newStock != null) {
                    val defaultPrice = newPrices.values.first()
                    val updates = mutableMapOf<String, Any>(
                        "name" to newName,
                        "prices" to newPrices,
                        "price" to defaultPrice
                    )
                    if (stockCountingEnabled) {
                        updates["stock"] = newStock
                    }

                    if (useCategorySwitch.isChecked) {
                        updates["availableOrderTypes"] =
                            com.google.firebase.firestore.FieldValue.delete()
                    } else {
                        updates["availableOrderTypes"] =
                            checkBoxes.filter { it.value.isChecked }.keys.toList()
                    }

                    updates["subcategoryId"] = selectedSubId

                    db.collection("MenuItems")
                        .document(item.id)
                        .update(updates)
                        .addOnSuccessListener {
                            Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================
    // ASSIGN TAXES
    // =========================================================

    private fun showAssignTaxesDialog(item: ItemModel) {
        db.collection("MenuItems").document(item.id).get()
            .addOnSuccessListener { itemDoc ->
                @Suppress("UNCHECKED_CAST")
                val existingTaxIds = (itemDoc.get("taxIds") as? List<String>) ?: emptyList()
                loadTaxesAndShowDialog(item, existingTaxIds)
            }
            .addOnFailureListener {
                loadTaxesAndShowDialog(item, emptyList())
            }
    }

    private fun loadTaxesAndShowDialog(item: ItemModel, existingTaxIds: List<String>) {
        db.collection("Taxes").get()
            .addOnSuccessListener { snap ->
                val taxes = snap.documents.mapNotNull { doc ->
                    val type = doc.getString("type") ?: return@mapNotNull null
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val amount = (doc.getDouble("amount")
                        ?: doc.getLong("amount")?.toDouble()) ?: return@mapNotNull null
                    TaxItem(id = doc.id, type = type, name = name, amount = amount)
                }

                if (taxes.isEmpty()) {
                    Toast.makeText(context, "No taxes configured", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val selectedIds = existingTaxIds.toMutableSet()

                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 40, 50, 10)
                }

                val subtitle = TextView(context).apply {
                    text = "Select taxes to apply to this item"
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, 24)
                }
                layout.addView(subtitle)

                for (tax in taxes) {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, 12, 0, 12)
                    }

                    val cb = CheckBox(context).apply {
                        isChecked = selectedIds.contains(tax.id)
                    }

                    val info = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(16, 0, 0, 0)
                    }

                    val nameView = TextView(context).apply {
                        text = tax.name
                        textSize = 15f
                        setTextColor(Color.parseColor("#1A1A1A"))
                    }

                    val detailView = TextView(context).apply {
                        text = if (tax.type == "FIXED") {
                            String.format(java.util.Locale.US, "Fixed · $%.2f", tax.amount)
                        } else {
                            String.format(java.util.Locale.US, "Percentage · %.1f%%", tax.amount)
                        }
                        textSize = 13f
                        setTextColor(Color.parseColor("#6A4FB3"))
                    }

                    info.addView(nameView)
                    info.addView(detailView)

                    row.addView(cb)
                    row.addView(info)

                    row.setOnClickListener { cb.isChecked = !cb.isChecked }

                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedIds.add(tax.id) else selectedIds.remove(tax.id)
                    }

                    layout.addView(row)

                    val divider = View(context).apply {
                        setBackgroundColor(Color.parseColor("#E0E0E0"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                    }
                    layout.addView(divider)
                }

                AlertDialog.Builder(context)
                    .setTitle("Assign Taxes — ${item.name}")
                    .setView(layout)
                    .setPositiveButton("Save") { _, _ ->
                        saveTaxAssignment(item.id, selectedIds)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to load taxes", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveTaxAssignment(itemId: String, selectedIds: Set<String>) {
        val updates: Map<String, Any> = if (selectedIds.isEmpty()) {
            mapOf(
                "taxIds" to com.google.firebase.firestore.FieldValue.delete(),
                "taxMode" to "INHERIT"
            )
        } else {
            mapOf(
                "taxIds" to selectedIds.toList(),
                "taxMode" to "FORCE_APPLY"
            )
        }

        db.collection("MenuItems").document(itemId)
            .update(updates)
            .addOnSuccessListener {
                val msg = if (selectedIds.isEmpty()) "Taxes cleared (inherit)" else "Taxes assigned"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
