package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Locale

class MenuOnlyActivity : AppCompatActivity() {

    private lateinit var categoryRecycler: RecyclerView
    private lateinit var itemRecycler: RecyclerView
    private lateinit var editSearch: EditText
    private lateinit var txtItemCount: TextView
    private lateinit var subcategoryChipsScroll: HorizontalScrollView
    private lateinit var subcategoryChipsRow: LinearLayout

    private val db = FirebaseFirestore.getInstance()
    private var selectedCategoryId: String? = null
    private var selectedCategoryAvailability: List<String> = emptyList()
    private var selectedCategoryScheduled: Boolean = false
    private var stockCountingEnabled: Boolean = true
    private var currentAdapter: ItemAdapter? = null
    private var activeScheduleIds: Set<String> = emptySet()
    private var categoryAvailabilityMap: Map<String, List<String>> = emptyMap()
    private var allSubcategories: List<SubcategoryModel> = emptyList()
    private var selectedSubcategoryId: String? = null

    // Pending Add-Item form state (survives selection-screen round-trips)
    private var pendingName: String = ""
    private var pendingPrice: String = ""
    private var pendingStock: String = ""
    private var pendingSubId: String = ""
    private var pendingModifierIds: ArrayList<String> = arrayListOf()
    private var pendingTaxIds: ArrayList<String> = arrayListOf()
    private var pendingUseCategoryAvail: Boolean = true
    private var pendingOrderTypes: List<String> = emptyList()
    private var pendingPrinterLabel: String = ""
    private var pendingKdsDeviceIds: ArrayList<String> = arrayListOf()
    private var pendingKdsTouched: Boolean = false
    private var addItemDialogOpen: Boolean = false
    private var currentAddItemDialog: AlertDialog? = null

    private lateinit var modifierLauncher: ActivityResultLauncher<Intent>
    private lateinit var taxLauncher: ActivityResultLauncher<Intent>
    private lateinit var printerLabelLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_only)
        supportActionBar?.hide()

        modifierLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingModifierIds = result.data?.getStringArrayListExtra("SELECTED_IDS") ?: arrayListOf()
            }
            if (addItemDialogOpen) showAddItemDialog()
        }

        taxLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingTaxIds = result.data?.getStringArrayListExtra("SELECTED_IDS") ?: arrayListOf()
            }
            if (addItemDialogOpen) showAddItemDialog()
        }

        printerLabelLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingPrinterLabel = result.data?.getStringExtra(SelectPrinterLabelActivity.RESULT_SELECTED_LABEL)?.trim().orEmpty()
            }
            if (addItemDialogOpen) showAddItemDialog()
        }

        categoryRecycler = findViewById(R.id.categoryRecycler)
        itemRecycler = findViewById(R.id.itemRecycler)
        editSearch = findViewById(R.id.editSearch)
        txtItemCount = findViewById(R.id.txtItemCount)
        subcategoryChipsScroll = findViewById(R.id.subcategoryChipsScroll)
        subcategoryChipsRow = findViewById(R.id.subcategoryChipsRow)

        categoryRecycler.layoutManager = LinearLayoutManager(this)
        itemRecycler.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btnAddCategory).setOnClickListener {
            showAddCategoryDialog()
        }

        findViewById<View>(R.id.btnAddItem).setOnClickListener {
            if (selectedCategoryId == null) {
                Toast.makeText(this, "Select a category first", Toast.LENGTH_SHORT).show()
            } else {
                showAddItemDialog()
            }
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    subcategoryChipsScroll.visibility = View.GONE
                    loadAllItemsForSearch(query)
                } else {
                    if (selectedCategoryId != null) {
                        buildSubcategoryChips(selectedCategoryId!!)
                        loadItems(selectedCategoryId!!)
                    } else {
                        showEmptyState()
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadStockSetting()
    }

    override fun onResume() {
        super.onResume()
        loadStockSetting()
    }

    private fun loadStockSetting() {
        db.collection("Settings").document("inventory").get()
            .addOnSuccessListener { doc ->
                stockCountingEnabled = doc.getBoolean("stockCountingEnabled") ?: true
                loadActiveSchedules { loadCategories() }
            }
            .addOnFailureListener {
                stockCountingEnabled = true
                loadActiveSchedules { loadCategories() }
            }
    }

    private fun loadActiveSchedules(onComplete: () -> Unit) {
        db.collection("menuSchedules").get()
            .addOnSuccessListener { snap ->
                val now = Calendar.getInstance()
                val dayOfWeek = when (now.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> "MON"
                    Calendar.TUESDAY -> "TUE"
                    Calendar.WEDNESDAY -> "WED"
                    Calendar.THURSDAY -> "THU"
                    Calendar.FRIDAY -> "FRI"
                    Calendar.SATURDAY -> "SAT"
                    Calendar.SUNDAY -> "SUN"
                    else -> ""
                }
                val currentTime = String.format(
                    Locale.US, "%02d:%02d",
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE)
                )

                val active = mutableSetOf<String>()
                for (doc in snap.documents) {
                    @Suppress("UNCHECKED_CAST")
                    val days = doc.get("days") as? List<String> ?: continue
                    val startTime = doc.getString("startTime") ?: continue
                    val endTime = doc.getString("endTime") ?: continue
                    if (days.contains(dayOfWeek) && currentTime >= startTime && currentTime <= endTime) {
                        active.add(doc.id)
                    }
                }
                activeScheduleIds = active
                onComplete()
            }
            .addOnFailureListener {
                activeScheduleIds = emptySet()
                onComplete()
            }
    }

    // =========================================================
    // LOAD SUBCATEGORIES
    // =========================================================

    private fun loadSubcategories(onComplete: () -> Unit) {
        db.collection("subcategories")
            .get()
            .addOnSuccessListener { snap ->
                allSubcategories = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    SubcategoryModel(
                        id = doc.id,
                        name = name,
                        categoryId = doc.getString("categoryId") ?: "",
                        order = (doc.getLong("order") ?: 0L).toInt(),
                        kitchenLabel = doc.getString("kitchenLabel")?.trim().orEmpty(),
                    )
                }.sortedBy { it.order }
                onComplete()
            }
            .addOnFailureListener {
                allSubcategories = emptyList()
                onComplete()
            }
    }

    // =========================================================
    // LOAD CATEGORIES
    // =========================================================

    private fun loadCategories() {
        loadSubcategories {
            db.collection("Categories")
                .get()
                .addOnSuccessListener { documents ->
                    val categoryList = mutableListOf<CategoryModel>()

                    for (doc in documents) {
                        val name = doc.getString("name") ?: continue
                        @Suppress("UNCHECKED_CAST")
                        val availableOrderTypes =
                            (doc.get("availableOrderTypes") as? List<String>) ?: emptyList()
                        @Suppress("UNCHECKED_CAST")
                        val scheduleIds =
                            (doc.get("scheduleIds") as? List<String>) ?: emptyList()

                        if (scheduleIds.isNotEmpty()) {
                            if (activeScheduleIds.isEmpty() || scheduleIds.none { it in activeScheduleIds }) {
                                continue
                            }
                        }

                        categoryList.add(
                            CategoryModel(
                                id = doc.id,
                                name = name,
                                normalizedName = doc.getString("normalizedName"),
                                availableOrderTypes = availableOrderTypes,
                                scheduleIds = scheduleIds,
                                kitchenLabel = doc.getString("kitchenLabel")?.trim().orEmpty(),
                            )
                        )
                    }

                    categoryList.sortBy { it.name.lowercase() }

                    categoryAvailabilityMap = categoryList.associate { it.id to it.availableOrderTypes }

                    categoryRecycler.adapter =
                        CategoryAdapter(
                            categories = categoryList,
                            subcategories = emptyList(),
                            onCategoryClick = { categoryId ->
                                selectedCategoryId = categoryId
                                selectedSubcategoryId = null
                                val cat = categoryList.find { it.id == categoryId }
                                selectedCategoryAvailability =
                                    cat?.availableOrderTypes ?: emptyList()
                                selectedCategoryScheduled =
                                    cat?.scheduleIds?.isNotEmpty() == true
                                editSearch.setText("")
                                buildSubcategoryChips(categoryId)
                                loadItems(categoryId)
                            },
                            context = this,
                            onDataChanged = {
                                loadCategories()
                            }
                        )

                    if (selectedCategoryId != null) {
                        buildSubcategoryChips(selectedCategoryId!!)
                        loadItems(selectedCategoryId!!)
                    } else if (editSearch.text.toString().trim().isEmpty()) {
                        showEmptyState()
                    }
                }
        }
    }

    // =========================================================
    // SUBCATEGORY CHIPS
    // =========================================================

    private fun buildSubcategoryChips(categoryId: String) {
        subcategoryChipsRow.removeAllViews()
        val subs = allSubcategories.filter { it.categoryId == categoryId }.sortedBy { it.order }

        if (subs.isEmpty()) {
            subcategoryChipsScroll.visibility = View.GONE
            return
        }

        subcategoryChipsScroll.visibility = View.VISIBLE

        fun chipBackground(active: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = 50f
                if (active) {
                    setColor(Color.parseColor("#6366F1"))
                } else {
                    setColor(Color.parseColor("#F1F5F9"))
                    setStroke(2, Color.parseColor("#E2E8F0"))
                }
            }
        }

        fun addChip(
            label: String, active: Boolean,
            onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
        ) {
            val tv = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (active) Color.WHITE else Color.parseColor("#334155"))
                background = chipBackground(active)
                setPadding(36, 16, 36, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                if (onLongClick != null) {
                    setOnLongClickListener { onLongClick(); true }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            subcategoryChipsRow.addView(tv, lp)
        }

        addChip("All", selectedSubcategoryId == null, onClick = {
            selectedSubcategoryId = null
            buildSubcategoryChips(categoryId)
            loadItems(categoryId)
        })

        for (sub in subs) {
            // Subcategory name only on the chip; kitchen routing label is set via long-press and
            // used for printing without cluttering the filter UI.
            val chipLabel = sub.name
            addChip(chipLabel, selectedSubcategoryId == sub.id, onClick = {
                selectedSubcategoryId = sub.id
                buildSubcategoryChips(categoryId)
                loadItems(categoryId)
            }, onLongClick = {
                showSubcategoryKitchenLabelPicker(sub, categoryId)
            })
        }
    }

    private fun showSubcategoryKitchenLabelPicker(sub: SubcategoryModel, categoryId: String) {
        val available = KitchenRoutingLabelsFirestore
            .labelsForItemAssignmentPicker(this, listOfNotNull(sub.kitchenLabel.takeIf { it.isNotEmpty() }))
        if (available.isEmpty()) {
            Toast.makeText(this, "No kitchen labels configured on any printer", Toast.LENGTH_SHORT).show()
            return
        }
        val current = PrinterLabelKey.normalize(sub.kitchenLabel)
        val names = arrayOf("None") + available.toTypedArray()
        val checkedIndex = if (current.isEmpty()) 0
            else available.indexOfFirst { PrinterLabelKey.normalize(it) == current } + 1

        AlertDialog.Builder(this)
            .setTitle("Kitchen Label \u2014 ${sub.name}")
            .setSingleChoiceItems(names, checkedIndex.coerceAtLeast(0), null)
            .setPositiveButton("Save") { dialog, _ ->
                val lv = (dialog as AlertDialog).listView
                val selected = lv.checkedItemPosition
                val label = if (selected <= 0) "" else available[selected - 1]
                val updates: Map<String, Any> = if (label.isEmpty()) {
                    mapOf("kitchenLabel" to com.google.firebase.firestore.FieldValue.delete())
                } else {
                    mapOf("kitchenLabel" to label)
                }
                db.collection("subcategories").document(sub.id).update(updates)
                    .addOnSuccessListener {
                        Toast.makeText(this,
                            if (label.isEmpty()) "Kitchen label removed" else "Label set to \"$label\"",
                            Toast.LENGTH_SHORT).show()
                        loadCategories()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================
    // LOAD ITEMS
    // =========================================================

    private fun loadAllItemsForSearch(query: String) {
        db.collection("MenuItems")
            .get()
            .addOnSuccessListener { documents ->
                val itemList = mutableListOf<ItemModel>()
                val q = query.lowercase()

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    if (!name.lowercase().contains(q)) continue

                    @Suppress("UNCHECKED_CAST")
                    val pricingRaw = doc.get("pricing") as? Map<String, Any>
                    val pricingObj = if (pricingRaw != null) Pricing(
                        pos = (pricingRaw["pos"] as? Number)?.toDouble(),
                        online = (pricingRaw["online"] as? Number)?.toDouble()
                    ) else null

                    @Suppress("UNCHECKED_CAST")
                    val channelsRaw = doc.get("channels") as? Map<String, Any>
                    val channelsObj = if (channelsRaw != null) Channels(
                        pos = channelsRaw["pos"] as? Boolean ?: true,
                        online = channelsRaw["online"] as? Boolean ?: false
                    ) else null

                    @Suppress("UNCHECKED_CAST")
                    val menuIdsRaw = doc.get("menuIds") as? List<String>

                    @Suppress("UNCHECKED_CAST")
                    val pricesRaw = doc.get("prices") as? Map<String, Any>
                    val pricesMap = pricesRaw?.mapValues {
                        (it.value as? Number)?.toDouble() ?: 0.0
                    } ?: emptyMap()
                    val legacyPrice = doc.getDouble("price")
                    val effectivePrices = if (pricesMap.isNotEmpty()) pricesMap
                        else mapOf("default" to (legacyPrice ?: 0.0))
                    val displayPrice = pricingObj?.pos
                        ?: effectivePrices.values.firstOrNull() ?: 0.0

                    val stock = doc.getLong("stock") ?: 0L
                    @Suppress("UNCHECKED_CAST")
                    val availableOrderTypes = doc.get("availableOrderTypes") as? List<String>
                    val isScheduled = doc.getBoolean("isScheduled") ?: false
                    @Suppress("UNCHECKED_CAST")
                    val scheduleIds = (doc.get("scheduleIds") as? List<String>) ?: emptyList()

                    itemList.add(
                        ItemModel(
                            id = doc.id,
                            name = name,
                            price = displayPrice,
                            prices = effectivePrices,
                            stock = stock,
                            categoryId = doc.getString("categoryId") ?: "",
                            availableOrderTypes = availableOrderTypes,
                            isScheduled = isScheduled,
                            scheduleIds = scheduleIds,
                            menuId = doc.getString("menuId"),
                            pricing = pricingObj,
                            menuIds = menuIdsRaw,
                            channels = channelsObj,
                            subcategoryId = doc.getString("subcategoryId") ?: "",
                            printerLabel = MenuItemRoutingLabel.fromMenuItemDoc(doc),
                        )
                    )
                }

                itemList.sortBy { it.name.lowercase() }
                txtItemCount.text = "${itemList.size} item${if (itemList.size != 1) "s" else ""}"

                val adapter = ItemAdapter(
                    context = this,
                    itemList = itemList,
                    categoryAvailabilityMap = categoryAvailabilityMap,
                    stockCountingEnabled = stockCountingEnabled,
                    subcategories = allSubcategories,
                    refresh = { loadAllItemsForSearch(query) }
                )
                currentAdapter = adapter
                itemRecycler.adapter = adapter
            }
    }

    private fun showEmptyState() {
        txtItemCount.text = "Select a category or search for an item"
        subcategoryChipsScroll.visibility = View.GONE
        currentAdapter = null
        itemRecycler.adapter = null
    }

    private fun subcategoryIdForCategory(doc: com.google.firebase.firestore.DocumentSnapshot, categoryId: String): String {
        @Suppress("UNCHECKED_CAST")
        val byCat = doc.get("subcategoryByCategoryId") as? Map<*, *>
        if (byCat != null) {
            val v = byCat[categoryId] as? String
            if (v != null) return v
        }
        return doc.getString("subcategoryId") ?: ""
    }

    private fun loadItems(categoryId: String) {
        db.collection("MenuItems")
            .where(
                Filter.or(
                    Filter.equalTo("categoryId", categoryId),
                    Filter.arrayContains("categoryIds", categoryId),
                ),
            )
            .get()
            .addOnSuccessListener { documents ->
                val itemList = mutableListOf<ItemModel>()

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue

                    val subcategoryId = subcategoryIdForCategory(doc, categoryId)
                    if (selectedSubcategoryId != null && subcategoryId != selectedSubcategoryId) continue

                    @Suppress("UNCHECKED_CAST")
                    val pricingRaw = doc.get("pricing") as? Map<String, Any>
                    val pricingObj = if (pricingRaw != null) Pricing(
                        pos = (pricingRaw["pos"] as? Number)?.toDouble(),
                        online = (pricingRaw["online"] as? Number)?.toDouble()
                    ) else null

                    @Suppress("UNCHECKED_CAST")
                    val channelsRaw = doc.get("channels") as? Map<String, Any>
                    val channelsObj = if (channelsRaw != null) Channels(
                        pos = channelsRaw["pos"] as? Boolean ?: true,
                        online = channelsRaw["online"] as? Boolean ?: false
                    ) else null

                    @Suppress("UNCHECKED_CAST")
                    val menuIdsRaw = doc.get("menuIds") as? List<String>

                    @Suppress("UNCHECKED_CAST")
                    val pricesRaw = doc.get("prices") as? Map<String, Any>
                    val pricesMap = pricesRaw?.mapValues {
                        (it.value as? Number)?.toDouble() ?: 0.0
                    } ?: emptyMap()
                    val legacyPrice = doc.getDouble("price")
                    val effectivePrices = if (pricesMap.isNotEmpty()) pricesMap
                        else mapOf("default" to (legacyPrice ?: 0.0))
                    val displayPrice = pricingObj?.pos
                        ?: effectivePrices.values.firstOrNull() ?: 0.0

                    val stock = doc.getLong("stock") ?: 0L
                    @Suppress("UNCHECKED_CAST")
                    val availableOrderTypes =
                        doc.get("availableOrderTypes") as? List<String>
                    val isScheduled = doc.getBoolean("isScheduled") ?: false
                    @Suppress("UNCHECKED_CAST")
                    val scheduleIds =
                        (doc.get("scheduleIds") as? List<String>) ?: emptyList()

                    itemList.add(
                        ItemModel(
                            id = doc.id,
                            name = name,
                            price = displayPrice,
                            prices = effectivePrices,
                            stock = stock,
                            categoryId = doc.getString("categoryId") ?: "",
                            availableOrderTypes = availableOrderTypes,
                            isScheduled = isScheduled,
                            scheduleIds = scheduleIds,
                            menuId = doc.getString("menuId"),
                            pricing = pricingObj,
                            menuIds = menuIdsRaw,
                            channels = channelsObj,
                            subcategoryId = subcategoryId,
                            printerLabel = MenuItemRoutingLabel.fromMenuItemDoc(doc),
                        )
                    )
                }

                itemList.sortBy { it.name.lowercase() }

                val count = itemList.size
                txtItemCount.text = "$count item${if (count != 1) "s" else ""}"

                val adapter = ItemAdapter(
                    context = this,
                    itemList = itemList,
                    categoryAvailabilityMap = categoryAvailabilityMap,
                    stockCountingEnabled = stockCountingEnabled,
                    subcategories = allSubcategories,
                    refresh = { loadItems(categoryId) }
                )
                currentAdapter = adapter
                itemRecycler.adapter = adapter
            }
    }

    // =========================================================
    // ADD CATEGORY
    // =========================================================

    private fun showAddCategoryDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val nameLabel = TextView(this)
        nameLabel.text = "Category Name"
        nameLabel.textSize = 13f
        nameLabel.setTextColor(Color.DKGRAY)
        layout.addView(nameLabel)

        val input = EditText(this)
        input.hint = "Category name"
        layout.addView(input)

        val availLabel = TextView(this)
        availLabel.text = "Available In Order Types"
        availLabel.textSize = 13f
        availLabel.setTextColor(Color.DKGRAY)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 32
        availLabel.layoutParams = params
        layout.addView(availLabel)

        val checkBoxes = mutableMapOf<String, CheckBox>()
        for (orderType in CategoryAdapter.ALL_ORDER_TYPES) {
            val cb = CheckBox(this)
            cb.text = when (orderType) {
                "BAR_TAB" -> "BAR"
                "TO_GO" -> "TO GO"
                "DINE_IN" -> "DINE IN"
                else -> orderType
            }
            cb.isChecked = true
            layout.addView(cb)
            checkBoxes[orderType] = cb
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(layout)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false

            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    val name = text.toString().trim()

                    if (name.isEmpty()) {
                        input.error = null
                        addButton.isEnabled = false
                        return
                    }

                    checkCategoryExists(name) { exists ->
                        if (exists) {
                            input.error = "Category already exists"
                            addButton.isEnabled = false
                        } else {
                            input.error = null
                            addButton.isEnabled = true
                        }
                    }
                }
            })

            addButton.setOnClickListener {
                val name = input.text.toString().trim()
                val selectedTypes = checkBoxes.filter { it.value.isChecked }.keys.toList()
                saveCategory(name, selectedTypes)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun checkCategoryExists(name: String, callback: (Boolean) -> Unit) {
        val wanted = CategoryNameUtils.normalizeCategoryName(name)

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->
                var exists = false
                for (doc in documents) {
                    val key = CategoryNameUtils.normalizedKeyForDocument(
                        doc.getString("name"),
                        doc.getString("normalizedName")
                    )
                    if (key == wanted) {
                        exists = true
                        break
                    }
                }
                callback(exists)
            }
    }

    private fun saveCategory(name: String, availableOrderTypes: List<String>) {
        val wanted = CategoryNameUtils.normalizeCategoryName(name)

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->
                var exists = false
                for (doc in documents) {
                    val key = CategoryNameUtils.normalizedKeyForDocument(
                        doc.getString("name"),
                        doc.getString("normalizedName")
                    )
                    if (key == wanted) {
                        exists = true
                        break
                    }
                }

                if (exists) {
                    Toast.makeText(this, "Category already exists", Toast.LENGTH_SHORT).show()
                } else {
                    val category = hashMapOf(
                        "name" to name.trim(),
                        "normalizedName" to wanted,
                        "availableOrderTypes" to availableOrderTypes
                    )

                    db.collection("Categories")
                        .add(category)
                        .addOnSuccessListener {
                            loadCategories()
                            Toast.makeText(this, "Category added", Toast.LENGTH_SHORT).show()
                        }
                }
            }
    }

    // =========================================================
    // ADD ITEM
    // =========================================================

    private fun showAddItemDialog() {
        addItemDialogOpen = true
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        fun createSmallLabel(text: String): TextView {
            val tv = TextView(this)
            tv.text = text
            tv.textSize = 13f
            tv.setTextColor(Color.DKGRAY)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            tv.layoutParams = lp
            return tv
        }

        fun createDivider(): View {
            val d = View(this)
            d.setBackgroundColor(Color.LTGRAY)
            d.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 24; bottomMargin = 16 }
            return d
        }

        fun createOptionRow(label: String, count: Int, onClick: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 28, 8, 28)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            val labelTv = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(labelTv)
            if (count > 0) {
                val badge = TextView(this).apply {
                    text = "$count selected"
                    textSize = 12f
                    setTextColor(Color.parseColor("#6366F1"))
                    setPadding(16, 4, 16, 4)
                    background = GradientDrawable().apply {
                        cornerRadius = 20f
                        setColor(Color.parseColor("#EEF2FF"))
                    }
                }
                row.addView(badge)
            }
            val arrow = TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(16, 0, 0, 0)
            }
            row.addView(arrow)
            return row
        }

        fun createOptionRowWithBadge(label: String, badgeText: String?, onClick: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 28, 8, 28)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.parseColor("#1E293B"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            val bt = badgeText?.trim()
            if (!bt.isNullOrEmpty()) {
                row.addView(
                    TextView(this).apply {
                        text = bt
                        textSize = 12f
                        setTextColor(Color.parseColor("#6366F1"))
                        setPadding(16, 4, 16, 4)
                        background = GradientDrawable().apply {
                            cornerRadius = 20f
                            setColor(Color.parseColor("#EEF2FF"))
                        }
                    },
                )
            }
            row.addView(
                TextView(this).apply {
                    text = "›"
                    textSize = 20f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(16, 0, 0, 0)
                },
            )
            return row
        }

        val nameInput = EditText(this).apply {
            hint = "Item name"
            setText(pendingName)
        }
        layout.addView(nameInput)

        val priceInput = EditText(this).apply {
            hint = "Price"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(pendingPrice)
        }
        layout.addView(priceInput)

        val stockInput = EditText(this).apply {
            hint = "Stock"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(pendingStock)
        }
        if (stockCountingEnabled) {
            layout.addView(stockInput)
        }

        val catSubs = allSubcategories.filter { it.categoryId == selectedCategoryId }
        var selectedSubId = pendingSubId
        if (catSubs.isNotEmpty()) {
            layout.addView(createSmallLabel("Subcategory"))
            val subSpinner = android.widget.Spinner(this)
            val subNames = listOf("None") + catSubs.map { it.name }
            subSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, subNames)
            val preIndex = if (pendingSubId.isNotEmpty()) {
                val idx = catSubs.indexOfFirst { it.id == pendingSubId }
                if (idx >= 0) idx + 1 else 0
            } else 0
            subSpinner.setSelection(preIndex)
            subSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    selectedSubId = if (pos == 0) "" else catSubs[pos - 1].id
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            layout.addView(subSpinner)
        }

        layout.addView(createDivider())

        layout.addView(
            createOptionRowWithBadge(
                getString(R.string.assign_printer_label_menu),
                pendingPrinterLabel.trim().takeIf { it.isNotEmpty() },
            ) {
                pendingName = nameInput.text.toString()
                pendingPrice = priceInput.text.toString()
                pendingStock = stockInput.text.toString()
                pendingSubId = selectedSubId
                val intent = Intent(this, SelectPrinterLabelActivity::class.java)
                intent.putExtra(SelectPrinterLabelActivity.EXTRA_CURRENT_LABEL, pendingPrinterLabel)
                printerLabelLauncher.launch(intent)
            },
        )
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.menu_item_printer_label_hint)
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(8, 0, 8, 8)
            },
        )

        layout.addView(createDivider())

        layout.addView(createOptionRow("Assign Modifiers", pendingModifierIds.size) {
            pendingName = nameInput.text.toString()
            pendingPrice = priceInput.text.toString()
            pendingStock = stockInput.text.toString()
            pendingSubId = selectedSubId
            val intent = Intent(this, SelectModifiersActivity::class.java)
            intent.putStringArrayListExtra("SELECTED_IDS", pendingModifierIds)
            modifierLauncher.launch(intent)
        })

        layout.addView(createOptionRow("Assign Taxes", pendingTaxIds.size) {
            pendingName = nameInput.text.toString()
            pendingPrice = priceInput.text.toString()
            pendingStock = stockInput.text.toString()
            pendingSubId = selectedSubId
            val intent = Intent(this, SelectTaxesActivity::class.java)
            intent.putStringArrayListExtra("SELECTED_IDS", pendingTaxIds)
            taxLauncher.launch(intent)
        })

        layout.addView(createOptionRow("Assign KDS", pendingKdsDeviceIds.size) {
            pendingName = nameInput.text.toString()
            pendingPrice = priceInput.text.toString()
            pendingStock = stockInput.text.toString()
            pendingSubId = selectedSubId
            openKdsPickerFromAddDialog()
        })

        layout.addView(createDivider())

        val useCategorySwitch = Switch(this)
        useCategorySwitch.text = "Use Category Availability"
        useCategorySwitch.isChecked = pendingUseCategoryAvail
        layout.addView(useCategorySwitch)

        val checkBoxContainer = LinearLayout(this)
        checkBoxContainer.orientation = LinearLayout.VERTICAL
        checkBoxContainer.setPadding(0, 16, 0, 0)
        checkBoxContainer.visibility = if (pendingUseCategoryAvail) View.GONE else View.VISIBLE

        val availLabel = TextView(this)
        availLabel.text = "Custom Availability"
        availLabel.textSize = 13f
        availLabel.setTextColor(Color.DKGRAY)
        checkBoxContainer.addView(availLabel)

        val checkBoxes = mutableMapOf<String, CheckBox>()
        for (orderType in CategoryAdapter.ALL_ORDER_TYPES) {
            val cb = CheckBox(this)
            cb.text = when (orderType) {
                "BAR_TAB" -> "BAR"
                "TO_GO" -> "TO GO"
                "DINE_IN" -> "DINE IN"
                else -> orderType
            }
            cb.isChecked = pendingOrderTypes.isEmpty() || pendingOrderTypes.contains(orderType)
            checkBoxContainer.addView(cb)
            checkBoxes[orderType] = cb
        }

        layout.addView(checkBoxContainer)

        useCategorySwitch.setOnCheckedChangeListener { _, isChecked ->
            checkBoxContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        val scrollView = android.widget.ScrollView(this)
        scrollView.addView(layout)

        val addDialog = AlertDialog.Builder(this)
            .setTitle("Add Item")
            .setView(scrollView)
            .setPositiveButton("Add") { _, _ ->
                addItemDialogOpen = false
                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull()
                val stock = if (stockCountingEnabled)
                    stockInput.text.toString().toLongOrNull() ?: 0L
                else
                    9999L

                if (name.isEmpty()) {
                    Toast.makeText(this, "Please enter item name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (price == null || price < 0) {
                    Toast.makeText(this, "Enter a valid price", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (selectedCategoryId == null) {
                    Toast.makeText(this, "No category selected", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val item = hashMapOf<String, Any>(
                    "name" to name,
                    "price" to price,
                    "pricing" to hashMapOf("pos" to price),
                    "channels" to hashMapOf("pos" to true),
                    "stock" to stock,
                    "categoryId" to selectedCategoryId!!,
                    "subcategoryId" to selectedSubId,
                    "taxIds" to pendingTaxIds.toList(),
                    "modifierGroupIds" to pendingModifierIds.toList(),
                    "isScheduled" to false,
                    "scheduleIds" to emptyList<String>(),
                )

                val pl = pendingPrinterLabel.trim().takeIf { it.isNotEmpty() }
                if (!pl.isNullOrEmpty()) {
                    item["printerLabel"] = pl
                }

                if (!useCategorySwitch.isChecked) {
                    val selectedTypes =
                        checkBoxes.filter { it.value.isChecked }.keys.toList()
                    item["availableOrderTypes"] = selectedTypes
                }

                db.collection("MenuItems")
                    .add(item)
                    .addOnSuccessListener { docRef ->
                        val categoryId = selectedCategoryId!!
                        val kdsDeviceIds = pendingKdsDeviceIds.toSet()
                        val kdsTouched = pendingKdsTouched
                        val runAfterModifiers = {
                            pl?.let {
                                KitchenRoutingLabelsFirestore.mergeLabelsIntoFirestore(db, listOf(it))
                            }
                            val finishAfterKds = {
                                clearPendingForm()
                                loadItems(categoryId)
                                Toast.makeText(
                                    this,
                                    getString(R.string.item_added_short, name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            if (kdsTouched && kdsDeviceIds.isNotEmpty()) {
                                KdsMenuItemStationPicker.applyNewItemAssignments(
                                    db,
                                    docRef.id,
                                    kdsDeviceIds,
                                ) { ok, err ->
                                    if (!ok) {
                                        Toast.makeText(
                                            this,
                                            err ?: "Failed to assign KDS",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    finishAfterKds()
                                }
                            } else {
                                finishAfterKds()
                            }
                        }
                        if (pendingModifierIds.isNotEmpty()) {
                            val batch = db.batch()
                            pendingModifierIds.forEachIndexed { index, groupId ->
                                val linkRef = db.collection("ItemModifierGroups").document()
                                batch.set(linkRef, hashMapOf(
                                    "itemId" to docRef.id,
                                    "groupId" to groupId,
                                    "displayOrder" to index + 1
                                ))
                            }
                            batch.commit()
                                .addOnSuccessListener { runAfterModifiers() }
                                .addOnFailureListener { e ->
                                    Toast.makeText(
                                        this,
                                        e.message ?: getString(R.string.item_detail_load_failed),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    runAfterModifiers()
                                }
                        } else {
                            runAfterModifiers()
                        }
                    }
            }
            .setNegativeButton("Cancel") { _, _ ->
                addItemDialogOpen = false
                clearPendingForm()
            }
            .setOnCancelListener {
                addItemDialogOpen = false
                clearPendingForm()
            }
            .create()
        addDialog.show()
        currentAddItemDialog = addDialog
    }

    private fun clearPendingForm() {
        pendingName = ""
        pendingPrice = ""
        pendingStock = ""
        pendingSubId = ""
        pendingModifierIds = arrayListOf()
        pendingTaxIds = arrayListOf()
        pendingUseCategoryAvail = true
        pendingOrderTypes = emptyList()
        pendingPrinterLabel = ""
        pendingKdsDeviceIds = arrayListOf()
        pendingKdsTouched = false
    }

    private fun openKdsPickerFromAddDialog() {
        val catId = selectedCategoryId ?: run {
            Toast.makeText(this, "No category selected", Toast.LENGTH_SHORT).show()
            return
        }
        currentAddItemDialog?.dismiss()
        currentAddItemDialog = null
        val currentSelection: Set<String>? =
            if (pendingKdsTouched) pendingKdsDeviceIds.toSet() else null
        KdsMenuItemStationPicker.pickForNewItem(
            activity = this,
            categoryId = catId,
            currentSelection = currentSelection,
            db = db,
            onSelected = { deviceIds ->
                pendingKdsDeviceIds = ArrayList(deviceIds)
                pendingKdsTouched = true
                if (addItemDialogOpen) showAddItemDialog()
            },
            onCancelled = {
                if (addItemDialogOpen) showAddItemDialog()
            },
        )
    }

}
