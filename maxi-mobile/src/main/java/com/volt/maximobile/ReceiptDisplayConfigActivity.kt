package com.volt.maximobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ReceiptDisplayConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_display_config)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Print Receipt Display"

        val mid = PosDeviceIdentity.getMerchantId(this).trim()
        if (mid.isNotEmpty()) {
            MerchantFirestore.init(mid)
            ReceiptPrintingConfig.startSync(this)
        }

        findViewById<android.view.View>(R.id.optionViewEditReceipt).setOnClickListener {
            startActivity(Intent(this, ViewEditReceiptActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionReceiptPrintingSettings).setOnClickListener {
            startActivity(Intent(this, ReceiptPrintingSettingsActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
