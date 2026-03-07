package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SplitPaymentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_payment)

        supportActionBar?.title = "Split Payments"

        findViewById<Button>(R.id.btnSplitEvenly).setOnClickListener {
            Toast.makeText(this, "Split Evenly", Toast.LENGTH_SHORT).show()
            // TODO: implement split evenly flow
        }

        findViewById<Button>(R.id.btnByItems).setOnClickListener {
            Toast.makeText(this, "By items", Toast.LENGTH_SHORT).show()
            // TODO: implement by items flow
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }
}
