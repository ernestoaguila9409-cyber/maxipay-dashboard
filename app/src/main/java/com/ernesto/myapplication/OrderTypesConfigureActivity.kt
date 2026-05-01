package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class OrderTypesConfigureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_types_configure)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Order Types configuration"

        findViewById<android.view.View>(R.id.optionBarTab).setOnClickListener {
            startActivity(Intent(this, BarTabConfigureActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionToGo).setOnClickListener {
            startActivity(Intent(this, ToGoConfigureActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionDineIn).setOnClickListener {
            startActivity(Intent(this, DineInConfigureActivity::class.java))
        }

        findViewById<android.view.View>(R.id.optionOnlineOrdering).setOnClickListener {
            startActivity(Intent(this, OnlineOrderingConfigureActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
