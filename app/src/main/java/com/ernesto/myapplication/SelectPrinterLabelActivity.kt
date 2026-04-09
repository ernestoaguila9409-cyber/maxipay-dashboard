package com.ernesto.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Single-select kitchen routing label. Optional [EXTRA_ITEM_ID]: saves directly to [MenuItems];
 * otherwise returns [RESULT_SELECTED_LABEL] (empty = none).
 */
class SelectPrinterLabelActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var radioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)?.trim().orEmpty()
        val currentRaw = intent.getStringExtra(EXTRA_CURRENT_LABEL)?.trim().orEmpty()
        val currentNorm = PrinterLabelKey.normalize(currentRaw)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 2f
            setPadding(40, 0, 40, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 112,
            )
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.assign_printer_label_title)
                textSize = 18f
                setTextColor(Color.parseColor("#1E293B"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 100)
        }
        radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        body.addView(radioGroup)
        scroll.addView(body)
        root.addView(scroll)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 4f
            setPadding(24, 16, 24, 16)
        }
        bottomBar.addView(
            TextView(this).apply {
                text = getString(R.string.save)
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(48, 24, 48, 24)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(Color.parseColor("#6366F1"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { save(itemId) }
            },
        )
        root.addView(bottomBar)
        setContentView(root)

        val assigned = if (currentRaw.isEmpty()) emptyList() else listOf(currentRaw)
        val merged = KitchenRoutingLabelsFirestore.labelsForItemAssignmentPicker(this, assigned)
        buildRadios(merged, currentNorm)
    }

    private fun buildRadios(labels: List<String>, currentNorm: String) {
        radioGroup.removeAllViews()
        fun addRb(text: String, tagLabel: String?) {
            val rb = RadioButton(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
                setPadding(32, 28, 32, 28)
                tag = tagLabel
            }
            radioGroup.addView(rb)
        }

        addRb(getString(R.string.menu_item_printer_label_none), null)
        for (label in labels) {
            addRb(label, label)
        }

        val matchIdx = labels.indexOfFirst { PrinterLabelKey.normalize(it) == currentNorm }
        val checkIdx = if (matchIdx >= 0) matchIdx + 1 else 0
        (radioGroup.getChildAt(checkIdx) as? RadioButton)?.isChecked = true
    }

    private fun save(itemId: String) {
        if (radioGroup.childCount == 0) return
        val checkedId = radioGroup.checkedRadioButtonId
        val rb = radioGroup.findViewById<RadioButton>(checkedId)
        val selected = rb?.tag as? String

        if (itemId.isNotEmpty()) {
            val updates = mutableMapOf<String, Any>()
            if (selected.isNullOrBlank()) {
                updates["printerLabel"] = FieldValue.delete()
            } else {
                updates["printerLabel"] = selected.trim()
                KitchenRoutingLabelsFirestore.mergeLabelsIntoFirestore(db, listOf(selected.trim()))
            }
            db.collection("MenuItems").document(itemId)
                .update(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, R.string.assign_printer_label_saved, Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, e.message ?: "Update failed", Toast.LENGTH_LONG).show()
                }
        } else {
            val result = Intent().apply {
                putExtra(RESULT_SELECTED_LABEL, selected?.trim().orEmpty())
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "ITEM_ID"
        const val EXTRA_CURRENT_LABEL = "CURRENT_LABEL"
        const val RESULT_SELECTED_LABEL = "SELECTED_LABEL"
    }
}
