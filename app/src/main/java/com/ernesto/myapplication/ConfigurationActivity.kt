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

        findViewById<android.view.View>(R.id.optionTaxesAndFees).setOnClickListener {
            startActivity(Intent(this, TaxesAndFeesActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionPaymentTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalListActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionOrderTypes).setOnClickListener {
            startActivity(Intent(this, OrderConfigurationActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionTips).setOnClickListener {
            startActivity(Intent(this, TipsAndDiscountsHubActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionInventory).setOnClickListener {
            startActivity(Intent(this, InventoryConfigActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionPrintReceiptDisplay).setOnClickListener {
            startActivity(Intent(this, ReceiptDisplayConfigActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
