package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class ToGoConfigureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_to_go_configure)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "To Go"

        val switch = findViewById<SwitchCompat>(R.id.switchToGoEnabled)
        switch.isChecked = OrderTypePrefs.isToGoEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePrefs.setToGoEnabled(this, isChecked)
        }

        findViewById<android.view.View>(R.id.optionPaymentMethods).setOnClickListener {
            val intent = Intent(this, PaymentMethodsActivity::class.java)
            intent.putExtra("ORDER_TYPE", "TO_GO")
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
