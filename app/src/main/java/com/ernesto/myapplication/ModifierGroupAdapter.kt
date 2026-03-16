package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class ModifierGroupAdapter(
    private val groups: List<ModifierGroupModel>,
    private val onGroupClick: (ModifierGroupModel) -> Unit,
    private val context: Context,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<ModifierGroupAdapter.GroupViewHolder>() {

    private val db = FirebaseFirestore.getInstance()
    var selectedGroupId: String? = null
        set(value) {
            val oldPos = groups.indexOfFirst { it.id == field }.takeIf { it >= 0 }
            val newPos = groups.indexOfFirst { it.id == value }.takeIf { it >= 0 }
            field = value
            if (oldPos != null) notifyItemChanged(oldPos)
            if (newPos != null) notifyItemChanged(newPos)
        }

    inner class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtGroupName: TextView = view.findViewById(R.id.txtGroupName)
        val txtGroupType: TextView = view.findViewById(R.id.txtGroupType)
        val indicator: View = view.findViewById(R.id.selectedIndicator)
        val root: View = view.findViewById(R.id.groupRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_modifier_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val isSelected = group.id == selectedGroupId

        holder.txtGroupName.text = group.name
        holder.txtGroupName.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        holder.txtGroupName.setTextColor(
            if (isSelected) 0xFF6366F1.toInt() else 0xFF334155.toInt()
        )

        holder.txtGroupType.text = when (group.groupType) {
            "REMOVE" -> "Remove ingredients"
            else -> "Add-ons • max ${group.maxSelection}"
        }
        holder.txtGroupType.setTextColor(
            if (group.groupType == "REMOVE") 0xFFDC2626.toInt() else 0xFF94A3B8.toInt()
        )

        holder.indicator.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.root.setBackgroundResource(
            if (isSelected) R.drawable.bg_category_selected else R.drawable.bg_category_default
        )

        holder.itemView.setOnClickListener {
            onGroupClick(group)
        }

        holder.itemView.setOnLongClickListener {
            showGroupOptions(group)
            true
        }
    }

    override fun getItemCount(): Int = groups.size

    private fun showGroupOptions(group: ModifierGroupModel) {
        val options = arrayOf("Edit Group", "Delete Group")

        AlertDialog.Builder(context)
            .setTitle(group.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditGroupDialog(group)
                    1 -> confirmDeleteGroup(group)
                }
            }
            .show()
    }

    private fun confirmDeleteGroup(group: ModifierGroupModel) {
        AlertDialog.Builder(context)
            .setTitle("Delete ${group.name}?")
            .setMessage("This will remove the group and all its options.")
            .setPositiveButton("Delete") { _, _ -> deleteGroup(group) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGroup(group: ModifierGroupModel) {
        db.collection("ModifierGroups")
            .document(group.id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                onDataChanged()
            }
    }

    private fun showEditGroupDialog(group: ModifierGroupModel) {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.setText(group.name)
        nameInput.hint = "Group name"

        val requiredCheckbox = CheckBox(context)
        requiredCheckbox.text = "Required"
        requiredCheckbox.isChecked = group.required

        val removeCheckbox = CheckBox(context)
        removeCheckbox.text = "Remove Ingredients Group"
        removeCheckbox.isChecked = group.groupType == "REMOVE"

        val maxSelectionInput = EditText(context)
        maxSelectionInput.setText(group.maxSelection.toString())
        maxSelectionInput.hint = "Max Selection"
        maxSelectionInput.inputType = InputType.TYPE_CLASS_NUMBER

        layout.addView(nameInput)
        layout.addView(requiredCheckbox)
        layout.addView(removeCheckbox)
        layout.addView(maxSelectionInput)

        AlertDialog.Builder(context)
            .setTitle("Edit Modifier Group")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newRequired = requiredCheckbox.isChecked
                val newGroupType = if (removeCheckbox.isChecked) "REMOVE" else "ADD"
                val newMax = maxSelectionInput.text.toString().toIntOrNull() ?: 1

                if (newName.isNotEmpty()) {
                    db.collection("ModifierGroups")
                        .document(group.id)
                        .update(
                            mapOf(
                                "name" to newName,
                                "required" to newRequired,
                                "maxSelection" to newMax,
                                "groupType" to newGroupType
                            )
                        )
                        .addOnSuccessListener {
                            group.name = newName
                            group.groupType = newGroupType
                            Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                            onDataChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
