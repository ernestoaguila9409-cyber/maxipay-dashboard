package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Hub: Order Types configuration or Kitchen Order Configuration. */
class OrderConfigurationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_configuration)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Order Configuration"

        findViewById<android.view.View>(R.id.optionOrderTypesConfiguration).setOnClickListener {
            startActivity(Intent(this, OrderTypesConfigureActivity::class.java))
        }
        findViewById<android.view.View>(R.id.optionKitchenOrderConfiguration).setOnClickListener {
            startActivity(Intent(this, KitchenOrderConfigurationActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
