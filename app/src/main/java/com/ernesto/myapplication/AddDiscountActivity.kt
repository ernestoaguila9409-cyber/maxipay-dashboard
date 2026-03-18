package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale

class AddDiscountActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private var discountId: String? = null
    private lateinit var edtName: EditText
    private lateinit var radioType: RadioGroup
    private lateinit var edtValue: EditText
    private lateinit var radioApplyTo: RadioGroup
    private lateinit var switchActive: SwitchCompat
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_discount)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        edtName = findViewById(R.id.edtDiscountName)
        radioType = findViewById(R.id.radioDiscountType)
        edtValue = findViewById(R.id.edtDiscountValue)
        radioApplyTo = findViewById(R.id.radioDiscountApplyTo)
        switchActive = findViewById(R.id.switchDiscountActive)
        btnSave = findViewById(R.id.btnSaveDiscount)
        btnDelete = findViewById(R.id.btnDeleteDiscount)

        discountId = intent.getStringExtra("DISCOUNT_ID")
        if (discountId != null) {
            supportActionBar?.title = "Edit discount"
            edtName.setText(intent.getStringExtra("DISCOUNT_NAME") ?: "")
            val type = intent.getStringExtra("DISCOUNT_TYPE") ?: "PERCENTAGE"
            radioType.check(if (type == "FIXED") R.id.radioDiscountFixed else R.id.radioDiscountPercentage)
            val value = intent.getDoubleExtra("DISCOUNT_VALUE", 0.0)
            edtValue.setText(String.format(Locale.US, "%.2f", value))
            val applyTo = intent.getStringExtra("DISCOUNT_APPLY_TO") ?: "ORDER"
            radioApplyTo.check(if (applyTo == "ITEM") R.id.radioApplyItem else R.id.radioApplyOrder)
            switchActive.isChecked = intent.getBooleanExtra("DISCOUNT_ACTIVE", true)
            btnSave.text = "Update"
            btnDelete.visibility = android.view.View.VISIBLE
        } else {
            supportActionBar?.title = "Add discount"
            btnDelete.visibility = android.view.View.GONE
        }

        btnSave.setOnClickListener { saveDiscount() }
        btnDelete.setOnClickListener { confirmAndDelete() }
    }

    private fun saveDiscount() {
        val name = edtName.text.toString().trim()
        val valueStr = edtValue.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return
        }
        if (valueStr.isBlank()) {
            Toast.makeText(this, "Enter a value", Toast.LENGTH_SHORT).show()
            return
        }

        val value = valueStr.toDoubleOrNull()
        if (value == null || value <= 0) {
            Toast.makeText(this, "Enter a valid value", Toast.LENGTH_SHORT).show()
            return
        }

        val isFixed = radioType.checkedRadioButtonId == R.id.radioDiscountFixed
        val type = if (isFixed) "FIXED" else "PERCENTAGE"

        if (!isFixed && value > 100) {
            Toast.makeText(this, "Percentage cannot exceed 100", Toast.LENGTH_SHORT).show()
            return
        }

        val applyTo = if (radioApplyTo.checkedRadioButtonId == R.id.radioApplyItem) "ITEM" else "ORDER"
        val active = switchActive.isChecked

        val data = hashMapOf<String, Any>(
            "name" to name,
            "type" to type,
            "value" to value,
            "applyTo" to applyTo,
            "active" to active
        )

        if (discountId == null) {
            data["createdAt"] = Date()
        } else {
            data["updatedAt"] = Date()
        }

        if (discountId != null) {
            db.collection("discounts").document(discountId!!)
                .update(data as Map<String, Any>)
                .addOnSuccessListener {
                    Toast.makeText(this, "Discount updated", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            db.collection("discounts")
                .add(data)
                .addOnSuccessListener {
                    Toast.makeText(this, "Discount saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun confirmAndDelete() {
        val id = discountId ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove discount")
            .setMessage("Remove this discount?")
            .setPositiveButton("Remove") { _, _ ->
                db.collection("discounts").document(id)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Discount removed", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to remove: ${e.message}", Toast.LENGTH_SHORT).show()
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
