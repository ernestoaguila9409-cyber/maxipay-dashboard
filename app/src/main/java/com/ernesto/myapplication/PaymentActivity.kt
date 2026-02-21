package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class PaymentActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTotal: TextView
    private lateinit var btnCash: Button
    private lateinit var btnCard: Button

    private var totalAmount = 0.0
    private var batchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        txtTotal = findViewById(R.id.txtTotalPayment)
        btnCash = findViewById(R.id.btnCash)
        btnCard = findViewById(R.id.btnCard)

        totalAmount = intent.getDoubleExtra("total", 0.0)
        batchId = intent.getStringExtra("batchId") ?: ""

        txtTotal.text = "Total: $%.2f".format(totalAmount)

        btnCash.setOnClickListener {
            completePayment("CASH")
        }

        btnCard.setOnClickListener {
            completePayment("CARD")
        }
    }

    private fun completePayment(method: String) {

        val transaction = hashMapOf(
            "timestamp" to Date(),
            "total" to totalAmount,
            "batchId" to batchId,
            "paymentMethod" to method,
            "settled" to false,
            "voided" to false
        )

        db.collection("Transactions")
            .add(transaction)
            .addOnSuccessListener {

                updateBatchTotals()

                Toast.makeText(this, "Payment Successful", Toast.LENGTH_SHORT).show()

                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Payment Failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateBatchTotals() {

        if (batchId.isEmpty()) return

        db.collection("Batches")
            .document(batchId)
            .get()
            .addOnSuccessListener { document ->

                val currentTotal = document.getDouble("total") ?: 0.0
                val currentCount = document.getLong("count") ?: 0

                db.collection("Batches")
                    .document(batchId)
                    .update(
                        mapOf(
                            "total" to currentTotal + totalAmount,
                            "count" to currentCount + 1
                        )
                    )
            }
    }
}