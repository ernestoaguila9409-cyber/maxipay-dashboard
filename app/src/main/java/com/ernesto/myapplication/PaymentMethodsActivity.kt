package com.ernesto.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class PaymentMethodsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_methods)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Payment Methods"

        val switchMixPayments = findViewById<SwitchCompat>(R.id.switchMixPayments)
        switchMixPayments.isChecked = MixPaymentsConfig.isEnabled(this)
        switchMixPayments.setOnCheckedChangeListener { _, isChecked ->
            MixPaymentsConfig.setEnabled(this, isChecked)
        }

        val switchCredit = findViewById<SwitchCompat>(R.id.switchCredit)
        switchCredit.isChecked = PaymentMethodsConfig.isCreditEnabled(this)
        switchCredit.setOnCheckedChangeListener { _, isChecked ->
            PaymentMethodsConfig.setCreditEnabled(this, isChecked)
        }

        val switchDebit = findViewById<SwitchCompat>(R.id.switchDebit)
        switchDebit.isChecked = PaymentMethodsConfig.isDebitEnabled(this)
        switchDebit.setOnCheckedChangeListener { _, isChecked ->
            PaymentMethodsConfig.setDebitEnabled(this, isChecked)
        }

        val switchCash = findViewById<SwitchCompat>(R.id.switchCash)
        switchCash.isChecked = PaymentMethodsConfig.isCashEnabled(this)
        switchCash.setOnCheckedChangeListener { _, isChecked ->
            PaymentMethodsConfig.setCashEnabled(this, isChecked)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
