package com.ernesto.myapplication

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.Locale

class BarTabConfigActivity : AppCompatActivity() {

    private lateinit var edtPreAuthAmount: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bar_tab_config)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bar Tab Configuration"

        edtPreAuthAmount = findViewById(R.id.edtPreAuthAmount)

        val saved = BarTabPrefs.getPreAuthAmount(this)
        edtPreAuthAmount.setText(String.format(Locale.US, "%.2f", saved))

        findViewById<MaterialButton>(R.id.btnSaveBarTabConfig).setOnClickListener {
            val amount = edtPreAuthAmount.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "Enter a valid amount greater than 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            BarTabPrefs.setPreAuthAmount(this, amount)
            Toast.makeText(this, "Bar tab settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

object BarTabPrefs {
    private const val PREFS_NAME = "bar_tab_prefs"
    private const val KEY_PRE_AUTH_AMOUNT = "pre_auth_amount"
    private const val DEFAULT_PRE_AUTH_AMOUNT = 50.0

    fun getPreAuthAmount(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getFloat(KEY_PRE_AUTH_AMOUNT, DEFAULT_PRE_AUTH_AMOUNT.toFloat()).toDouble()
        } catch (_: ClassCastException) {
            val legacy = prefs.getInt(KEY_PRE_AUTH_AMOUNT, DEFAULT_PRE_AUTH_AMOUNT.toInt()).toDouble()
            setPreAuthAmount(context, legacy)
            legacy
        }
    }

    fun setPreAuthAmount(context: Context, amount: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PRE_AUTH_AMOUNT)
            .putFloat(KEY_PRE_AUTH_AMOUNT, amount.toFloat())
            .apply()
    }
}
