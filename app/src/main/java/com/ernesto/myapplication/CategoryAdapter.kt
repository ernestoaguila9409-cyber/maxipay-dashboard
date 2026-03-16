package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class CategoryAdapter(
    private val categories: List<CategoryModel>,
    private val onCategoryClick: (String) -> Unit,
    private val context: Context,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private val db = FirebaseFirestore.getInstance()
    private var selectedPosition: Int = RecyclerView.NO_POSITION

    companion object {
        val ALL_ORDER_TYPES = listOf("BAR_TAB", "TO_GO", "DINE_IN")

        private val ORDER_TYPE_SHORT_LABELS = mapOf(
            "BAR_TAB" to "BAR",
            "TO_GO" to "TO GO",
            "DINE_IN" to "DINE IN"
        )
    }

    inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtCategoryName)
        val indicator: View = view.findViewById(R.id.selectedIndicator)
        val root: View = view.findViewById(R.id.categoryRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val category = categories[position]
        val isSelected = position == selectedPosition

        holder.nameText.text = category.name
        holder.nameText.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        holder.nameText.setTextColor(
            if (isSelected) 0xFF6366F1.toInt() else 0xFF334155.toInt()
        )

        holder.indicator.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.root.setBackgroundResource(
            if (isSelected) R.drawable.bg_category_selected else R.drawable.bg_category_default
        )

        holder.itemView.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = holder.adapterPosition
            if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onCategoryClick(category.id)
        }

        holder.itemView.setOnLongClickListener {
            showCategoryOptions(category)
            true
        }
    }

    override fun getItemCount(): Int = categories.size

    private fun showCategoryOptions(category: CategoryModel) {
        val options = arrayOf("Edit", "Delete")

        AlertDialog.Builder(context)
            .setTitle(category.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(category)
                    1 -> deleteCategory(category.id)
                }
            }
            .show()
    }

    private fun showEditDialog(category: CategoryModel) {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val editText = android.widget.EditText(context)
        editText.setText(category.name)

        val nameLabel = TextView(context)
        nameLabel.text = "Category Name"
        nameLabel.textSize = 13f
        nameLabel.setTextColor(android.graphics.Color.DKGRAY)

        layout.addView(nameLabel)
        layout.addView(editText)

        val availLabel = TextView(context)
        availLabel.text = "Available In Order Types"
        availLabel.textSize = 13f
        availLabel.setTextColor(android.graphics.Color.DKGRAY)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 32
        availLabel.layoutParams = params
        layout.addView(availLabel)

        val checkBoxes = mutableMapOf<String, CheckBox>()
        for (orderType in ALL_ORDER_TYPES) {
            val cb = CheckBox(context)
            cb.text = ORDER_TYPE_SHORT_LABELS[orderType] ?: orderType
            cb.isChecked = category.availableOrderTypes.isEmpty() || category.availableOrderTypes.contains(orderType)
            layout.addView(cb)
            checkBoxes[orderType] = cb
        }

        AlertDialog.Builder(context)
            .setTitle("Edit Category")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val selectedTypes = checkBoxes.filter { it.value.isChecked }.keys.toList()
                    db.collection("Categories")
                        .document(category.id)
                        .update(
                            mapOf(
                                "name" to newName,
                                "availableOrderTypes" to selectedTypes
                            )
                        )
                        .addOnSuccessListener {
                            Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                            onDataChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCategory(categoryId: String) {
        db.collection("Categories")
            .document(categoryId)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                onDataChanged()
            }
    }
}
