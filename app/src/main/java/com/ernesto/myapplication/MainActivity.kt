package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Firebase Firestore
        db = FirebaseFirestore.getInstance()

        val button = findViewById<Button>(R.id.btnPayment)

        button.setOnClickListener {
            val intent = Intent(this, PaymentActivity::class.java)
            startActivity(intent)
        }
    }

    // Function to save a transaction to Firebase
    private fun saveTransactionToFirebase(
        amount: Double,
        type: String,
        reference: String
    ) {

        val transaction = hashMapOf(
            "amount" to amount,
            "type" to type,
            "reference" to reference,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("transactions")
            .add(transaction)
            .addOnSuccessListener {
                Toast.makeText(this, "Transaction saved!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}

