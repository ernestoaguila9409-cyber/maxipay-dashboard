package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SideMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_side_menu)

        val btnOrders = findViewById<Button>(R.id.btnOrders)
        btnOrders.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
    }
}