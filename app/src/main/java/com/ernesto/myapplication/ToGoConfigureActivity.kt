package com.ernesto.myapplication

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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
