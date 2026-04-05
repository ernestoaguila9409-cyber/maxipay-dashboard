package com.ernesto.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Placeholder for managing connected printers. FAB reserved for add flow.
 */
class PrinterListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Printers"

        findViewById<FloatingActionButton>(R.id.fabAddPrinter).setOnClickListener {
            showPrinterTypePickerThenOpenAddPrinter()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
