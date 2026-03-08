package com.ernesto.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TableMappingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Table Mapping Setup"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
