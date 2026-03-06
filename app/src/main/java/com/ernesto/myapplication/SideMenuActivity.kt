package com.ernesto.myapplication

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
            val i = Intent(this, MainActivity::class.java) // <-- this is your 2nd picture screen in most setups
            i.putExtra("ORDER_ID", orderIdFromDetail)
            i.putExtra("employeeName", intent.getStringExtra("employeeName"))
            i.putExtra("employeeRole", intent.getStringExtra("employeeRole"))
            // Optional: tell MainActivity we're editing an existing order
            i.putExtra("MODE", "EDIT_ORDER")
            startActivity(i)
            finish()
            return
        }

        // Normal entry (no orderId): show the side menu page
        setContentView(R.layout.activity_side_menu)

        val btnOrders = findViewById<Button>(R.id.btnOrders)
        btnOrders.setOnClickListener {
            val intent = Intent(this, OrdersActivity::class.java)
            intent.putExtra("employeeName", intent.getStringExtra("employeeName"))
            startActivity(intent)
        }
    }
}