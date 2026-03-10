package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.functions.FirebaseFunctions

class ReceiptOptionsActivity : AppCompatActivity() {

    private var orderId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_options)

        orderId = intent.getStringExtra("ORDER_ID")

        val optionsContainer = findViewById<LinearLayout>(R.id.optionsContainer)
        val emailFormContainer = findViewById<LinearLayout>(R.id.emailFormContainer)

        findViewById<LinearLayout>(R.id.btnEmailReceipt).setOnClickListener {
            optionsContainer.visibility = View.GONE
            emailFormContainer.visibility = View.VISIBLE
        }

        findViewById<LinearLayout>(R.id.btnSkipReceipt).setOnClickListener {
            goToMainScreen()
        }

        findViewById<Button>(R.id.btnSendReceipt).setOnClickListener {
            val email = findViewById<EditText>(R.id.etReceiptEmail).text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter an email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val oid = orderId ?: ""
            sendReceiptEmail(email, oid)
        }

        findViewById<Button>(R.id.btnBackToOptions).setOnClickListener {
            emailFormContainer.visibility = View.GONE
            optionsContainer.visibility = View.VISIBLE
        }
    }

    private fun goToMainScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun sendReceiptEmail(email: String, orderId: String) {
        val functions = FirebaseFunctions.getInstance()

        val data = hashMapOf(
            "email" to email,
            "orderId" to orderId
        )

        val btnSend = findViewById<Button>(R.id.btnSendReceipt)
        btnSend.isEnabled = false
        btnSend.text = "Sending…"

        functions
            .getHttpsCallable("sendReceiptEmail")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Log.d("Receipt", "Email sent successfully")
                    Toast.makeText(this, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                    goToMainScreen()
                } else {
                    Log.e("Receipt", "Cloud function returned failure: $response")
                    Toast.makeText(this, "Failed to send receipt", Toast.LENGTH_SHORT).show()
                    btnSend.isEnabled = true
                    btnSend.text = "Send Receipt"
                }
            }
            .addOnFailureListener { e ->
                Log.e("Receipt", "Error sending email", e)
                Toast.makeText(this, "Failed to send receipt. Please try again.", Toast.LENGTH_SHORT).show()
                btnSend.isEnabled = true
                btnSend.text = "Send Receipt"
            }
    }
}
