package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // TAKE PAYMENT
        findViewById<Button>(R.id.btnTakePayment).setOnClickListener {
            startActivity(Intent(this, PaymentActivity::class.java))
        }

        // TRANSACTIONS
        findViewById<Button>(R.id.btnTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionActivity::class.java))
        }

        // SETTLE BATCH
        findViewById<Button>(R.id.btnSettle).setOnClickListener {
            confirmSettle()
        }
    }

    private fun confirmSettle() {
        AlertDialog.Builder(this)
            .setTitle("Settle Batch")
            .setMessage("Are you sure you want to close the batch?")
            .setPositiveButton("YES") { _, _ ->
                settleBatch()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun settleBatch() {

        val json = """
        {
          "Tpn": "11881706541A",
          "RegisterId": "134909005",
          "Authkey": "Qt9N7CxhDs"
        }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Settle")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Settle Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {
                    if (response.isSuccessful && responseText.contains("Approved")) {

                        Toast.makeText(
                            this@MainActivity,
                            "Batch Settled Successfully",
                            Toast.LENGTH_LONG
                        ).show()

                    } else {

                        Toast.makeText(
                            this@MainActivity,
                            "Settle Failed\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }
}

