package com.ernesto.myapplication

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class ModifierGroupExpandableAdapter(
    private val groups: MutableList<ModifierGroupModel>
) : RecyclerView.Adapter<ModifierGroupExpandableAdapter.GroupViewHolder>() {

    private val db = FirebaseFirestore.getInstance()

    inner class GroupViewHolder(val layout: LinearLayout) :
        RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val layout = LinearLayout(parent.context)
        layout.orientation = LinearLayout.VERTICAL
        return GroupViewHolder(layout)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {

        val group = groups[position]
        holder.layout.removeAllViews()

        // =========================
        // GROUP TITLE
        // =========================
        val title = TextView(holder.layout.context)
        title.text = group.name
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setPadding(40, 40, 40, 40)

        holder.layout.addView(title)

        // Expand on long press
        // NORMAL TAP → Expand
        title.setOnClickListener {
            group.isExpanded = !group.isExpanded
            notifyItemChanged(position)
        }

// LONG PRESS → Edit/Delete
        title.setOnLongClickListener {
            showGroupOptionsDialog(holder.layout.context, group)
            true
        }

        // =========================
        // IF EXPANDED → LOAD OPTIONS
        // =========================
        if (group.isExpanded) {

            loadOptions(group, holder)

            val addOption = TextView(holder.layout.context)
            addOption.text = "+ Add Option"
            addOption.setPadding(80, 30, 40, 30)
            addOption.setTextColor(0xFF6A4FB3.toInt())

            holder.layout.addView(addOption)

            addOption.setOnClickListener {
                showAddOptionDialog(holder.layout.context, group)
            }
        }
    }

    // =====================================================
    // GROUP EDIT / DELETE
    // =====================================================
    private fun showGroupOptionsDialog(
        context: android.content.Context,
        group: ModifierGroupModel
    ) {

        val options = arrayOf("Edit Group", "Delete Group")

        AlertDialog.Builder(context)
            .setTitle(group.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditGroupDialog(context, group)
                    1 -> deleteGroup(group)
                }
            }
            .show()
    }

    private fun showEditGroupDialog(
        context: android.content.Context,
        group: ModifierGroupModel
    ) {

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.setText(group.name)

        val requiredCheckbox = android.widget.CheckBox(context)
        requiredCheckbox.text = "Required"
        requiredCheckbox.isChecked = group.required

        val maxSelectionInput = EditText(context)
        maxSelectionInput.setText(group.maxSelection.toString())
        maxSelectionInput.inputType =
            InputType.TYPE_CLASS_NUMBER

        layout.addView(nameInput)
        layout.addView(requiredCheckbox)
        layout.addView(maxSelectionInput)

        AlertDialog.Builder(context)
            .setTitle("Edit Modifier Group")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val newName = nameInput.text.toString().trim()
                val newRequired = requiredCheckbox.isChecked
                val newMax =
                    maxSelectionInput.text.toString().toIntOrNull() ?: 1

                if (newName.isNotEmpty()) {

                    val updates = mapOf(
                        "name" to newName,
                        "required" to newRequired,
                        "maxSelection" to newMax
                    )

                    db.collection("ModifierGroups")
                        .document(group.id)
                        .update(updates)
                        .addOnSuccessListener {

                            group.name = newName
                            group.isExpanded = false

                            notifyDataSetChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGroup(group: ModifierGroupModel) {

        db.collection("ModifierGroups")
            .document(group.id)
            .delete()
            .addOnSuccessListener {
                groups.remove(group)
                notifyDataSetChanged()
            }
    }

    // =====================================================
    // LOAD OPTIONS
    // =====================================================
    private fun loadOptions(group: ModifierGroupModel, holder: GroupViewHolder) {

        db.collection("ModifierOptions")
            .whereEqualTo("groupId", group.id)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0

                    val optionText = TextView(holder.layout.context)
                    optionText.text =
                        "• $name   +$${String.format(Locale.US, "%.2f", price)}"
                    optionText.setPadding(80, 20, 40, 20)

                    holder.layout.addView(optionText)

                    optionText.setOnClickListener {
                        showEditOptionDialog(
                            holder.layout.context,
                            doc.id,
                            name,
                            price
                        )
                    }
                }
            }
    }

    // =====================================================
    // ADD OPTION
    // =====================================================
    private fun showAddOptionDialog(
        context: android.content.Context,
        group: ModifierGroupModel
    ) {

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.hint = "Option Name (ex: Small)"

        val priceInput = EditText(context)
        priceInput.hint = "Price Adjustment"
        priceInput.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        layout.addView(priceInput)

        AlertDialog.Builder(context)
            .setTitle("Add Option")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (name.isNotEmpty()) {

                    val option = hashMapOf(
                        "name" to name,
                        "price" to price,
                        "groupId" to group.id
                    )

                    db.collection("ModifierOptions")
                        .add(option)
                        .addOnSuccessListener {
                            notifyDataSetChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =====================================================
    // EDIT / DELETE OPTION
    // =====================================================
    private fun showEditOptionDialog(
        context: android.content.Context,
        optionId: String,
        currentName: String,
        currentPrice: Double
    ) {

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.setText(currentName)

        val priceInput = EditText(context)
        priceInput.setText(currentPrice.toString())
        priceInput.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        layout.addView(priceInput)

        AlertDialog.Builder(context)
            .setTitle("Edit Option")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val newName = nameInput.text.toString().trim()
                val newPrice =
                    priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (newName.isNotEmpty()) {

                    val updates = mapOf(
                        "name" to newName,
                        "price" to newPrice
                    )

                    db.collection("ModifierOptions")
                        .document(optionId)
                        .update(updates)
                        .addOnSuccessListener {
                            notifyDataSetChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->

                db.collection("ModifierOptions")
                    .document(optionId)
                    .delete()
                    .addOnSuccessListener {
                        notifyDataSetChanged()
                    }
            }
            .show()
    }
}