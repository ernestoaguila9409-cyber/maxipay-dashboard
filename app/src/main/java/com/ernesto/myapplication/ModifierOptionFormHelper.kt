package com.ernesto.myapplication

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * Shared "add / edit modifier option" form: Material inputs and expandable trigger groups
 * (selected groups sort to the top).
 */
object ModifierOptionFormHelper {

    fun interface TriggerSelection {
        fun selectedTriggerGroupIds(): List<String>
    }

    private object EmptyTriggers : TriggerSelection {
        override fun selectedTriggerGroupIds(): List<String> = emptyList()
    }

    /**
     * Inflates [R.layout.dialog_modifier_option_form], wires price visibility, and optional triggers.
     * @param initialSelected pre-checked group ids (edit mode)
     * @param startExpanded when true, triggers list starts open (e.g. existing selections)
     */
    fun inflateForm(
        context: Context,
        showPrice: Boolean,
        otherGroups: List<Triple<String, String, String>>,
        initialSelected: Set<String>,
        startExpanded: Boolean,
    ): InflatedModifierOptionForm {
        val scroll = LayoutInflater.from(context)
            .inflate(R.layout.dialog_modifier_option_form, null) as NestedScrollView
        val tilName = scroll.findViewById<TextInputLayout>(R.id.tilOptionName)
        val editName = scroll.findViewById<TextInputEditText>(R.id.editOptionName)
        val tilPrice = scroll.findViewById<TextInputLayout>(R.id.tilOptionPrice)
        val editPrice = scroll.findViewById<TextInputEditText>(R.id.editOptionPrice)
        tilPrice.visibility = if (showPrice) View.VISIBLE else View.GONE

        val triggers = bindTriggersSection(
            context,
            scroll,
            otherGroups,
            initialSelected,
            startExpanded,
        )
        return InflatedModifierOptionForm(scroll, tilName, editName, tilPrice, editPrice, triggers)
    }

    data class InflatedModifierOptionForm(
        val scrollView: NestedScrollView,
        val tilName: TextInputLayout,
        val editName: TextInputEditText,
        val tilPrice: TextInputLayout,
        val editPrice: TextInputEditText,
        val triggers: TriggerSelection,
    )

    private fun bindTriggersSection(
        context: Context,
        formRoot: View,
        otherGroups: List<Triple<String, String, String>>,
        initialSelected: Set<String>,
        startExpanded: Boolean,
    ): TriggerSelection {
        val section = formRoot.findViewById<View>(R.id.sectionTriggers)
        val header = formRoot.findViewById<View>(R.id.headerTriggers)
        val chevron = formRoot.findViewById<TextView>(R.id.chevronTriggers)
        val container = formRoot.findViewById<LinearLayout>(R.id.containerTriggers)
        if (otherGroups.isEmpty()) {
            section.visibility = View.GONE
            return EmptyTriggers
        }
        section.visibility = View.VISIBLE

        val checkById = linkedMapOf<String, CheckBox>()
        val density = context.resources.displayMetrics.density
        val padV = (6 * density).toInt()
        val padH = (4 * density).toInt()

        for ((gId, gName, gInfo) in otherGroups) {
            val cb = CheckBox(context).apply {
                text = context.getString(R.string.modifier_trigger_checkbox_line, gName, gInfo)
                textSize = 14f
                setTextColor(context.getColor(R.color.pos_primary_text))
                isChecked = initialSelected.contains(gId)
                setPadding(padH, padV, padH, padV)
            }
            CompoundButtonCompat.setButtonTintList(
                cb,
                ColorStateList.valueOf(context.getColor(R.color.brand_primary)),
            )
            checkById[gId] = cb
        }

        fun reorderRows() {
            val sorted = otherGroups.sortedWith(
                compareByDescending<Triple<String, String, String>> { checkById[it.first]?.isChecked == true }
                    .thenBy { it.second.lowercase(Locale.getDefault()) },
            )
            container.removeAllViews()
            for (t in sorted) {
                checkById[t.first]?.let { container.addView(it) }
            }
        }

        var expanded = startExpanded
        fun applyExpandedState() {
            container.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.text = if (expanded) "▲" else "▼"
        }

        for (cb in checkById.values) {
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !expanded) {
                    expanded = true
                    applyExpandedState()
                }
                reorderRows()
            }
        }

        header.setOnClickListener {
            expanded = !expanded
            applyExpandedState()
        }

        reorderRows()
        applyExpandedState()

        return TriggerSelection {
            checkById.filter { it.value.isChecked }.keys.toList()
        }
    }
}
