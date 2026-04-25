package com.ernesto.myapplication

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale

class ItemDetailActivity : AppCompatActivity() {

    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var txtName: TextView
    private lateinit var txtPrice: TextView
    private lateinit var txtCategory: TextView
    private lateinit var containerTaxes: LinearLayout
    private lateinit var txtTaxesEmpty: TextView
    private lateinit var containerModifiers: LinearLayout
    private lateinit var txtModifiersEmpty: TextView
    private lateinit var containerLabels: LinearLayout
    private lateinit var txtLabelsEmpty: TextView
    private lateinit var containerKds: LinearLayout
    private lateinit var txtKdsEmpty: TextView
    private lateinit var cardItemStock: MaterialCardView
    private lateinit var txtItemDetailStock: TextView
    private lateinit var txtItemDetailStockStatus: TextView

    private var stockCountingEnabled = true
    private var itemLoadComplete = false
    private var itemStock = 0L

    private var itemId = ""
    private var itemName = ""
    private var itemPrice = 0.0
    private var itemPrices: Map<String, Double> = emptyMap()
    private var itemCategoryId = ""
    /** Category doc ids where this item is placed (matches dashboard / KDS [placementCategoryIds]). */
    private var itemPlacementCategoryIds: Set<String> = emptySet()
    private var itemSubcategoryId = ""
    private var categoryKitchenLabelRaw = ""
    private var subcategoryKitchenLabelRaw = ""
    private var assignedTaxIds: List<String> = emptyList()
    private var assignedModifierGroupIds: List<String> = emptyList()
    private var labels: List<String> = emptyList()
    /** KDS devices that show this item (explicit ids, category routing, or no filter = all). */
    private var kdsAssignedStations: List<KdsDeviceRow> = emptyList()

    private data class KdsDeviceRow(val id: String, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.item_detail_title)

        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty().trim()
        if (itemId.isEmpty()) {
            finish()
            return
        }

        txtName = findViewById(R.id.txtItemDetailName)
        txtPrice = findViewById(R.id.txtItemDetailPrice)
        txtCategory = findViewById(R.id.txtItemDetailCategory)
        containerTaxes = findViewById(R.id.containerTaxes)
        txtTaxesEmpty = findViewById(R.id.txtTaxesEmpty)
        containerModifiers = findViewById(R.id.containerModifiers)
        txtModifiersEmpty = findViewById(R.id.txtModifiersEmpty)
        containerLabels = findViewById(R.id.containerLabels)
        txtLabelsEmpty = findViewById(R.id.txtLabelsEmpty)
        containerKds = findViewById(R.id.containerKds)
        txtKdsEmpty = findViewById(R.id.txtKdsEmpty)
        cardItemStock = findViewById(R.id.cardItemStock)
        txtItemDetailStock = findViewById(R.id.txtItemDetailStock)
        txtItemDetailStockStatus = findViewById(R.id.txtItemDetailStockStatus)

        findViewById<View>(R.id.btnEditItemName).setOnClickListener { showEditNameDialog() }
        txtPrice.setOnClickListener { showEditPriceDialog() }
        findViewById<MaterialButton>(R.id.btnAddTax).setOnClickListener { showAddTaxDialog() }
        findViewById<MaterialButton>(R.id.btnAddModifier).setOnClickListener { showAddModifierDialog() }
        findViewById<MaterialButton>(R.id.btnAddLabel).setOnClickListener { showAddLabelDialog() }
        findViewById<MaterialButton>(R.id.btnAddKds).setOnClickListener { showAddKdsDialog() }
        findViewById<MaterialButton>(R.id.btnDeleteItem).setOnClickListener { confirmDelete() }
        findViewById<MaterialButton>(R.id.btnEditStock).setOnClickListener { showEditStockDialog() }
        txtItemDetailStock.setOnClickListener { showEditStockDialog() }

        loadStockSetting()
        loadItem()
    }

    override fun onResume() {
        super.onResume()
        loadStockSetting()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── Stock (Settings / inventory + MenuItems.stock) ──────────────

    private fun loadStockSetting() {
        db.collection("Settings").document("inventory").get()
            .addOnSuccessListener { doc ->
                stockCountingEnabled = doc.getBoolean("stockCountingEnabled") ?: true
                applyStockSectionVisibility()
            }
            .addOnFailureListener {
                stockCountingEnabled = true
                applyStockSectionVisibility()
            }
    }

    private fun applyStockSectionVisibility() {
        val show = stockCountingEnabled && itemLoadComplete
        cardItemStock.visibility = if (show) View.VISIBLE else View.GONE
        if (show) bindStockDisplay()
    }

    private fun bindStockDisplay() {
        txtItemDetailStock.text = itemStock.toString()
        when {
            itemStock <= 0 -> {
                txtItemDetailStockStatus.text = getString(R.string.item_detail_stock_status_out)
                txtItemDetailStockStatus.setTextColor(0xFFDC2626.toInt())
                txtItemDetailStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_red)
            }
            itemStock <= 10 -> {
                txtItemDetailStockStatus.text = getString(R.string.item_detail_stock_status_low)
                txtItemDetailStockStatus.setTextColor(0xFFA16207.toInt())
                txtItemDetailStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_yellow)
            }
            else -> {
                txtItemDetailStockStatus.text = getString(R.string.item_detail_stock_status_in)
                txtItemDetailStockStatus.setTextColor(0xFF16A34A.toInt())
                txtItemDetailStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_green)
            }
        }
    }

    private fun showEditStockDialog() {
        if (!stockCountingEnabled || !itemLoadComplete) return
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.item_detail_stock_hint)
        }
        val edit = TextInputEditText(til.context).apply {
            setText(itemStock.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        til.addView(edit)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.item_detail_edit_stock_title)
            .setView(til)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            edit.selectAll()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = edit.text?.toString()?.trim().orEmpty()
                val newStock = raw.toLongOrNull()
                if (newStock == null || newStock < 0) {
                    Toast.makeText(this, R.string.item_detail_stock_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                db.collection("MenuItems").document(itemId)
                    .update("stock", newStock)
                    .addOnSuccessListener {
                        itemStock = newStock
                        bindStockDisplay()
                        Toast.makeText(this, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
                    }
            }
        }
        dialog.show()
    }

    // ── Load ──────────────────────────────────────────────────────────

    private fun loadItem() {
        db.collection("MenuItems").document(itemId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(this, R.string.item_detail_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                itemStock = doc.getLong("stock") ?: 0L
                itemLoadComplete = true
                applyStockSectionVisibility()

                itemName = doc.getString("name").orEmpty()
                itemPrice = doc.getDouble("price") ?: 0.0
                @Suppress("UNCHECKED_CAST")
                itemPrices = (doc.get("prices") as? Map<String, Double>) ?: emptyMap()
                itemCategoryId = doc.getString("categoryId").orEmpty()
                itemPlacementCategoryIds = KdsStationRouting.placementCategoryIdsFromMenuItemDoc(doc)
                itemSubcategoryId = subcategoryIdForItem(doc, itemCategoryId)
                @Suppress("UNCHECKED_CAST")
                assignedTaxIds = (doc.get("assignedTaxIds") as? List<String>) ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                assignedModifierGroupIds = (doc.get("assignedModifierGroupIds") as? List<String>) ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val labelsField = (doc.get("labels") as? List<*>)?.mapNotNull { it as? String }?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
                val printerLabelField = doc.getString("printerLabel")?.trim()?.takeIf { it.isNotEmpty() }
                val rawAssigned: List<String> = when {
                    labelsField.isNotEmpty() -> labelsField
                    printerLabelField != null -> listOf(printerLabelField)
                    else -> emptyList()
                }
                val validAssigned = filterToPrinterRoutingLabels(rawAssigned)
                labels = validAssigned
                if (routingLabelsAssignmentChanged(rawAssigned, validAssigned)) {
                    applyRoutingLabelsToFirestore(validAssigned, showSavedToast = false)
                }

                bindHeader()
                loadCategoryAndSubcategoryMeta()
                loadAssignedTaxes()
                loadAssignedModifiers()
                loadKdsAssignments()
            }
            .addOnFailureListener {
                Toast.makeText(this, R.string.item_detail_load_failed, Toast.LENGTH_SHORT).show()
            }
    }

    // ── Header ────────────────────────────────────────────────────────

    private fun bindHeader() {
        txtName.text = itemName
        val displayPrice = itemPrices.values.firstOrNull() ?: itemPrice
        txtPrice.text = String.format("$%.2f", displayPrice)
    }

    /**
     * Loads category name, category [kitchenLabel], and subcategory [kitchenLabel] so the header
     * and labels section can reflect inheritance (item → subcategory → category), matching
     * [MenuItemRoutingLabel.resolve].
     */
    private fun loadCategoryAndSubcategoryMeta() {
        var pending = 2
        fun onMetaReady() {
            pending--
            if (pending == 0) bindLabels()
        }

        if (itemCategoryId.isEmpty()) {
            txtCategory.text = getString(R.string.item_detail_no_category)
            categoryKitchenLabelRaw = ""
            onMetaReady()
        } else {
            db.collection("Categories").document(itemCategoryId).get()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val cdoc = task.result
                        if (cdoc != null && cdoc.exists()) {
                            val name = cdoc.getString("name")
                            txtCategory.text =
                                if (name.isNullOrBlank()) getString(R.string.item_detail_no_category) else name
                            categoryKitchenLabelRaw = cdoc.getString("kitchenLabel")?.trim().orEmpty()
                        } else {
                            txtCategory.text = getString(R.string.item_detail_no_category)
                            categoryKitchenLabelRaw = ""
                        }
                    } else {
                        txtCategory.text = getString(R.string.item_detail_no_category)
                        categoryKitchenLabelRaw = ""
                    }
                    onMetaReady()
                }
        }

        if (itemSubcategoryId.isEmpty()) {
            subcategoryKitchenLabelRaw = ""
            onMetaReady()
        } else {
            db.collection("subcategories").document(itemSubcategoryId).get()
                .addOnCompleteListener { task ->
                    subcategoryKitchenLabelRaw =
                        if (task.isSuccessful) {
                            val sdoc = task.result
                            if (sdoc != null && sdoc.exists()) {
                                sdoc.getString("kitchenLabel")?.trim().orEmpty()
                            } else ""
                        } else ""
                    onMetaReady()
                }
        }
    }

    /**
     * Effective inherited routing label when the item has no own labels (same order as
     * [MenuItemRoutingLabel.resolve]). Hidden unless at least one **kitchen** printer defines
     * routing labels — otherwise category/subcategory kitchen labels look like printer routing
     * when no kitchen station is configured.
     */
    private fun inheritedRoutingDisplay(): Pair<String, Boolean>? {
        if (labels.isNotEmpty()) return null
        if (SelectedPrinterPrefs.allKitchenLabelOptions(this).isEmpty()) return null
        if (subcategoryKitchenLabelRaw.isNotBlank()) {
            return subcategoryKitchenLabelRaw to true
        }
        if (categoryKitchenLabelRaw.isNotBlank()) {
            return categoryKitchenLabelRaw to false
        }
        return null
    }

    // ── Edit Name ─────────────────────────────────────────────────────

    private fun showEditNameDialog() {
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.item_detail_name_hint)
        }
        val edit = TextInputEditText(til.context).apply {
            setText(itemName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        til.addView(edit)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.item_detail_edit_name)
            .setView(til)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            edit.selectAll()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val newName = edit.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.item_detail_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                db.collection("MenuItems").document(itemId)
                    .update("name", newName)
                    .addOnSuccessListener {
                        itemName = newName
                        txtName.text = newName
                        supportActionBar?.title = newName
                        Toast.makeText(this, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
                    }
            }
        }
        dialog.show()
    }

    // ── Edit Price ───────────────────────────────────────────────────

    private fun showEditPriceDialog() {
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.item_detail_price_hint)
            prefixText = "$"
        }
        val currentDisplay = itemPrices.values.firstOrNull() ?: itemPrice
        val edit = TextInputEditText(til.context).apply {
            setText(String.format(java.util.Locale.US, "%.2f", currentDisplay))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        til.addView(edit)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.item_detail_edit_price)
            .setView(til)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            edit.selectAll()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = edit.text?.toString()?.trim().orEmpty()
                val newPrice = raw.toDoubleOrNull()
                if (newPrice == null || newPrice < 0) {
                    Toast.makeText(this, R.string.item_detail_price_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                db.collection("MenuItems").document(itemId)
                    .update("price", newPrice)
                    .addOnSuccessListener {
                        itemPrice = newPrice
                        itemPrices = emptyMap()
                        bindHeader()
                        Toast.makeText(this, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
                    }
            }
        }
        dialog.show()
    }

    // ── Taxes (assigned only) ─────────────────────────────────────────

    private data class TaxRow(val id: String, val name: String, val type: String, val amount: Double)

    private fun loadAssignedTaxes() {
        containerTaxes.removeAllViews()
        if (assignedTaxIds.isEmpty()) {
            txtTaxesEmpty.visibility = View.VISIBLE
            return
        }
        txtTaxesEmpty.visibility = View.GONE

        db.collection("Taxes").get()
            .addOnSuccessListener { snap ->
                val taxMap = snap.documents.associate { doc ->
                    doc.id to TaxRow(
                        doc.id,
                        doc.getString("name").orEmpty(),
                        doc.getString("type").orEmpty(),
                        doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble() ?: 0.0,
                    )
                }
                containerTaxes.removeAllViews()
                for (taxId in assignedTaxIds) {
                    val tax = taxMap[taxId] ?: continue
                    addAssignedTaxRow(tax)
                }
                if (containerTaxes.childCount == 0) {
                    txtTaxesEmpty.visibility = View.VISIBLE
                }
            }
    }

    private fun addAssignedTaxRow(tax: TaxRow) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val label = if (tax.type == "FIXED") {
            String.format(java.util.Locale.US, "%s ($%.2f)", tax.name, tax.amount)
        } else {
            String.format(java.util.Locale.US, "%s (%.1f%%)", tax.name, tax.amount)
        }

        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        containerTaxes.addView(row)

        containerTaxes.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
    }

    private fun showAddTaxDialog() {
        db.collection("Taxes").get()
            .addOnSuccessListener { snap ->
                val allTaxes = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val type = doc.getString("type") ?: return@mapNotNull null
                    val amount = (doc.getDouble("amount")
                        ?: doc.getLong("amount")?.toDouble()) ?: return@mapNotNull null
                    TaxRow(doc.id, name, type, amount)
                }
                if (allTaxes.isEmpty()) {
                    Toast.makeText(this, R.string.item_detail_no_taxes_assigned, Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val names = allTaxes.map { tax ->
                    if (tax.type == "FIXED") {
                        String.format(java.util.Locale.US, "%s ($%.2f)", tax.name, tax.amount)
                    } else {
                        String.format(java.util.Locale.US, "%s (%.1f%%)", tax.name, tax.amount)
                    }
                }.toTypedArray()

                val checked = BooleanArray(allTaxes.size) { assignedTaxIds.contains(allTaxes[it].id) }

                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.item_detail_add_tax_title)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(R.string.save) { _, _ ->
                        val newIds = allTaxes.filterIndexed { i, _ -> checked[i] }.map { it.id }
                        saveAssignedTaxIds(newIds)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
    }

    private fun saveAssignedTaxIds(ids: List<String>) {
        val updates: Map<String, Any> = if (ids.isEmpty()) {
            mapOf("assignedTaxIds" to FieldValue.delete())
        } else {
            mapOf("assignedTaxIds" to ids)
        }
        db.collection("MenuItems").document(itemId).update(updates)
            .addOnSuccessListener {
                assignedTaxIds = ids
                loadAssignedTaxes()
                Toast.makeText(this, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ── Modifier Groups (assigned only) ───────────────────────────────

    private data class ModGroupRow(val id: String, val name: String, val required: Boolean, val min: Int, val max: Int)

    private fun loadAssignedModifiers() {
        containerModifiers.removeAllViews()
        if (assignedModifierGroupIds.isEmpty()) {
            txtModifiersEmpty.visibility = View.VISIBLE
            return
        }
        txtModifiersEmpty.visibility = View.GONE

        db.collection("ModifierGroups").get()
            .addOnSuccessListener { snap ->
                val groupMap = mutableMapOf<String, ModGroupRow>()
                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: continue
                    val required = doc.getBoolean("required") ?: false
                    val min = doc.getLong("minSelection")?.toInt() ?: 0
                    val max = doc.getLong("maxSelection")?.toInt() ?: 0
                    groupMap[doc.id] = ModGroupRow(doc.id, name, required, min, max)
                }
                bindAssignedModifierRows(groupMap)
            }
    }

    private fun bindAssignedModifierRows(groupMap: Map<String, ModGroupRow>) {
        containerModifiers.removeAllViews()
        val assigned = assignedModifierGroupIds.mapNotNull { groupMap[it] }
        if (assigned.isEmpty()) {
            txtModifiersEmpty.visibility = View.VISIBLE
            return
        }
        txtModifiersEmpty.visibility = View.GONE

        for (group in assigned) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = group.name
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
                setTypeface(null, Typeface.BOLD)
            })
            val detail = buildString {
                append(if (group.required) "Required" else "Optional")
                if (group.min > 0 || group.max > 0) {
                    append(" · ")
                    if (group.min > 0) append("min ${group.min}")
                    if (group.min > 0 && group.max > 0) append(", ")
                    if (group.max > 0) append("max ${group.max}")
                }
            }
            row.addView(TextView(this).apply {
                text = detail
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
            })
            containerModifiers.addView(row)

            containerModifiers.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun showAddModifierDialog() {
        db.collection("ModifierGroups").get()
            .addOnSuccessListener { snap ->
                val allGroups = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val required = doc.getBoolean("required") ?: false
                    val min = doc.getLong("minSelection")?.toInt() ?: 0
                    val max = doc.getLong("maxSelection")?.toInt() ?: 0
                    ModGroupRow(doc.id, name, required, min, max)
                }
                if (allGroups.isEmpty()) {
                    Toast.makeText(this, R.string.item_detail_no_modifiers, Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val names = allGroups.map { g ->
                    val suffix = if (g.required) "Required" else "Optional"
                    "${g.name} ($suffix)"
                }.toTypedArray()

                val checked = BooleanArray(allGroups.size) {
                    assignedModifierGroupIds.contains(allGroups[it].id)
                }

                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.item_detail_add_modifier_title)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(R.string.save) { _, _ ->
                        val newIds = allGroups.filterIndexed { i, _ -> checked[i] }.map { it.id }
                        saveAssignedModifierGroupIds(newIds)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
    }

    private fun saveAssignedModifierGroupIds(ids: List<String>) {
        val updates: Map<String, Any> = if (ids.isEmpty()) {
            mapOf("assignedModifierGroupIds" to FieldValue.delete())
        } else {
            mapOf("assignedModifierGroupIds" to ids)
        }
        db.collection("MenuItems").document(itemId).update(updates)
            .addOnSuccessListener {
                assignedModifierGroupIds = ids
                loadAssignedModifiers()
                Toast.makeText(this, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ── Labels (assigned only) ────────────────────────────────────────

    private fun bindLabels() {
        containerLabels.removeAllViews()
        val inherited = inheritedRoutingDisplay()

        if (labels.isEmpty() && inherited == null) {
            txtLabelsEmpty.visibility = View.VISIBLE
            return
        }
        txtLabelsEmpty.visibility = View.GONE

        for (label in labels) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            containerLabels.addView(row)

            containerLabels.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }

        if (inherited != null) {
            val (text, fromSubcategory) = inherited
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            col.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
            })
            col.addView(TextView(this).apply {
                this.text = getString(
                    if (fromSubcategory) R.string.item_detail_label_from_subcategory
                    else R.string.item_detail_label_from_category
                )
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
            })
            containerLabels.addView(col)
            containerLabels.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun showAddLabelDialog() {
        val available = KitchenRoutingLabelsFirestore.labelsForItemAssignmentPicker(this, labels)
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.item_detail_no_labels_available, Toast.LENGTH_SHORT).show()
            return
        }
        showLabelPicker(available)
    }

    private fun showLabelPicker(available: List<String>) {
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.item_detail_no_labels_available, Toast.LENGTH_SHORT).show()
            return
        }

        val names = available.toTypedArray()
        val checked = BooleanArray(available.size) { labels.contains(available[it]) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.item_detail_add_label_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val selected = available.filterIndexed { i, _ -> checked[i] }
                saveLabels(selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Keeps order; drops unknown labels; dedupes by normalized key (first spelling wins). */
    private fun filterToPrinterRoutingLabels(raw: List<String>): List<String> {
        val validNorms = SelectedPrinterPrefs.allRoutingLabelsFromSavedPrinters(this)
            .map { PrinterLabelKey.normalize(it) }
            .toSet()
        val seenNorm = mutableSetOf<String>()
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.filter {
            val n = PrinterLabelKey.normalize(it)
            validNorms.contains(n) && seenNorm.add(n)
        }
    }

    private fun routingLabelsAssignmentChanged(raw: List<String>, valid: List<String>): Boolean {
        if (raw.size != valid.size) return true
        return raw.indices.any { i ->
            PrinterLabelKey.normalize(raw[i]) != PrinterLabelKey.normalize(valid[i])
        }
    }

    private fun applyRoutingLabelsToFirestore(trimmed: List<String>, showSavedToast: Boolean) {
        val updates = mutableMapOf<String, Any>()
        if (trimmed.isEmpty()) {
            updates["labels"] = FieldValue.delete()
            updates["printerLabel"] = FieldValue.delete()
        } else {
            updates["labels"] = trimmed
            updates["printerLabel"] = trimmed.first()
            KitchenRoutingLabelsFirestore.mergeLabelsIntoFirestore(db, trimmed)
        }
        db.collection("MenuItems").document(itemId).update(updates)
            .addOnSuccessListener {
                labels = trimmed
                bindLabels()
                if (showSavedToast) {
                    Toast.makeText(this, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveLabels(newLabels: List<String>) {
        val trimmed = newLabels.map { it.trim() }.filter { it.isNotEmpty() }
        applyRoutingLabelsToFirestore(trimmed, showSavedToast = true)
    }

    // ── KDS stations (kds_devices: assignedItemIds, assignedCategoryIds, or empty = all) ──

    private fun loadKdsAssignments() {
        db.collection(KdsStationRouting.COLLECTION).get()
            .addOnSuccessListener { snap ->
                val rows = snap.documents
                    .filter {
                        KdsStationRouting.isDeviceSelectable(it) &&
                            KdsStationRouting.deviceCoversMenuItem(it, itemId, itemPlacementCategoryIds)
                    }
                    .map { doc ->
                        val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
                        KdsDeviceRow(doc.id, name)
                    }
                    .sortedBy { it.name.lowercase(Locale.getDefault()) }
                kdsAssignedStations = rows
                bindKdsRows()
            }
            .addOnFailureListener {
                kdsAssignedStations = emptyList()
                bindKdsRows()
            }
    }

    private fun bindKdsRows() {
        containerKds.removeAllViews()
        if (kdsAssignedStations.isEmpty()) {
            txtKdsEmpty.visibility = View.VISIBLE
            return
        }
        txtKdsEmpty.visibility = View.GONE

        for (station in kdsAssignedStations) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = station.name
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            containerKds.addView(row)
            containerKds.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun showAddKdsDialog() {
        KdsMenuItemStationPicker.show(this, itemId, itemPlacementCategoryIds, db) {
            loadKdsAssignments()
        }
    }

    // ── Delete ────────────────────────────────────────────────────────

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.item_detail_delete_title, itemName))
            .setMessage(R.string.item_detail_delete_message)
            .setPositiveButton(R.string.item_detail_delete_action) { _, _ ->
                removeItemFromKdsDeviceAssignments {
                    db.collection("MenuItems").document(itemId).delete()
                        .addOnSuccessListener {
                            Toast.makeText(this, R.string.item_detail_deleted, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Clears this menu item id from every KDS device filter list (Firestore does not cascade). */
    private fun removeItemFromKdsDeviceAssignments(done: () -> Unit) {
        db.collection(KdsStationRouting.COLLECTION)
            .whereArrayContains("assignedItemIds", itemId)
            .get()
            .addOnCompleteListener { task ->
                val qs = task.result
                if (!task.isSuccessful || qs == null || qs.isEmpty) {
                    done()
                    return@addOnCompleteListener
                }
                val batch = db.batch()
                for (doc in qs.documents) {
                    batch.update(doc.reference, "assignedItemIds", FieldValue.arrayRemove(itemId))
                }
                batch.commit().addOnCompleteListener { done() }
            }
    }

    // ── Util ──────────────────────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ITEM_ID = "ITEM_ID"

        fun createIntent(context: Context, itemId: String): Intent =
            Intent(context, ItemDetailActivity::class.java).putExtra(EXTRA_ITEM_ID, itemId)

        /** Same resolution as [MenuOnlyActivity] / [MenuActivity] for filtering and routing. */
        private fun subcategoryIdForItem(doc: com.google.firebase.firestore.DocumentSnapshot, categoryId: String): String {
            if (categoryId.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val byCat = doc.get("subcategoryByCategoryId") as? Map<*, *>
                val v = byCat?.get(categoryId) as? String
                if (!v.isNullOrBlank()) return v
            }
            return doc.getString("subcategoryId").orEmpty()
        }
    }
}
