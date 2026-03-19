package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

data class ModifierOptionDisplay(
    val id: String,
    val name: String,
    val price: Double,
    val isRemove: Boolean
)

class ModifierOptionAdapter(
    private val context: Context,
    private val optionList: List<ModifierOptionDisplay>,
    private val refresh: () -> Unit,
    private val groupId: String? = null,
    private val useEmbeddedOptions: Boolean = false
) : RecyclerView.Adapter<ModifierOptionAdapter.OptionViewHolder>() {

    private val db = FirebaseFirestore.getInstance()
    private var filteredList: List<ModifierOptionDisplay> = optionList

    inner class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtOptionName: TextView = view.findViewById(R.id.txtOptionName)
        val txtOptionPrice: TextView = view.findViewById(R.id.txtOptionPrice)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_modifier_option, parent, false)
        return OptionViewHolder(view)
    }

    override fun getItemCount(): Int = filteredList.size

    fun filter(query: String) {
        filteredList = if (query.isBlank()) {
            optionList
        } else {
            optionList.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
        val option = filteredList[position]

        holder.txtOptionName.text = option.name
        holder.txtOptionName.setTextColor(
            if (option.isRemove) 0xFFDC2626.toInt() else 0xFF1E293B.toInt()
        )

        if (option.isRemove) {
            holder.txtOptionPrice.text = ""
            holder.txtOptionPrice.visibility = View.GONE
        } else {
            holder.txtOptionPrice.text = "+$${String.format(Locale.US, "%.2f", option.price)}"
            holder.txtOptionPrice.visibility = View.VISIBLE
        }

        holder.btnEdit.setOnClickListener { showEditOptionDialog(option) }
        holder.btnDelete.setOnClickListener { confirmDeleteOption(option) }
    }

    private fun confirmDeleteOption(option: ModifierOptionDisplay) {
        AlertDialog.Builder(context)
            .setTitle("Delete ${option.name}?")
            .setMessage("This will permanently remove this option.")
            .setPositiveButton("Delete") { _, _ -> deleteOption(option) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteOption(option: ModifierOptionDisplay) {
        if (useEmbeddedOptions && groupId != null) {
            db.collection("ModifierGroups").document(groupId).get()
                .addOnSuccessListener { doc ->
                    @Suppress("UNCHECKED_CAST")
                    val rawOptions = doc.get("options") as? List<Map<String, Any>> ?: emptyList()
                    val updated = rawOptions.filter { (it["id"] as? String) != option.id }
                    db.collection("ModifierGroups").document(groupId)
                        .update("options", updated)
                        .addOnSuccessListener {
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                }
        } else {
            db.collection("ModifierOptions")
                .document(option.id)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    refresh()
                }
        }
    }

    private fun showEditOptionDialog(option: ModifierOptionDisplay) {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.setText(option.name)
        nameInput.hint = "Option name"

        val priceInput = EditText(context)
        priceInput.setText(option.price.toString())
        priceInput.hint = "Price adjustment"
        priceInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        if (!option.isRemove) {
            layout.addView(priceInput)
        }

        AlertDialog.Builder(context)
            .setTitle("Edit Option")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newPrice = if (option.isRemove) 0.0
                    else priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (newName.isNotEmpty()) {
                    if (useEmbeddedOptions && groupId != null) {
                        db.collection("ModifierGroups").document(groupId).get()
                            .addOnSuccessListener { doc ->
                                @Suppress("UNCHECKED_CAST")
                                val rawOptions = doc.get("options") as? List<Map<String, Any>> ?: emptyList()
                                val updated = rawOptions.map { opt ->
                                    if ((opt["id"] as? String) == option.id) {
                                        mutableMapOf<String, Any>(
                                            "id" to option.id,
                                            "name" to newName,
                                            "price" to newPrice
                                        )
                                    } else {
                                        opt
                                    }
                                }
                                db.collection("ModifierGroups").document(groupId)
                                    .update("options", updated)
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                                        refresh()
                                    }
                            }
                    } else {
                        db.collection("ModifierOptions")
                            .document(option.id)
                            .update(mapOf("name" to newName, "price" to newPrice))
                            .addOnSuccessListener {
                                Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
