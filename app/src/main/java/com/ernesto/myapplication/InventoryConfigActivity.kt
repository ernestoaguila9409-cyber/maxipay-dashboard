package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class InventoryConfigActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var switchStockCounting: Switch
    private lateinit var txtDescription: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory_config)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Inventory Configuration"

        switchStockCounting = findViewById(R.id.switchStockCounting)
        txtDescription = findViewById(R.id.txtStockDescription)

        switchStockCounting.isEnabled = false
        loadSetting()

        switchStockCounting.setOnCheckedChangeListener { _, isChecked ->
            saveSetting(isChecked)
            updateDescription(isChecked)
        }
    }

    private fun loadSetting() {
        db.collection("Settings").document("inventory")
            .get()
            .addOnSuccessListener { doc ->
                val enabled = doc.getBoolean("stockCountingEnabled") ?: true
                switchStockCounting.setOnCheckedChangeListener(null)
                switchStockCounting.isChecked = enabled
                updateDescription(enabled)
                switchStockCounting.isEnabled = true
                switchStockCounting.setOnCheckedChangeListener { _, isChecked ->
                    saveSetting(isChecked)
                    updateDescription(isChecked)
                }
            }
            .addOnFailureListener {
                switchStockCounting.isChecked = true
                switchStockCounting.isEnabled = true
                updateDescription(true)
            }
    }

    private fun saveSetting(enabled: Boolean) {
        db.collection("Settings").document("inventory")
            .set(mapOf("stockCountingEnabled" to enabled))
            .addOnSuccessListener {
                val label = if (enabled) "enabled" else "disabled"
                Toast.makeText(this, "Stock counting $label", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save setting", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateDescription(enabled: Boolean) {
        txtDescription.text = if (enabled)
            "Stock levels are tracked. Items show stock counts and out-of-stock warnings."
        else
            "Stock tracking is off. Items can be ordered without stock limits."
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
