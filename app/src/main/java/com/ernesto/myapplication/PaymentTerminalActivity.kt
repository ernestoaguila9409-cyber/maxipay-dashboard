package com.ernesto.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PaymentTerminalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Payment Terminal"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
