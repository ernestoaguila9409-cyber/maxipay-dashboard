package com.ernesto.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class PaymentMethodsActivity : AppCompatActivity() {

    private var orderType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_methods)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Payment Methods"

        orderType = intent.getStringExtra("ORDER_TYPE")

        if (orderType != null) {
            setupOrderTypeConfig(orderType!!)
        } else {
            setupGlobalConfig()
        }
    }

    private fun setupOrderTypeConfig(type: String) {
        val switchMixPayments = findViewById<SwitchCompat>(R.id.switchMixPayments)
        switchMixPayments.isChecked = OrderTypePaymentConfig.isMixPaymentsEnabled(this, type)
        switchMixPayments.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePaymentConfig.setMixPaymentsEnabled(this, type, isChecked)
        }

        val switchCredit = findViewById<SwitchCompat>(R.id.switchCredit)
        switchCredit.isChecked = OrderTypePaymentConfig.isCreditEnabled(this, type)
        switchCredit.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePaymentConfig.setCreditEnabled(this, type, isChecked)
        }

        val switchDebit = findViewById<SwitchCompat>(R.id.switchDebit)
        switchDebit.isChecked = OrderTypePaymentConfig.isDebitEnabled(this, type)
        switchDebit.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePaymentConfig.setDebitEnabled(this, type, isChecked)
        }

        val switchCash = findViewById<SwitchCompat>(R.id.switchCash)
        switchCash.isChecked = OrderTypePaymentConfig.isCashEnabled(this, type)
        switchCash.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePaymentConfig.setCashEnabled(this, type, isChecked)
        }
    }

    private fun setupGlobalConfig() {
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
