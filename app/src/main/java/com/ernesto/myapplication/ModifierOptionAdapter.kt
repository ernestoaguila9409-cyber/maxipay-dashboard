package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

data class ModifierOptionDisplay(
    val id: String,
    val name: String,
    val price: Double,
    val isRemove: Boolean,
    val triggersModifierGroupIds: List<String> = emptyList(),
    /** Persisted `action` on Firestore (`ADD` / `REMOVE`). */
    val action: String = "ADD",
)

class ModifierOptionAdapter(
    private val context: Context,
    private val optionList: List<ModifierOptionDisplay>,
    private val refresh: () -> Unit,
    private val groupId: String? = null,
    private val useEmbeddedOptions: Boolean = false,
    private val groupType: String = "ADD",
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
        db.collection("ModifierGroups").get()
            .addOnSuccessListener { snap ->
                val otherGroups = snap.documents.mapNotNull { doc ->
                    val gName = doc.getString("name") ?: return@mapNotNull null
                    if (doc.id == groupId) return@mapNotNull null
                    val required = doc.getBoolean("required") ?: false
                    val maxSel = doc.getLong("maxSelection")?.toInt() ?: 1
                    @Suppress("UNCHECKED_CAST")
                    val optCount = (doc.get("options") as? List<*>)?.size ?: 0
                    Triple(doc.id, gName,
                        "${if (required) "Required" else "Optional"} · 1–$maxSel sel · $optCount opt")
                }.sortedBy { it.second }
                buildEditOptionDialog(option, otherGroups)
            }
            .addOnFailureListener { buildEditOptionDialog(option, emptyList()) }
    }

    private fun buildEditOptionDialog(
        option: ModifierOptionDisplay,
        otherGroups: List<Triple<String, String, String>>
    ) {
        val showPrice = groupType.trim().uppercase(Locale.US) != "REMOVE"
        val form = ModifierOptionFormHelper.inflateForm(
            context,
            showPrice = showPrice,
            otherGroups = otherGroups,
            initialSelected = option.triggersModifierGroupIds.toSet(),
            startExpanded = option.triggersModifierGroupIds.isNotEmpty(),
        )
        form.editName.setText(option.name)
        if (showPrice) {
            form.editPrice.setText(option.price.toString())
        }

        val dialogRoot = ModifierOptionKeypadHost.wrap(context, form)
        val removeStyleCb = CheckBox(context).apply {
            text = context.getString(R.string.modifier_option_remove_style_checkbox)
            visibility =
                if (groupType.trim().uppercase(Locale.US) == "REMOVE") View.GONE else View.VISIBLE
            isChecked = option.action.trim().uppercase(Locale.US) == "REMOVE"
        }
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (groupType.trim().uppercase(Locale.US) != "REMOVE") addView(removeStyleCb)
            addView(
                dialogRoot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_MyApplication_MaterialAlertDialog)
            .setTitle("Edit Option")
            .setView(shell)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dialog.getButton(AppCompatAlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = form.editName.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    form.tilName.error = context.getString(R.string.item_detail_name_required)
                    return@setOnClickListener
                }
                form.tilName.error = null
                val newAction = when {
                    groupType.trim().uppercase(Locale.US) == "REMOVE" -> "REMOVE"
                    removeStyleCb.isChecked -> "REMOVE"
                    else -> "ADD"
                }
                val newPrice = if (newAction == "REMOVE") {
                    0.0
                } else {
                    form.editPrice.text?.toString()?.toDoubleOrNull() ?: 0.0
                }
                val selectedTriggers = form.triggers.selectedTriggerGroupIds()

                if (useEmbeddedOptions && groupId != null) {
                    db.collection("ModifierGroups").document(groupId).get()
                        .addOnSuccessListener { doc ->
                            @Suppress("UNCHECKED_CAST")
                            val rawOptions = doc.get("options") as? List<Map<String, Any>> ?: emptyList()
                            val updated = rawOptions.map { opt ->
                                if ((opt["id"] as? String) == option.id) {
                                    hashMapOf<String, Any>(
                                        "id" to option.id,
                                        "name" to newName,
                                        "price" to newPrice,
                                        "triggersModifierGroupIds" to selectedTriggers,
                                        "action" to newAction,
                                    )
                                } else {
                                    opt
                                }
                            }
                            db.collection("ModifierGroups").document(groupId)
                                .update("options", updated)
                                .addOnSuccessListener {
                                    dialog.dismiss()
                                    Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                                    refresh()
                                }
                        }
                } else {
                    val updates = mutableMapOf<String, Any>(
                        "name" to newName,
                        "price" to newPrice,
                        "triggersModifierGroupIds" to selectedTriggers,
                        "action" to newAction,
                    )
                    db.collection("ModifierOptions")
                        .document(option.id)
                        .update(updates)
                        .addOnSuccessListener {
                            dialog.dismiss()
                            Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                }
            }
        }
        dialog.show()
    }
}
