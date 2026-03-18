package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MenuPerformanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_performance)
        supportActionBar?.hide()

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<android.view.View>(R.id.cardItemSales).setOnClickListener {
            startActivity(Intent(this, ItemSalesReportActivity::class.java))
        }

        findViewById<android.view.View>(R.id.cardCategorySales).setOnClickListener {
            startActivity(Intent(this, CategorySalesReportActivity::class.java))
        }

        findViewById<android.view.View>(R.id.cardModifierSales).setOnClickListener {
            startActivity(Intent(this, ModifierSalesReportActivity::class.java))
        }
    }
}
