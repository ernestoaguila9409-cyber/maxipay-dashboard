package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class DineInConfigureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dine_in_configure)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dine In"

        val switch = findViewById<SwitchCompat>(R.id.switchDineInEnabled)
        switch.isChecked = OrderTypePrefs.isDineInEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            OrderTypePrefs.setDineInEnabled(this, isChecked)
        }

        findViewById<android.view.View>(R.id.optionTableMappingSetup).setOnClickListener {
            startActivity(Intent(this, TableLayoutActivity::class.java))
        }

        val txtWaitingValue = findViewById<TextView>(R.id.txtWaitingAlertValue)
        txtWaitingValue.text = OrderTypePrefs.getWaitingAlertMinutes(this).toString()

        findViewById<android.view.View>(R.id.optionWaitingAlert).setOnClickListener {
            showWaitingAlertDialog(txtWaitingValue)
        }

        findViewById<android.view.View>(R.id.optionPaymentMethods).setOnClickListener {
            val intent = Intent(this, PaymentMethodsActivity::class.java)
            intent.putExtra("ORDER_TYPE", "DINE_IN")
            startActivity(intent)
        }
    }

    private fun showWaitingAlertDialog(displayView: TextView) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(OrderTypePrefs.getWaitingAlertMinutes(this@DineInConfigureActivity).toString())
            setPadding(48, 32, 48, 32)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("Waiting for Order Alert")
            .setMessage("Enter the number of minutes before a table shows a waiting alert.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val minutes = input.text.toString().toIntOrNull()
                if (minutes != null && minutes > 0) {
                    OrderTypePrefs.setWaitingAlertMinutes(this, minutes)
                    displayView.text = minutes.toString()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
