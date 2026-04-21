package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.android.material.button.MaterialButton
import java.util.Locale

class CashPaymentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AMOUNT_DUE_CENTS = "AMOUNT_DUE_CENTS"
        const val EXTRA_TENDERED_CENTS = "CASH_TENDERED_CENTS"
        const val EXTRA_CHANGE_CENTS = "CASH_CHANGE_CENTS"

        private val COLOR_CHANGE = Color.parseColor("#2E7D32")
        private val COLOR_REMAINING = Color.parseColor("#C62828")
        private val COLOR_PLACEHOLDER = Color.parseColor("#BDBDBD")
        private val COLOR_INPUT = Color.parseColor("#212121")
    }

    private var amountDueCents: Long = 0L
    private var amountDue: Double = 0.0
    private var cashReceived: Double = 0.0

    private lateinit var txtAmountDue: TextView
    private lateinit var txtCashReceivedInput: TextView
    private lateinit var txtChangeOrRemaining: TextView
    private lateinit var btnComplete: MaterialButton
    private lateinit var keyboardContainer: LinearLayout
    private lateinit var keypad: PosMoneyAmountKeypad

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_cash_payment)
        supportActionBar?.hide()

        amountDueCents = intent.getLongExtra(EXTRA_AMOUNT_DUE_CENTS, 0L)
        if (amountDueCents <= 0L) {
            finish()
            return
        }
        amountDue = MoneyUtils.centsToDouble(amountDueCents)

        txtAmountDue = findViewById(R.id.txtAmountDue)
        txtCashReceivedInput = findViewById(R.id.txtCashReceivedInput)
        txtChangeOrRemaining = findViewById(R.id.txtChangeOrRemaining)
        btnComplete = findViewById(R.id.btnComplete)
        keyboardContainer = findViewById(R.id.keyboardContainerCash)

        txtAmountDue.text = formatCurrency(amountDue)

        keypad = PosMoneyAmountKeypad(this, keyboardContainer) { raw ->
            applyCashBuffer(raw)
        }
        keypad.attach()

        findViewById<MaterialButton>(R.id.btnExactCash).setOnClickListener {
            keypad.applyExactAmountCents(amountDueCents)
        }

        txtCashReceivedInput.post {
            txtCashReceivedInput.requestFocus()
            hideIme(txtCashReceivedInput)
        }

        applyCashBuffer("")

        btnComplete.setOnClickListener {
            val tenderedCents = MoneyUtils.dollarsToCents(cashReceived)
            val changeCents = (tenderedCents - amountDueCents).coerceAtLeast(0L)
            val data = Intent().apply {
                putExtra(EXTRA_TENDERED_CENTS, tenderedCents)
                putExtra(EXTRA_CHANGE_CENTS, changeCents)
            }
            setResult(RESULT_OK, data)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnCancelCash).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::txtCashReceivedInput.isInitialized) {
            hideIme(txtCashReceivedInput)
        }
    }

    private fun hideIme(view: android.view.View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun applyCashBuffer(raw: String) {
        cashReceived = parseCashReceived(raw)
        renderCashInputDisplay(raw)
        val delta = cashReceived - amountDue
        if (delta >= 0.0) {
            txtChangeOrRemaining.text = "Change: ${formatCurrency(delta)}"
            txtChangeOrRemaining.setTextColor(COLOR_CHANGE)
        } else {
            txtChangeOrRemaining.text = "Remaining: ${formatCurrency(kotlin.math.abs(delta))}"
            txtChangeOrRemaining.setTextColor(COLOR_REMAINING)
        }
        val tenderedCents = MoneyUtils.dollarsToCents(cashReceived)
        btnComplete.isEnabled = tenderedCents >= amountDueCents
    }

    private fun renderCashInputDisplay(raw: String) {
        if (raw.isEmpty()) {
            txtCashReceivedInput.text = "0.00"
            txtCashReceivedInput.setTextColor(COLOR_PLACEHOLDER)
        } else {
            txtCashReceivedInput.text = raw
            txtCashReceivedInput.setTextColor(COLOR_INPUT)
        }
    }

    private fun parseCashReceived(raw: String): Double {
        if (raw.isEmpty() || raw == ".") return 0.0
        return raw.toDoubleOrNull() ?: 0.0
    }

    private fun formatCurrency(amount: Double): String =
        String.format(Locale.US, "$%.2f", amount)
}
