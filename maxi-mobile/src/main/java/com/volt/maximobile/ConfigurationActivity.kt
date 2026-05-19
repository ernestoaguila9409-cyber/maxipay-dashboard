package com.volt.maximobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ConfigurationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuration)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configuration"

        val mid = PosDeviceIdentity.getMerchantId(this).trim()
        if (mid.isNotEmpty()) {
            MerchantFirestore.init(mid)
            ReceiptPrintingConfig.startSync(this)
        }

        findViewById<android.view.View>(R.id.optionTaxesAndFees).setOnClickListener {
            startActivity(Intent(this, TaxesAndFeesActivity::class.java))
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

        findViewById<android.view.View>(R.id.optionSignatureSettings).setOnClickListener {
            startActivity(Intent(this, SignatureSettingsActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
