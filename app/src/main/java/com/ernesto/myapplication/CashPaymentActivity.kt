package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class CashPaymentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AMOUNT_DUE_CENTS = "AMOUNT_DUE_CENTS"
        const val EXTRA_TENDERED_CENTS = "CASH_TENDERED_CENTS"
        const val EXTRA_CHANGE_CENTS = "CASH_CHANGE_CENTS"
    }

    private var amountDueCents: Long = 0L
    private var cashReceivedCents: Long = 0L

    private lateinit var txtAmountDue: TextView
    private lateinit var cardChange: MaterialCardView
    private lateinit var txtCashReceived: TextView
    private lateinit var txtChange: TextView
    private lateinit var txtWarning: TextView
    private lateinit var inputCustomAmount: TextInputEditText
    private lateinit var btnComplete: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cash_payment)
        supportActionBar?.hide()

        amountDueCents = intent.getLongExtra(EXTRA_AMOUNT_DUE_CENTS, 0L)
        if (amountDueCents <= 0L) {
            finish()
            return
        }

        txtAmountDue = findViewById(R.id.txtAmountDue)
        cardChange = findViewById(R.id.cardChange)
        txtCashReceived = findViewById(R.id.txtCashReceived)
        txtChange = findViewById(R.id.txtChange)
        txtWarning = findViewById(R.id.txtWarning)
        inputCustomAmount = findViewById(R.id.inputCustomAmount)
        btnComplete = findViewById(R.id.btnComplete)

        txtAmountDue.text = MoneyUtils.centsToDisplay(amountDueCents)

        setupDenominationButtons()
        setupExactAmountButton()
        setupCustomInput()

        btnComplete.setOnClickListener {
            val data = Intent().apply {
                putExtra(EXTRA_TENDERED_CENTS, cashReceivedCents)
                putExtra(EXTRA_CHANGE_CENTS, (cashReceivedCents - amountDueCents).coerceAtLeast(0L))
            }
            setResult(RESULT_OK, data)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnCancelCash).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun setupDenominationButtons() {
        val denominations = mapOf(
            R.id.btnDenom5 to 500L,
            R.id.btnDenom10 to 1000L,
            R.id.btnDenom20 to 2000L,
            R.id.btnDenom50 to 5000L,
            R.id.btnDenom100 to 10000L
        )
        for ((id, cents) in denominations) {
            findViewById<MaterialButton>(id).setOnClickListener {
                selectAmount(cents)
                inputCustomAmount.setText("")
            }
        }
    }

    private fun setupExactAmountButton() {
        findViewById<MaterialButton>(R.id.btnExactAmount).setOnClickListener {
            selectAmount(amountDueCents)
            inputCustomAmount.setText("")
        }
    }

    private fun setupCustomInput() {
        inputCustomAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isEmpty()) return
                val dollars = text.toDoubleOrNull() ?: return
                selectAmount(MoneyUtils.dollarsToCents(dollars))
            }
        })
    }

    private fun selectAmount(receivedCents: Long) {
        cashReceivedCents = receivedCents
        val changeCents = receivedCents - amountDueCents
        val sufficient = changeCents >= 0L

        cardChange.visibility = View.VISIBLE
        txtCashReceived.text = MoneyUtils.centsToDisplay(receivedCents)

        if (sufficient) {
            txtChange.text = MoneyUtils.centsToDisplay(changeCents)
            txtChange.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            txtWarning.visibility = View.GONE
        } else {
            txtChange.text = String.format(Locale.US, "-$%.2f", -changeCents / 100.0)
            txtChange.setTextColor(android.graphics.Color.parseColor("#C62828"))
            txtWarning.visibility = View.VISIBLE
        }

        btnComplete.isEnabled = sufficient
    }
}
