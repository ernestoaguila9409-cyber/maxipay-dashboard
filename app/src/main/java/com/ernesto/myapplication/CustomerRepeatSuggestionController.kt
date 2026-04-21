package com.ernesto.myapplication

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.android.material.button.MaterialButton

/**
 * Drives the "Same as usual?" card under the Cart's Customer row.
 *
 * Responsibilities:
 *  - Bind to the included layout (see `view_same_as_usual.xml`).
 *  - Load recent history when a customer is attached to the cart.
 *  - Detect a [RepeatPattern] (≥ 2 matching orders) and render only when visitCount > 2 AND a
 *    pattern exists — otherwise hide the card entirely.
 *  - Handle Repeat / View button actions via callbacks (activity-owned cart mutations).
 *
 * Reusable for Favorites and predictive recommendations: the same pattern / history pair can feed
 * other UI surfaces without duplicating detection.
 */
class CustomerRepeatSuggestionController(
    private val rootView: View,
    private val history: CustomerOrderHistoryRepository = CustomerOrderHistoryRepository(),
) {

    fun interface CartNonEmptyConfirm {
        fun askToReplace(onConfirmed: () -> Unit)
    }

    fun interface RepeatApplier {
        fun apply(pattern: RepeatPattern)
    }

    private val context: Context = rootView.context
    private val cardView: View = rootView
    private val txtSummary: TextView = rootView.findViewById(R.id.txtRepeatPatternSummary)
    private val txtTotal: TextView = rootView.findViewById(R.id.txtRepeatTotal)
    private val btnApply: MaterialButton = rootView.findViewById(R.id.btnRepeatApply)
    private val btnView: MaterialButton = rootView.findViewById(R.id.btnRepeatView)
    private val btnEdit: MaterialButton = rootView.findViewById(R.id.btnRepeatEdit)

    private var currentCustomerId: String? = null
    private var lastPattern: RepeatPattern = RepeatPattern.NONE

    private var cartIsEmptyProvider: () -> Boolean = { true }
    private var confirmReplace: CartNonEmptyConfirm = CartNonEmptyConfirm { it() }
    private var applier: RepeatApplier = RepeatApplier { }
    private var editApplier: RepeatApplier = RepeatApplier { }

    fun init(
        cartIsEmpty: () -> Boolean,
        confirmReplace: CartNonEmptyConfirm,
        applier: RepeatApplier,
        editApplier: RepeatApplier = applier,
    ) {
        this.cartIsEmptyProvider = cartIsEmpty
        this.confirmReplace = confirmReplace
        this.applier = applier
        this.editApplier = editApplier
        btnApply.setOnClickListener { onRepeatPressed() }
        btnView.setOnClickListener { onViewPressed() }
        btnEdit.setOnClickListener { onEditPressed() }
        hide()
    }

    /** Call whenever the cart's customer changes (selected, cleared, reloaded from Firestore). */
    fun onCustomerChanged(customerId: String?) {
        val id = customerId?.trim().orEmpty()
        currentCustomerId = id.ifEmpty { null }
        if (id.isEmpty()) {
            hide()
            return
        }
        hide()
        history.loadRecentHistory(
            customerId = id,
            onSuccess = { recent ->
                if (currentCustomerId != id) return@loadRecentHistory
                if (!recent.isEligibleForSuggestion) {
                    hide()
                    return@loadRecentHistory
                }
                val pattern = detectFrequentOrderPattern(recent.lastOrders)
                if (pattern.isEmpty) hide() else show(pattern)
            },
            onFailure = { hide() },
        )
    }

    private fun show(pattern: RepeatPattern) {
        lastPattern = pattern
        txtSummary.text = summarizePatternForCard(pattern)
        if (pattern.type == RepeatPatternType.FULL_ORDER && pattern.totalInCents > 0L) {
            txtTotal.text = MoneyUtils.centsToDisplay(pattern.totalInCents)
            txtTotal.visibility = View.VISIBLE
        } else {
            txtTotal.visibility = View.GONE
        }
        cardView.visibility = View.VISIBLE
    }

    private fun hide() {
        lastPattern = RepeatPattern.NONE
        cardView.visibility = View.GONE
    }

    private fun onRepeatPressed() {
        val pattern = lastPattern
        if (pattern.isEmpty) return
        if (cartIsEmptyProvider()) {
            applier.apply(pattern)
        } else {
            confirmReplace.askToReplace { applier.apply(pattern) }
        }
    }

    private fun onViewPressed() {
        val pattern = lastPattern
        if (pattern.isEmpty) {
            Toast.makeText(context, "Nothing to show", Toast.LENGTH_SHORT).show()
            return
        }
        showPreviewDialog(pattern)
    }

    /**
     * Edit flow: loads the suggested order into a guided per-item modifier review.
     * Per spec, no confirmation dialog is shown — the cart is cleared silently and the flow
     * walks through items one at a time so the merchant can tweak modifiers before items
     * land in the cart for real.
     */
    private fun onEditPressed() {
        val pattern = lastPattern
        if (pattern.isEmpty) return
        editApplier.apply(pattern)
    }

    private fun showPreviewDialog(pattern: RepeatPattern) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_same_as_usual_preview, null, false)
        val container = dialogView.findViewById<LinearLayout>(R.id.containerRepeatDialogItems)
        val txtDialogTotal = dialogView.findViewById<TextView>(R.id.txtRepeatDialogTotal)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtRepeatDialogSubtitle)
        val btnApplyDialog = dialogView.findViewById<MaterialButton>(R.id.btnRepeatDialogApply)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnRepeatDialogClose)

        txtSubtitle.text = when (pattern.type) {
            RepeatPatternType.FULL_ORDER ->
                "Ordered ${pattern.occurrences} times in recent visits"
            RepeatPatternType.ITEM_PATTERN ->
                "Frequent items (seen in ${pattern.occurrences} recent orders)"
            RepeatPatternType.NONE -> ""
        }

        val density = context.resources.displayMetrics.density
        val rowPadV = (8 * density).toInt()
        val modPadStart = (12 * density).toInt()

        for (item in pattern.items) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, rowPadV, 0, rowPadV)
            }
            val title = TextView(context).apply {
                text = "${item.quantity}× ${item.name}"
                textSize = 14f
                setTextColor(0xFF2E2548.toInt())
            }
            row.addView(title)
            for (mod in item.modifiers) {
                val label = when (mod.action.uppercase()) {
                    "REMOVE" -> "• No ${mod.name}"
                    else -> "• ${mod.name}"
                }
                val modText = TextView(context).apply {
                    text = label
                    textSize = 12f
                    setTextColor(0xFF5D4E7B.toInt())
                    setPadding(modPadStart, 0, 0, 0)
                }
                row.addView(modText)
            }
            container.addView(row)
        }

        if (pattern.type == RepeatPatternType.FULL_ORDER && pattern.totalInCents > 0L) {
            txtDialogTotal.text = "Total: " + MoneyUtils.centsToDisplay(pattern.totalInCents)
            txtDialogTotal.visibility = View.VISIBLE
        }

        val dlg = AlertDialog.Builder(context).setView(dialogView).create()
        btnClose.setOnClickListener { dlg.dismiss() }
        btnApplyDialog.setOnClickListener {
            dlg.dismiss()
            onRepeatPressed()
        }
        dlg.show()
    }
}
