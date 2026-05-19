package com.volt.maximobile

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class ReceiptPrintingSettingsActivity : AppCompatActivity() {

    private var suppressListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_printing_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.receipt_printing_settings_title)

        val rgMode = findViewById<RadioGroup>(R.id.rgReceiptPrintingMode)
        val rbAuto = findViewById<RadioButton>(R.id.rbAutoPrintAndAsk)
        val rbPrompt = findViewById<RadioButton>(R.id.rbPromptAfterOrder)
        val rbNone = findViewById<RadioButton>(R.id.rbDoNotPrint)

        bindModeToUi(
            rbAuto = rbAuto,
            rbPrompt = rbPrompt,
            rbNone = rbNone,
            mode = ReceiptPrintingConfig.getMode(this),
        )

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (suppressListener) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbAutoPrintAndAsk -> ReceiptPrintingConfig.MODE_AUTO_PRINT_AND_ASK
                R.id.rbDoNotPrint -> ReceiptPrintingConfig.MODE_DO_NOT_PRINT
                else -> ReceiptPrintingConfig.MODE_PROMPT_AFTER_ORDER
            }
            ReceiptPrintingConfig.setMode(this, mode)
        }

        ReceiptPrintingConfig.setOnModeChangedListener { mode ->
            runOnUiThread {
                suppressListener = true
                bindModeToUi(rbAuto, rbPrompt, rbNone, mode)
                suppressListener = false
            }
        }
        ReceiptPrintingConfig.startSync(this)
    }

    override fun onDestroy() {
        ReceiptPrintingConfig.setOnModeChangedListener(null)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindModeToUi(
        rbAuto: RadioButton,
        rbPrompt: RadioButton,
        rbNone: RadioButton,
        mode: String,
    ) {
        when (mode) {
            ReceiptPrintingConfig.MODE_AUTO_PRINT_AND_ASK -> rbAuto.isChecked = true
            ReceiptPrintingConfig.MODE_DO_NOT_PRINT -> rbNone.isChecked = true
            else -> rbPrompt.isChecked = true
        }
    }
}
