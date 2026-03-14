package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class BarTabConfigureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bar_tab_configure)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bar Tab"

        val switch = findViewById<SwitchCompat>(R.id.switchBarTabEnabled)
        switch.isChecked = OrderTypePrefs.isBarTabEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePrefs.setBarTabEnabled(this, isChecked)
        }

        findViewById<android.view.View>(R.id.optionSeatsConfig).setOnClickListener {
            startActivity(Intent(this, BarSeatConfigActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionSetPreAuth).setOnClickListener {
            startActivity(Intent(this, BarTabConfigActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionPaymentMethods).setOnClickListener {
            val intent = Intent(this, PaymentMethodsActivity::class.java)
            intent.putExtra("ORDER_TYPE", "BAR_TAB")
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
