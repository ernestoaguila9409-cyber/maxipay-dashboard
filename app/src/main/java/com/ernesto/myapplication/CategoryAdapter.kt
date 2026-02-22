package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class CategoryAdapter(
    private val categories: List<Pair<String, String>>, // id, name
    private val onCategoryClick: (String) -> Unit,
    private val context: Context,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private val db = FirebaseFirestore.getInstance()

    inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtCategoryName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {

        val (id, name) = categories[position]

        holder.nameText.text = name

        holder.itemView.setOnClickListener {
            onCategoryClick(id)
        }

        holder.itemView.setOnLongClickListener {
            showCategoryOptions(id, name)
            true
        }
    }

    override fun getItemCount(): Int = categories.size

    // =========================================================
    // CATEGORY OPTIONS
    // =========================================================

    private fun showCategoryOptions(categoryId: String, categoryName: String) {

        val options = arrayOf("Edit", "Delete")

        AlertDialog.Builder(context)
            .setTitle(categoryName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(categoryId, categoryName)
                    1 -> deleteCategory(categoryId)
                }
            }
            .show()
    }

    private fun showEditDialog(categoryId: String, oldName: String) {

        val input = TextView(context)
        val editText = android.widget.EditText(context)
        editText.setText(oldName)

        AlertDialog.Builder(context)
            .setTitle("Edit Category")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    db.collection("Categories")
                        .document(categoryId)
                        .update("name", newName)
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