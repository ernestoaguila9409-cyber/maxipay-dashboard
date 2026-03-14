package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class DineInConfigureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dine_in_configure)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dine In"

        val switch = findViewById<SwitchCompat>(R.id.switchDineInEnabled)
        switch.isChecked = OrderTypePrefs.isDineInEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePrefs.setDineInEnabled(this, isChecked)
        }

        findViewById<android.view.View>(R.id.optionTableMappingSetup).setOnClickListener {
            startActivity(Intent(this, TableLayoutActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionPaymentMethods).setOnClickListener {
            val intent = Intent(this, PaymentMethodsActivity::class.java)
            intent.putExtra("ORDER_TYPE", "DINE_IN")
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
