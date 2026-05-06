package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class CategoryAdapter(
    private val categories: List<CategoryModel>,
    private val subcategories: List<SubcategoryModel> = emptyList(),
    private val onCategoryClick: (String) -> Unit,
    private val onSubcategoryClick: ((String, String) -> Unit)? = null,
    private val context: Context,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val db = FirebaseFirestore.getInstance()
    private var selectedPosition: Int = RecyclerView.NO_POSITION

    companion object {
        val ALL_ORDER_TYPES = listOf("BAR_TAB", "TO_GO", "DINE_IN")

        private val ORDER_TYPE_SHORT_LABELS = mapOf(
            "BAR_TAB" to "BAR",
            "TO_GO" to "TO GO",
            "DINE_IN" to "DINE IN"
        )

        private const val VIEW_TYPE_CATEGORY = 0
        private const val VIEW_TYPE_SUBCATEGORY = 1
    }

    sealed class ListRow {
        data class CategoryRow(val category: CategoryModel) : ListRow()
        data class SubcategoryRow(val sub: SubcategoryModel) : ListRow()
    }

    private val rows: List<ListRow> = buildList {
        for (cat in categories) {
            add(ListRow.CategoryRow(cat))
            val catSubs = subcategories.filter { it.categoryId == cat.id }.sortedBy { it.order }
            for (sub in catSubs) {
                add(ListRow.SubcategoryRow(sub))
            }
        }
    }

    inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtCategoryName)
        val indicator: View = view.findViewById(R.id.selectedIndicator)
        val root: View = view.findViewById(R.id.categoryRoot)
    }

    inner class SubcategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtCategoryName)
        val indicator: View = view.findViewById(R.id.selectedIndicator)
        val root: View = view.findViewById(R.id.categoryRoot)
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is ListRow.CategoryRow -> VIEW_TYPE_CATEGORY
        is ListRow.SubcategoryRow -> VIEW_TYPE_SUBCATEGORY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return if (viewType == VIEW_TYPE_CATEGORY) CategoryViewHolder(view) else SubcategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ListRow.CategoryRow -> bindCategory(holder as CategoryViewHolder, row.category, position)
            is ListRow.SubcategoryRow -> bindSubcategory(holder as SubcategoryViewHolder, row.sub, position)
        }
    }

    private fun bindCategory(holder: CategoryViewHolder, category: CategoryModel, position: Int) {
        val isSelected = position == selectedPosition

        // Category name only in the list; kitchen label is for routing (long-press → Kitchen Label).
        holder.nameText.text = category.name
        holder.nameText.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        holder.nameText.setTextColor(
            if (isSelected) 0xFF6366F1.toInt() else 0xFF334155.toInt()
        )

        holder.indicator.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.root.setBackgroundResource(
            if (isSelected) R.drawable.bg_category_selected else R.drawable.bg_category_default
        )
        holder.root.setPadding(
            holder.root.paddingLeft, holder.root.paddingTop,
            holder.root.paddingRight, holder.root.paddingBottom
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

    private fun bindSubcategory(holder: SubcategoryViewHolder, sub: SubcategoryModel, position: Int) {
        val isSelected = position == selectedPosition

        holder.nameText.text = sub.name
        holder.nameText.textSize = 13f
        holder.nameText.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        holder.nameText.setTextColor(
            if (isSelected) 0xFF6366F1.toInt() else 0xFF64748B.toInt()
        )

        holder.indicator.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.root.setBackgroundResource(
            if (isSelected) R.drawable.bg_category_selected else R.drawable.bg_category_default
        )
        holder.root.setPadding(48, holder.root.paddingTop, holder.root.paddingRight, holder.root.paddingBottom)

        holder.itemView.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = holder.adapterPosition
            if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onSubcategoryClick?.invoke(sub.categoryId, sub.id)
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun showCategoryOptions(category: CategoryModel) {
        val labelSuffix = if (category.kitchenLabel.isNotEmpty()) " (${category.kitchenLabel})" else ""
        val options = arrayOf("Edit", "Kitchen Label$labelSuffix", "Add KDS", "Delete")

        AlertDialog.Builder(context)
            .setTitle(category.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(category)
                    1 -> showKitchenLabelPicker(category)
                    2 -> showCategoryKdsAssignment(category)
                    3 -> deleteCategory(category.id)
                }
            }
            .show()
    }

    private data class KdsPickerRow(val id: String, val name: String)

    private fun dp(px: Int): Int = (px * context.resources.displayMetrics.density).toInt()

    /**
     * Adds or removes this category id on [KdsStationRouting.COLLECTION] docs’ [assignedCategoryIds].
     * All items that list this category (including subcategory-only placements that still use
     * this category id) follow the same KDS routing as the dashboard.
     */
    private fun showCategoryKdsAssignment(category: CategoryModel) {
        val categoryId = category.id.trim()
        if (categoryId.isEmpty()) return

        db.collection(KdsStationRouting.COLLECTION).get()
            .addOnSuccessListener { snap ->
                val all = snap.documents.mapNotNull { doc ->
                    if (!KdsStationRouting.isDeviceSelectable(doc)) return@mapNotNull null
                    val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
                    KdsPickerRow(doc.id, name)
                }.sortedBy { it.name.lowercase(Locale.getDefault()) }

                if (all.isEmpty()) {
                    val msg = if (snap.documents.isEmpty()) {
                        R.string.category_no_kds_devices
                    } else {
                        R.string.category_no_kds_active_devices
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val baseline = snap.documents
                    .filter { KdsStationRouting.isDeviceSelectable(it) && KdsStationRouting.deviceCoversCategory(it, categoryId) }
                    .map { it.id }
                    .toSet()

                val inflater = LayoutInflater.from(context)
                val content = inflater.inflate(R.layout.dialog_kds_station_picker, null, false)
                content.findViewById<TextView>(R.id.txtKdsPickerHint).text =
                    context.getString(R.string.category_kds_hint)
                val container = content.findViewById<LinearLayout>(R.id.containerKdsPickerChecks)

                val boxes = all.map { dev ->
                    CheckBox(context).apply {
                        text = dev.name
                        isChecked = baseline.contains(dev.id)
                        textSize = 16f
                        setTextColor(Color.parseColor("#1E293B"))
                        setPadding(0, dp(6), 0, dp(6))
                    }.also { container.addView(it) }
                }

                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.category_assign_kds_title)
                    .setView(content)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val want = all.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.id }.toSet()
                        saveCategoryKdsAssignments(categoryId, all, want, baseline)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, e.message ?: "Failed to load KDS devices", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveCategoryKdsAssignments(
        categoryId: String,
        allDevices: List<KdsPickerRow>,
        wantIds: Set<String>,
        baselineHadIds: Set<String>,
    ) {
        val batch = db.batch()
        var ops = 0
        for (dev in allDevices) {
            val want = wantIds.contains(dev.id)
            val had = baselineHadIds.contains(dev.id)
            if (want == had) continue
            val ref = db.collection(KdsStationRouting.COLLECTION).document(dev.id)
            if (want) {
                batch.update(ref, "assignedCategoryIds", FieldValue.arrayUnion(categoryId))
            } else {
                batch.update(ref, "assignedCategoryIds", FieldValue.arrayRemove(categoryId))
            }
            ops++
        }
        if (ops == 0) {
            Toast.makeText(context, R.string.category_kds_saved, Toast.LENGTH_SHORT).show()
            return
        }
        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(context, R.string.category_kds_saved, Toast.LENGTH_SHORT).show()
                onDataChanged()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
                                "normalizedName" to CategoryNameUtils.normalizeCategoryName(newName),
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

    private fun showKitchenLabelPicker(category: CategoryModel) {
        val available = KitchenRoutingLabelsFirestore
            .labelsForItemAssignmentPicker(context, listOfNotNull(category.kitchenLabel.takeIf { it.isNotEmpty() }))
        if (available.isEmpty()) {
            Toast.makeText(context, "No kitchen labels configured on any printer", Toast.LENGTH_SHORT).show()
            return
        }
        val current = PrinterLabelKey.normalize(category.kitchenLabel)
        val names = arrayOf("None") + available.toTypedArray()
        val checkedIndex = if (current.isEmpty()) 0
            else available.indexOfFirst { PrinterLabelKey.normalize(it) == current } + 1

        AlertDialog.Builder(context)
            .setTitle("Kitchen Label — ${category.name}")
            .setSingleChoiceItems(names, checkedIndex.coerceAtLeast(0), null)
            .setPositiveButton("Save") { dialog, _ ->
                val selected = (dialog as AlertDialog).listView.checkedItemPosition
                val label = if (selected <= 0) "" else available[selected - 1]
                val previousLabel = category.kitchenLabel.trim()
                val updates = if (label.isEmpty()) {
                    mapOf("kitchenLabel" to com.google.firebase.firestore.FieldValue.delete())
                } else {
                    mapOf("kitchenLabel" to label)
                }
                db.collection("Categories").document(category.id).update(updates)
                    .addOnSuccessListener {
                        if (label.isEmpty() && previousLabel.isNotEmpty()) {
                            CategoryKitchenLabelCascade.afterCategoryKitchenLabelRemoved(
                                db,
                                category.id,
                                previousLabel,
                                onDone = {
                                    Toast.makeText(
                                        context,
                                        "Kitchen label removed from category, subcategories, and matching items",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onDataChanged()
                                },
                                onError = { e ->
                                    Toast.makeText(
                                        context,
                                        "Category updated; cascade failed: ${e.message}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onDataChanged()
                                },
                            )
                        } else {
                            Toast.makeText(
                                context,
                                if (label.isEmpty()) "Kitchen label removed" else "Label set to \"$label\"",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDataChanged()
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
