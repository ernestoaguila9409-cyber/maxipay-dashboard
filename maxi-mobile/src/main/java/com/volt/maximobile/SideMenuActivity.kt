package com.volt.maximobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SideMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If we came here from an OPEN order "Checkout", forward to the menu/cart screen
        val orderIdFromDetail = intent.getStringExtra("ORDER_ID")
        if (!orderIdFromDetail.isNullOrBlank()) {
            val i = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ORDER_ID, orderIdFromDetail)
                putExtra("employeeName", intent.getStringExtra("employeeName"))
                putExtra("employeeRole", intent.getStringExtra("employeeRole"))
            }
            startActivity(i)
            finish()
            return
        }

        // Normal entry (no orderId): show the side menu page
        setContentView(R.layout.activity_side_menu)

        val btnConfiguration = findViewById<Button>(R.id.btnConfiguration)
        btnConfiguration.setOnClickListener {
            val intent = Intent(this, ConfigurationActivity::class.java)
            intent.putExtra("employeeName", intent.getStringExtra("employeeName"))
            startActivity(intent)
        }
    }
}