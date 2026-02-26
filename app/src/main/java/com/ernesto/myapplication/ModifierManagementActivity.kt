package com.ernesto.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.R

class ModifierManagementActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ matches your existing XML file: activity_global_modifier.xml
        setContentView(R.layout.activity_global_modifier)
    }
}