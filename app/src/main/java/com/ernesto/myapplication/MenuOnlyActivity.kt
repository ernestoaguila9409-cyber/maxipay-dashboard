package com.ernesto.myapplication

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuOnlyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_only)

        val container = findViewById<LinearLayout>(R.id.categoryContainer)

        // Temporary demo categories
        addCategory(container, "BURGERS")
        addCategory(container, "DRINKS")
    }

    private fun addCategory(container: LinearLayout, name: String) {

        val textView = TextView(this)
        textView.text = name
        textView.textSize = 18f
        textView.setPadding(40, 40, 40, 40)
        textView.setBackgroundColor(android.graphics.Color.WHITE)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 30)

        textView.layoutParams = params

        container.addView(textView)
    }
}