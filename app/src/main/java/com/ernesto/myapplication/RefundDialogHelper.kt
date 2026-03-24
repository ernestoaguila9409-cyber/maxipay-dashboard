package com.ernesto.myapplication

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.ernesto.myapplication.engine.MoneyUtils

object RefundDialogHelper {

    /**
     * Entry point: shows the refund options dialog with Full / Custom / Percentage choices.
     * @param maxRefundCents the maximum refundable amount in cents
     * @param onRefundAmount callback with the chosen refund amount in cents
     */
    fun showRefundOptionsDialog(
        context: Context,
        maxRefundCents: Long,
        onRefundAmount: (amountInCents: Long) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_refund_options, null)

        val txtMax = view.findViewById<TextView>(R.id.txtMaxRefundAmount)
        val radioFull = view.findViewById<RadioButton>(R.id.radioFullRefund)
        val radioCustom = view.findViewById<RadioButton>(R.id.radioCustomAmount)
        val radioPercent = view.findViewById<RadioButton>(R.id.radioPercentage)
        val btnContinue = view.findViewById<MaterialButton>(R.id.btnContinueRefund)

        txtMax.text = MoneyUtils.centsToDisplay(maxRefundCents)
        radioFull.text = "Full Refund \u2014 ${MoneyUtils.centsToDisplay(maxRefundCents)}"
        radioFull.isChecked = true

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        btnContinue.setOnClickListener {
            dialog.dismiss()
            when {
                radioFull.isChecked -> onRefundAmount(maxRefundCents)
                radioCustom.isChecked -> showCustomAmountDialog(context, maxRefundCents, onRefundAmount)
                radioPercent.isChecked -> showPercentageDialog(context, maxRefundCents, onRefundAmount)
            }
        }

        dialog.show()
    }

    private fun showCustomAmountDialog(
        context: Context,
        maxRefundCents: Long,
        onRefundAmount: (amountInCents: Long) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_refund_custom_amount, null)

        val txtMax = view.findViewById<TextView>(R.id.txtMaxAmountCustom)
        val edtAmount = view.findViewById<TextInputEditText>(R.id.edtRefundAmount)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelCustom)
        val btnRefund = view.findViewById<MaterialButton>(R.id.btnRefundCustom)

        txtMax.text = "Maximum: ${MoneyUtils.centsToDisplay(maxRefundCents)}"

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnRefund.setOnClickListener {
            val entered = edtAmount.text.toString().toDoubleOrNull()
            val maxDollars = maxRefundCents / 100.0

            if (entered == null || entered <= 0) {
                Toast.makeText(context, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (entered > maxDollars) {
                Toast.makeText(
                    context,
                    "Amount cannot exceed ${MoneyUtils.centsToDisplay(maxRefundCents)}",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            onRefundAmount(MoneyUtils.dollarsToCents(entered))
        }

        dialog.show()
    }

    private fun showPercentageDialog(
        context: Context,
        maxRefundCents: Long,
        onRefundAmount: (amountInCents: Long) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_refund_percentage, null)

        val txtMax = view.findViewById<TextView>(R.id.txtMaxAmountPct)
        val btn25 = view.findViewById<MaterialButton>(R.id.btnPct25)
        val btn50 = view.findViewById<MaterialButton>(R.id.btnPct50)
        val btn75 = view.findViewById<MaterialButton>(R.id.btnPct75)
        val btn100 = view.findViewById<MaterialButton>(R.id.btnPct100)
        val edtCustom = view.findViewById<TextInputEditText>(R.id.edtCustomPercent)
        val txtCalculated = view.findViewById<TextView>(R.id.txtCalculatedAmount)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelPct)
        val btnRefund = view.findViewById<MaterialButton>(R.id.btnRefundPct)

        val allPresets = listOf(btn25, btn50, btn75, btn100)
        txtMax.text = "Maximum: ${MoneyUtils.centsToDisplay(maxRefundCents)}"

        var selectedCents = 0L

        fun updateCalculated(pct: Double) {
            selectedCents = kotlin.math.round(maxRefundCents * pct / 100.0).toLong()
                .coerceIn(0L, maxRefundCents)
            txtCalculated.text = MoneyUtils.centsToDisplay(selectedCents)
        }

        fun highlightPreset(selected: MaterialButton?) {
            val activeBg = ColorStateList.valueOf(Color.parseColor("#1976D2"))
            val defaultBg = ColorStateList.valueOf(Color.TRANSPARENT)
            for (btn in allPresets) {
                if (btn == selected) {
                    btn.backgroundTintList = activeBg
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.backgroundTintList = defaultBg
                    btn.setTextColor(Color.parseColor("#1976D2"))
                }
            }
        }

        fun selectPreset(pct: Int, btn: MaterialButton) {
            edtCustom.setText("")
            updateCalculated(pct.toDouble())
            highlightPreset(btn)
        }

        btn25.setOnClickListener { selectPreset(25, btn25) }
        btn50.setOnClickListener { selectPreset(50, btn50) }
        btn75.setOnClickListener { selectPreset(75, btn75) }
        btn100.setOnClickListener { selectPreset(100, btn100) }

        edtCustom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pct = s?.toString()?.toDoubleOrNull()
                if (pct != null && pct > 0) {
                    highlightPreset(null)
                    if (pct <= 100) {
                        updateCalculated(pct)
                    } else {
                        selectedCents = 0L
                        txtCalculated.text = MoneyUtils.centsToDisplay(0)
                    }
                } else if (s.isNullOrEmpty()) {
                    highlightPreset(null)
                    selectedCents = 0L
                    txtCalculated.text = MoneyUtils.centsToDisplay(0)
                }
            }
        })

        updateCalculated(0.0)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnRefund.setOnClickListener {
            if (selectedCents <= 0L) {
                Toast.makeText(context, "Select a percentage", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val customPct = edtCustom.text.toString().toDoubleOrNull()
            if (customPct != null && customPct > 100) {
                Toast.makeText(context, "Percentage cannot exceed 100%", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onRefundAmount(selectedCents)
        }

        dialog.show()
    }
}
