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
import android.util.Log

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView

    private var currentBatchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        ensureOpenBatch()

        findViewById<Button>(R.id.btnTakePayment).setOnClickListener {

            if (currentBatchId.isEmpty()) {
                Toast.makeText(this, "No open batch", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettle).setOnClickListener {
            startActivity(Intent(this, BatchManagementActivity::class.java))
        }


        loadTodayStats()
    }

    override fun onResume() {
        super.onResume()
        loadTodayStats()
    }

    // ======================================
    // ENSURE OPEN BATCH EXISTS
    // ======================================
    private fun ensureOpenBatch() {

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->

                if (!documents.isEmpty) {

                    currentBatchId = documents.documents[0].id
                    Log.d("BATCH_DEBUG", "Existing batch: $currentBatchId")

                } else {

                    val newBatchId = "BATCH_${System.currentTimeMillis()}"

                    val batchData = hashMapOf(
                        "batchId" to newBatchId,
                        "total" to 0.0,
                        "count" to 0,
                        "closed" to false,
                        "createdAt" to Date()
                    )

                    db.collection("Batches")
                        .document(newBatchId)
                        .set(batchData)
                        .addOnSuccessListener {
                            currentBatchId = newBatchId
                            Log.d("BATCH_DEBUG", "New batch created: $currentBatchId")
                        }
                }
            }
    }

    // ======================================
    // LOAD DASHBOARD TOTALS
    // ======================================
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
                    val amount = doc.getDouble("amount") ?: 0.0
                    val type = doc.getString("type") ?: "SALE"

                    if (!voided && !settled) {

                        if (type == "SALE") {
                            total += amount
                            count++
                        }

                        if (type == "REFUND") {
                            total -= amount
                            count++
                        }
                    }
                }

                txtTodayTotal.text = String.format(Locale.US, "Today: $%.2f", total)
                txtTodayCount.text = "Transactions: $count"
            }
    }

    // ======================================
    // CONFIRM SETTLE
    // ======================================
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

    // ======================================
    // CALL SPIN SETTLE API
    // ======================================
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

                runOnUiThread {

                    if (response.isSuccessful) {

                        closeCurrentBatch()

                    } else {

                        Toast.makeText(
                            this@MainActivity,
                            "Settle Failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // ======================================
    // CLOSE CURRENT BATCH
    // ======================================
    private fun closeCurrentBatch() {

        if (currentBatchId.isEmpty()) return

        db.collection("Batches")
            .document(currentBatchId)
            .update(
                mapOf(
                    "closed" to true,
                    "closedAt" to Date()
                )
            )
            .addOnSuccessListener {

                // Mark transactions as settled
                db.collection("Transactions")
                    .whereEqualTo("settled", false)
                    .get()
                    .addOnSuccessListener { docs ->

                        for (doc in docs) {
                            db.collection("Transactions")
                                .document(doc.id)
                                .update("settled", true)
                        }

                        Toast.makeText(
                            this,
                            "Batch Closed Successfully",
                            Toast.LENGTH_LONG
                        ).show()

                        currentBatchId = ""
                        ensureOpenBatch()
                        loadTodayStats()
                    }
            }
    }
}

