package com.ernesto.myapplication

import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale

class AddDiscountActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private var discountId: String? = null
    private lateinit var edtName: EditText
    private lateinit var radioType: RadioGroup
    private lateinit var edtValue: EditText
    private lateinit var radioApplyTo: RadioGroup
    private lateinit var switchActive: SwitchCompat
    private lateinit var switchAutoApply: SwitchCompat
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var itemSelectorContainer: View
    private lateinit var txtSelectedItems: TextView
    private lateinit var btnSelectItems: Button
    private lateinit var daysContainer: LinearLayout
    private lateinit var txtStartTime: TextView
    private lateinit var txtEndTime: TextView

    private val selectedDays = mutableSetOf<String>()
    private val dayButtons = mutableMapOf<String, TextView>()
    private var startTime = ""
    private var endTime = ""
    private var selectedItemIds = mutableListOf<String>()
    private var selectedItemNames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_discount)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        edtName = findViewById(R.id.edtDiscountName)
        radioType = findViewById(R.id.radioDiscountType)
        edtValue = findViewById(R.id.edtDiscountValue)
        radioApplyTo = findViewById(R.id.radioDiscountApplyTo)
        switchActive = findViewById(R.id.switchDiscountActive)
        switchAutoApply = findViewById(R.id.switchAutoApply)
        btnSave = findViewById(R.id.btnSaveDiscount)
        btnDelete = findViewById(R.id.btnDeleteDiscount)
        itemSelectorContainer = findViewById(R.id.itemSelectorContainer)
        txtSelectedItems = findViewById(R.id.txtSelectedItems)
        btnSelectItems = findViewById(R.id.btnSelectItems)
        daysContainer = findViewById(R.id.daysContainer)
        txtStartTime = findViewById(R.id.txtStartTime)
        txtEndTime = findViewById(R.id.txtEndTime)

        setupDaySelector()
        setupTimePickers()
        setupApplyScopeListener()

        btnSelectItems.setOnClickListener { showItemSelectorDialog() }

        discountId = intent.getStringExtra("DISCOUNT_ID")
        if (discountId != null) {
            supportActionBar?.title = "Edit discount"
            populateFromIntent()
            btnSave.text = "Update"
            btnDelete.visibility = View.VISIBLE
        } else {
            supportActionBar?.title = "Add discount"
            btnDelete.visibility = View.GONE
        }

        btnSave.setOnClickListener { saveDiscount() }
        btnDelete.setOnClickListener { confirmAndDelete() }
    }

    private fun populateFromIntent() {
        edtName.setText(intent.getStringExtra("DISCOUNT_NAME") ?: "")
        val type = intent.getStringExtra("DISCOUNT_TYPE") ?: "PERCENTAGE"
        radioType.check(if (type == "FIXED") R.id.radioDiscountFixed else R.id.radioDiscountPercentage)
        val value = intent.getDoubleExtra("DISCOUNT_VALUE", 0.0)
        edtValue.setText(String.format(Locale.US, "%.2f", value))

        val applyScope = intent.getStringExtra("DISCOUNT_APPLY_SCOPE") ?: "order"
        when (applyScope) {
            "item" -> radioApplyTo.check(R.id.radioApplyItem)
            "manual" -> radioApplyTo.check(R.id.radioApplyManual)
            else -> radioApplyTo.check(R.id.radioApplyOrder)
        }
        updateItemSelectorVisibility()

        switchActive.isChecked = intent.getBooleanExtra("DISCOUNT_ACTIVE", true)
        switchAutoApply.isChecked = intent.getBooleanExtra("DISCOUNT_AUTO_APPLY", true)

        val days = intent.getStringArrayListExtra("DISCOUNT_DAYS")
        days?.forEach { day ->
            selectedDays.add(day)
            dayButtons[day]?.let { updateDayButtonState(it, day, true) }
        }

        startTime = intent.getStringExtra("DISCOUNT_START_TIME") ?: ""
        endTime = intent.getStringExtra("DISCOUNT_END_TIME") ?: ""
        txtStartTime.text = if (startTime.isNotBlank()) startTime else "--:--"
        txtEndTime.text = if (endTime.isNotBlank()) endTime else "--:--"

        val itemIds = intent.getStringArrayListExtra("DISCOUNT_ITEM_IDS")
        val itemNames = intent.getStringArrayListExtra("DISCOUNT_ITEM_NAMES")
        if (!itemIds.isNullOrEmpty()) {
            selectedItemIds = itemIds.toMutableList()
            selectedItemNames = itemNames?.toMutableList() ?: mutableListOf()
            updateSelectedItemsDisplay()
        }
    }

    private fun setupDaySelector() {
        val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        val labels = listOf("M", "T", "W", "T", "F", "S", "S")

        for (i in days.indices) {
            val tv = TextView(this).apply {
                text = labels[i]
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(4, 0, 4, 0)
                }
                setTextColor(Color.parseColor("#555555"))
                background = createCircleBg(Color.parseColor("#E8E8E8"))
            }

            tv.setOnClickListener {
                val day = days[i]
                val isSelected = selectedDays.contains(day)
                if (isSelected) selectedDays.remove(day) else selectedDays.add(day)
                updateDayButtonState(tv, day, !isSelected)
            }

            dayButtons[days[i]] = tv
            daysContainer.addView(tv)
        }
    }

    private fun updateDayButtonState(tv: TextView, day: String, selected: Boolean) {
        if (selected) {
            tv.setTextColor(Color.WHITE)
            tv.background = createCircleBg(Color.parseColor("#6A4FB3"))
        } else {
            tv.setTextColor(Color.parseColor("#555555"))
            tv.background = createCircleBg(Color.parseColor("#E8E8E8"))
        }
    }

    private fun createCircleBg(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun setupTimePickers() {
        txtStartTime.setOnClickListener {
            showTimePicker("Start Time") { time ->
                startTime = time
                txtStartTime.text = time
            }
        }
        txtEndTime.setOnClickListener {
            showTimePicker("End Time") { time ->
                endTime = time
                txtEndTime.text = time
            }
        }
    }

    private fun showTimePicker(title: String, onTimeSelected: (String) -> Unit) {
        val hour = 12
        val minute = 0
        TimePickerDialog(this, { _, h, m ->
            onTimeSelected(String.format(Locale.US, "%02d:%02d", h, m))
        }, hour, minute, true).apply {
            setTitle(title)
            show()
        }
    }

    private fun setupApplyScopeListener() {
        radioApplyTo.setOnCheckedChangeListener { _, _ -> updateItemSelectorVisibility() }
    }

    private fun updateItemSelectorVisibility() {
        itemSelectorContainer.visibility =
            if (radioApplyTo.checkedRadioButtonId == R.id.radioApplyItem) View.VISIBLE else View.GONE
    }

    private fun showItemSelectorDialog() {
        db.collection("MenuItems").get()
            .addOnSuccessListener { snap ->
                val items = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    doc.id to name
                }
                if (items.isEmpty()) {
                    Toast.makeText(this, "No menu items found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val names = items.map { it.second }.toTypedArray()
                val ids = items.map { it.first }
                val checked = BooleanArray(items.size) { ids[it] in selectedItemIds }

                AlertDialog.Builder(this)
                    .setTitle("Select Items")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("Done") { _, _ ->
                        selectedItemIds.clear()
                        selectedItemNames.clear()
                        for (i in items.indices) {
                            if (checked[i]) {
                                selectedItemIds.add(ids[i])
                                selectedItemNames.add(names[i])
                            }
                        }
                        updateSelectedItemsDisplay()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }

    private fun updateSelectedItemsDisplay() {
        txtSelectedItems.text = if (selectedItemNames.isEmpty()) {
            "No items selected"
        } else {
            selectedItemNames.joinToString(", ")
        }
    }

    private fun saveDiscount() {
        val name = edtName.text.toString().trim()
        val valueStr = edtValue.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return
        }
        if (valueStr.isBlank()) {
            Toast.makeText(this, "Enter a value", Toast.LENGTH_SHORT).show()
            return
        }

        val value = valueStr.toDoubleOrNull()
        if (value == null || value <= 0) {
            Toast.makeText(this, "Enter a valid value", Toast.LENGTH_SHORT).show()
            return
        }

        val isFixed = radioType.checkedRadioButtonId == R.id.radioDiscountFixed
        val type = if (isFixed) "FIXED" else "PERCENTAGE"

        if (!isFixed && value > 100) {
            Toast.makeText(this, "Percentage cannot exceed 100", Toast.LENGTH_SHORT).show()
            return
        }

        val applyScope = when (radioApplyTo.checkedRadioButtonId) {
            R.id.radioApplyItem -> "item"
            R.id.radioApplyManual -> "manual"
            else -> "order"
        }

        if (applyScope == "item" && selectedItemIds.isEmpty()) {
            Toast.makeText(this, "Select at least one item", Toast.LENGTH_SHORT).show()
            return
        }

        val active = switchActive.isChecked
        val autoApply = switchAutoApply.isChecked

        val applyTo = when (applyScope) {
            "item" -> "ITEM"
            else -> "ORDER"
        }

        val data = hashMapOf<String, Any>(
            "name" to name,
            "type" to type,
            "value" to value,
            "applyTo" to applyTo,
            "applyScope" to applyScope,
            "active" to active,
            "autoApply" to autoApply
        )

        if (applyScope == "item") {
            data["itemIds"] = selectedItemIds.toList()
            data["itemNames"] = selectedItemNames.toList()
        } else {
            data["itemIds"] = emptyList<String>()
            data["itemNames"] = emptyList<String>()
        }

        val scheduleMap = hashMapOf<String, Any>(
            "days" to selectedDays.toList(),
            "startTime" to startTime,
            "endTime" to endTime
        )
        data["schedule"] = scheduleMap

        if (discountId == null) {
            data["createdAt"] = Date()
        } else {
            data["updatedAt"] = Date()
        }

        if (discountId != null) {
            db.collection("discounts").document(discountId!!)
                .update(data as Map<String, Any>)
                .addOnSuccessListener {
                    Toast.makeText(this, "Discount updated", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            db.collection("discounts")
                .add(data)
                .addOnSuccessListener {
                    Toast.makeText(this, "Discount saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun confirmAndDelete() {
        val id = discountId ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove discount")
            .setMessage("Remove this discount?")
            .setPositiveButton("Remove") { _, _ ->
                db.collection("discounts").document(id)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Discount removed", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to remove: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
