package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale

class AddTaxActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private var taxId: String? = null
    private lateinit var radioType: RadioGroup
    private lateinit var edtName: EditText
    private lateinit var edtAmount: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_tax)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        radioType = findViewById(R.id.radioType)
        edtName = findViewById(R.id.edtTaxName)
        edtAmount = findViewById(R.id.edtTaxAmount)
        btnSave = findViewById(R.id.btnSaveTax)
        btnDelete = findViewById(R.id.btnDeleteTax)

        taxId = intent.getStringExtra("TAX_ID")
        if (taxId != null) {
            supportActionBar?.title = "Edit tax or fee"
            edtName.setText(intent.getStringExtra("TAX_NAME") ?: "")
            val type = intent.getStringExtra("TAX_TYPE") ?: "FIXED"
            radioType.check(if (type == "PERCENTAGE") R.id.radioPercentage else R.id.radioFixed)
            val amount = intent.getDoubleExtra("TAX_AMOUNT", 0.0)
            edtAmount.setText(String.format(Locale.US, "%.2f", amount))
            btnSave.text = "Update"
            btnDelete.visibility = android.view.View.VISIBLE
        } else {
            supportActionBar?.title = "Add tax or fee"
            btnDelete.visibility = android.view.View.GONE
        }

        btnSave.setOnClickListener { saveTax() }
        btnDelete.setOnClickListener { confirmAndDelete() }
    }

    private fun saveTax() {
        val name = edtName.text.toString().trim()
        val amountStr = edtAmount.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return
        }
        if (amountStr.isBlank()) {
            Toast.makeText(this, "Enter an amount", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount < 0) {
            Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val isFixed = radioType.checkedRadioButtonId == R.id.radioFixed
        val type = if (isFixed) "FIXED" else "PERCENTAGE"

        if (!isFixed && amount > 100) {
            Toast.makeText(this, "Percentage cannot exceed 100", Toast.LENGTH_SHORT).show()
            return
        }

        val data = hashMapOf<String, Any>(
            "type" to type,
            "name" to name,
            "amount" to amount
        )
        if (taxId == null) {
            data["createdAt"] = Date()
        } else {
            data["updatedAt"] = Date()
        }

        if (taxId != null) {
            db.collection("Taxes").document(taxId!!)
                .update(data)
                .addOnSuccessListener {
                    Toast.makeText(this, "Tax updated", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            db.collection("Taxes")
                .add(data)
                .addOnSuccessListener {
                    Toast.makeText(this, "Tax saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun confirmAndDelete() {
        val id = taxId ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove tax")
            .setMessage("Remove this tax or fee?")
            .setPositiveButton("Remove") { _, _ ->
                db.collection("Taxes").document(id)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Tax removed", Toast.LENGTH_SHORT).show()
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
