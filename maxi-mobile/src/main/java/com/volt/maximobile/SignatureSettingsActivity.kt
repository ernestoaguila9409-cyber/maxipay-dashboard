package com.volt.maximobile

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SignatureSettingsActivity : AppCompatActivity() {

    private lateinit var rgMode: RadioGroup
    private lateinit var rbNone: RadioButton
    private lateinit var rbReceipt: RadioButton
    private lateinit var rbCustomerDisplay: RadioButton
    private lateinit var txtDescription: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Signature Settings"

        rgMode = findViewById(R.id.rgSignatureMode)
        rbNone = findViewById(R.id.rbSignatureNone)
        rbReceipt = findViewById(R.id.rbSignatureReceipt)
        rbCustomerDisplay = findViewById(R.id.rbSignatureCustomerDisplay)
        txtDescription = findViewById(R.id.txtSignatureDescription)

        when (SignatureSettings.getMode(this)) {
            SignatureSettings.MODE_RECEIPT -> rbReceipt.isChecked = true
            SignatureSettings.MODE_CUSTOMER_DISPLAY -> rbCustomerDisplay.isChecked = true
            else -> rbNone.isChecked = true
        }
        updateDescription()

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbSignatureReceipt -> SignatureSettings.MODE_RECEIPT
                R.id.rbSignatureCustomerDisplay -> SignatureSettings.MODE_CUSTOMER_DISPLAY
                else -> SignatureSettings.MODE_NONE
            }
            SignatureSettings.setMode(this, mode)
            updateDescription()
        }
    }

    private fun updateDescription() {
        txtDescription.text = when {
            rbReceipt.isChecked ->
                "A signature line will be printed on the receipt immediately after a sale. " +
                "Reprints from Transactions or Orders will NOT include the signature line."
            rbCustomerDisplay.isChecked ->
                "After a sale, the customer display will show a signature pad. " +
                "The signature is saved and will appear on reprinted receipts."
            else -> "No signature will be requested after a sale."
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
