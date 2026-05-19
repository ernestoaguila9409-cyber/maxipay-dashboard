package com.ernesto.myapplication

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class ReceiptPrintingSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_printing_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.receipt_printing_settings_title)

        val rgMode = findViewById<RadioGroup>(R.id.rgReceiptPrintingMode)
        val rbAuto = findViewById<RadioButton>(R.id.rbAutoPrintAndAsk)
        val rbPrompt = findViewById<RadioButton>(R.id.rbPromptAfterOrder)
        val rbNone = findViewById<RadioButton>(R.id.rbDoNotPrint)

        when (ReceiptPrintingConfig.getMode(this)) {
            ReceiptPrintingConfig.MODE_AUTO_PRINT_AND_ASK -> rbAuto.isChecked = true
            ReceiptPrintingConfig.MODE_DO_NOT_PRINT -> rbNone.isChecked = true
            else -> rbPrompt.isChecked = true
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbAutoPrintAndAsk -> ReceiptPrintingConfig.MODE_AUTO_PRINT_AND_ASK
                R.id.rbDoNotPrint -> ReceiptPrintingConfig.MODE_DO_NOT_PRINT
                else -> ReceiptPrintingConfig.MODE_PROMPT_AFTER_ORDER
            }
            ReceiptPrintingConfig.setMode(this, mode)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
