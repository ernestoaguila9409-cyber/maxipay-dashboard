package com.ernesto.myapplication

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class TipConfigActivity : AppCompatActivity() {

    private val presetEditTextIds = listOf(
        R.id.etPreset1, R.id.etPreset2, R.id.etPreset3,
        R.id.etPreset4, R.id.etPreset5
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tip_config)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Tips"

        val switchTips = findViewById<SwitchCompat>(R.id.switchTipsEnabled)
        val switchCustomTip = findViewById<SwitchCompat>(R.id.switchCustomTip)
        val presetsContainer = findViewById<LinearLayout>(R.id.presetsContainer)
        val rgCalculationBase = findViewById<RadioGroup>(R.id.rgCalculationBase)
        val rbSubtotal = findViewById<RadioButton>(R.id.rbSubtotal)
        val rbTotal = findViewById<RadioButton>(R.id.rbTotal)
        val calculationBaseContainer = findViewById<LinearLayout>(R.id.calculationBaseContainer)

        switchTips.isChecked = TipConfig.isTipsEnabled(this)
        switchCustomTip.isChecked = TipConfig.isCustomTipEnabled(this)

        for (i in presetEditTextIds.indices) {
            val et = findViewById<EditText>(presetEditTextIds[i])
            val value = TipConfig.getPresetValue(this, i)
            et.setText(if (value > 0) value.toString() else "")
        }

        if (TipConfig.getCalculationBase(this) == "SUBTOTAL") {
            rbSubtotal.isChecked = true
        } else {
            rbTotal.isChecked = true
        }

        rgCalculationBase.setOnCheckedChangeListener { _, checkedId ->
            val base = if (checkedId == R.id.rbSubtotal) "SUBTOTAL" else "TOTAL"
            TipConfig.setCalculationBase(this, base)
        }

        fun updateFieldsEnabled(enabled: Boolean) {
            presetsContainer.alpha = if (enabled) 1f else 0.4f
            calculationBaseContainer.alpha = if (enabled) 1f else 0.4f
            for (id in presetEditTextIds) {
                findViewById<EditText>(id).isEnabled = enabled
            }
            switchCustomTip.isEnabled = enabled
            rbSubtotal.isEnabled = enabled
            rbTotal.isEnabled = enabled
        }

        updateFieldsEnabled(switchTips.isChecked)

        switchTips.setOnCheckedChangeListener { _, isChecked ->
            TipConfig.setTipsEnabled(this, isChecked)
            updateFieldsEnabled(isChecked)
        }

        switchCustomTip.setOnCheckedChangeListener { _, isChecked ->
            TipConfig.setCustomTipEnabled(this, isChecked)
        }

        val saveOnFocusLost = View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val text = (v as EditText).text.toString().trim()
                val index = presetEditTextIds.indexOf(v.id)
                if (index < 0) return@OnFocusChangeListener

                if (text.isEmpty()) {
                    TipConfig.clearPreset(this, index)
                } else {
                    val value = text.toIntOrNull()
                    if (value != null && value in 1..100) {
                        TipConfig.setPresetValue(this, index, value)
                    } else {
                        Toast.makeText(this, "Enter a value between 1 and 100, or leave blank", Toast.LENGTH_SHORT).show()
                        (v as EditText).setText("")
                        TipConfig.clearPreset(this, index)
                    }
                }
            }
        }

        for (id in presetEditTextIds) {
            findViewById<EditText>(id).onFocusChangeListener = saveOnFocusLost
        }
    }

    override fun onPause() {
        super.onPause()
        saveAllPresets()
    }

    private fun saveAllPresets() {
        for (i in presetEditTextIds.indices) {
            val text = findViewById<EditText>(presetEditTextIds[i]).text.toString().trim()
            if (text.isEmpty()) {
                TipConfig.clearPreset(this, i)
            } else {
                text.toIntOrNull()?.let {
                    if (it in 1..100) TipConfig.setPresetValue(this, i, it)
                    else TipConfig.clearPreset(this, i)
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
