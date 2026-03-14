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
    private val categoryAvailability: List<String> = emptyList(),
    private val refresh: () -> Unit
) : RecyclerView.Adapter<ItemAdapter.ViewHolder>() {

    private val db = FirebaseFirestore.getInstance()

    private val orderTypeShortLabels = mapOf(
        "BAR_TAB" to "BAR",
        "TO_GO" to "TO GO",
        "DINE_IN" to "DINE IN"
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtItemName: TextView = view.findViewById(R.id.txtItemName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = itemList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item = itemList[position]

        holder.txtItemName.text =
            "${item.name}  |  $${String.format("%.2f", item.price)}  |  Stock: ${item.stock}"

        when {
            item.stock <= 0 -> {
                holder.itemView.setBackgroundColor(Color.parseColor("#FFCDD2"))
            }
            item.stock <= 5 -> {
                holder.itemView.setBackgroundColor(Color.parseColor("#FFE0B2"))
            }
            else -> {
                holder.itemView.setBackgroundColor(Color.WHITE)
            }
        }

        holder.itemView.setOnClickListener {
            showOptionsDialog(item)
        }
    }

    // =========================================================
    // OPTIONS DIALOG
    // =========================================================

    private fun showOptionsDialog(item: ItemModel) {

        val options = arrayOf("Edit", "Delete", "Assign Modifiers")

        AlertDialog.Builder(context)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {

                    0 -> showEditDialog(item)

                    1 -> deleteItem(item.id)

                    2 -> {
                        val intent = Intent(context, AssignModifierToItemActivity::class.java)
                        intent.putExtra("ITEM_ID", item.id)
                        intent.putExtra("ITEM_NAME", item.name)
                        context.startActivity(intent)
                    }
                }
            }
            .show()
    }

    // =========================================================
    // EDIT ITEM
    // =========================================================

    private fun showEditDialog(item: ItemModel) {

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        fun createLabel(text: String): TextView {
            val label = TextView(context)
            label.text = text
            label.textSize = 13f
            label.setTextColor(Color.DKGRAY)
            return label
        }

        val nameInput = EditText(context)
        nameInput.setText(item.name)

        val priceInput = EditText(context)
        priceInput.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        priceInput.setText(item.price.toString())

        val stockInput = EditText(context)
        stockInput.inputType = InputType.TYPE_CLASS_NUMBER
        stockInput.setText(item.stock.toString())

        layout.addView(createLabel("Item Name"))
        layout.addView(nameInput)

        layout.addView(createLabel("Price"))
        layout.addView(priceInput)

        layout.addView(createLabel("Stock"))
        layout.addView(stockInput)

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
                categoryAvailability.contains(orderType)
            }
            checkBoxContainer.addView(cb)
            checkBoxes[orderType] = cb
        }

        layout.addView(checkBoxContainer)

        useCategorySwitch.setOnCheckedChangeListener { _, isChecked ->
            checkBoxContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        AlertDialog.Builder(context)
            .setTitle("Edit Item")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val newName = nameInput.text.toString().trim()
                val newPrice = priceInput.text.toString().toDoubleOrNull()
                val newStock = stockInput.text.toString().toLongOrNull()

                if (newName.isNotEmpty() && newPrice != null && newStock != null) {

                    val updates = mutableMapOf<String, Any>(
                        "name" to newName,
                        "price" to newPrice,
                        "stock" to newStock
                    )

                    if (useCategorySwitch.isChecked) {
                        updates["availableOrderTypes"] =
                            com.google.firebase.firestore.FieldValue.delete()
                    } else {
                        updates["availableOrderTypes"] =
                            checkBoxes.filter { it.value.isChecked }.keys.toList()
                    }

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
    // DELETE ITEM
    // =========================================================

    private fun deleteItem(itemId: String) {

        db.collection("MenuItems")
            .document(itemId)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                refresh()
            }
    }
}
