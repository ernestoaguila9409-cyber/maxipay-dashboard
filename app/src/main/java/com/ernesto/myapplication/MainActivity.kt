package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        findViewById<Button>(R.id.btnTakePayment).setOnClickListener {
            startActivity(Intent(this, PaymentActivity::class.java))
        }

        findViewById<Button>(R.id.btnTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettle).setOnClickListener {
            confirmSettle()
        }

        loadTodayStats()
    }

    override fun onResume() {
        super.onResume()
        loadTodayStats()
    }

    // ✅ DASHBOARD TOTALS
    private fun loadTodayStats() {

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startOfDay = calendar.time

        db.collection("Transactions")
            .whereGreaterThanOrEqualTo("timestamp", startOfDay)
            .get()
            .addOnSuccessListener { documents ->

                var total = 0.0
                var count = 0

                for (doc in documents) {

                    val voided = doc.getBoolean("voided") ?: false
                    val settled = doc.getBoolean("settled") ?: false

                    // Only count NOT voided AND NOT settled
                    if (!voided && !settled) {
                        total += doc.getDouble("amount") ?: 0.0
                        count++
                    }
                }

                txtTodayTotal.text = "Today: $%.2f".format(total)
                txtTodayCount.text = "Transactions: $count"
            }
    }

    // ✅ CONFIRM SETTLE
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

    // ✅ SETTLE BATCH
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

                    try {

                        val jsonObject = org.json.JSONObject(responseText)
                        val resultCode = jsonObject
                            .getJSONObject("GeneralResponse")
                            .getString("ResultCode")

                        if (resultCode == "0") {

                            // 🔥 Get start of today
                            val calendar = Calendar.getInstance()
                            calendar.set(Calendar.HOUR_OF_DAY, 0)
                            calendar.set(Calendar.MINUTE, 0)
                            calendar.set(Calendar.SECOND, 0)
                            calendar.set(Calendar.MILLISECOND, 0)

                            val startOfDay = calendar.time

                            // 🔥 Mark ALL today's transactions as settled
                            db.collection("Transactions")
                                .whereGreaterThanOrEqualTo("timestamp", startOfDay)
                                .get()
                                .addOnSuccessListener { documents ->

                                    for (doc in documents) {
                                        db.collection("Transactions")
                                            .document(doc.id)
                                            .update("settled", true)
                                    }

                                    // Refresh totals AFTER update
                                    loadTodayStats()
                                }

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

                    } catch (e: Exception) {

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

