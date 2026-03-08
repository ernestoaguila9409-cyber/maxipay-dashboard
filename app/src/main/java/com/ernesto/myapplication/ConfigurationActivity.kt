package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ConfigurationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuration)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configuration"

        findViewById<android.view.View>(R.id.optionPaymentMethods).setOnClickListener {
            startActivity(Intent(this, PaymentMethodsActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionTaxesAndFees).setOnClickListener {
            startActivity(Intent(this, TaxesAndFeesActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionPaymentTerminal).setOnClickListener {
            startActivity(Intent(this, PaymentTerminalActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
