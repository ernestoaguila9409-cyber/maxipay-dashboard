package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // TAKE PAYMENT button
        findViewById<Button>(R.id.btnTakePayment).setOnClickListener {
            startActivity(Intent(this, PaymentActivity::class.java))
        }

        // TRANSACTIONS button
        findViewById<Button>(R.id.btnTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionActivity::class.java))
        }
    }
}

