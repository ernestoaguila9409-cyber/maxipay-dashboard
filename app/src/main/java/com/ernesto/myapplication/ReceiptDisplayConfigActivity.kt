package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ReceiptDisplayConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_display_config)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Print Receipt Display"

        findViewById<android.view.View>(R.id.optionViewEditReceipt).setOnClickListener {
            startActivity(Intent(this, ViewEditReceiptActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
