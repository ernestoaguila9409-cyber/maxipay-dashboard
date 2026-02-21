package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class PaymentActivity : AppCompatActivity() {

    private lateinit var txtPaymentTotal: TextView
    private lateinit var btnConfirmPayment: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        txtPaymentTotal = findViewById(R.id.txtPaymentTotal)
        btnConfirmPayment = findViewById(R.id.btnConfirmPayment)

        val total = intent.getDoubleExtra("TOTAL_AMOUNT", 0.0)

        txtPaymentTotal.text =
            String.format(Locale.US, "Total: $%.2f", total)

        btnConfirmPayment.setOnClickListener {
            Toast.makeText(this, "Payment processed", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}