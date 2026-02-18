package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class BatchManagementActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtOpenBatch: TextView
    private lateinit var btnCloseBatch: Button
    private lateinit var recyclerBatches: RecyclerView

    private var openTransactionCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_management)

        txtOpenBatch = findViewById(R.id.txtOpenBatch)
        btnCloseBatch = findViewById(R.id.btnCloseBatch)
        recyclerBatches = findViewById(R.id.recyclerBatches)

        recyclerBatches.layoutManager = LinearLayoutManager(this)

        loadOpenBatch()
        loadClosedBatches()

        btnCloseBatch.setOnClickListener {
            confirmCloseBatch()
        }
    }

    override fun onResume() {
        super.onResume()
        loadOpenBatch()
        loadClosedBatches()
    }

    // ✅ LOAD OPEN TRANSACTIONS
    private fun loadOpenBatch() {

        db.collection("Transactions")
            .whereEqualTo("settled", false)
            .whereEqualTo("voided", false)
            .get()
            .addOnSuccessListener { documents ->

                openTransactionCount = documents.size()

                txtOpenBatch.text = "Open Transactions: $openTransactionCount"

                btnCloseBatch.isEnabled = openTransactionCount > 0
            }
    }

    // ✅ LOAD CLOSED BATCHES
    private fun loadClosedBatches() {

        db.collection("Batches")
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { documents ->

                val batchList = mutableListOf<BatchItem>()

                for (doc in documents) {

                    val batchId = doc.getString("batchId") ?: ""
                    val total = doc.getDouble("total") ?: 0.0
                    val count = doc.getLong("transactionCount") ?: 0
                    val timestamp = doc.getDate("timestamp")

                    val formattedDate = if (timestamp != null) {
                        java.text.SimpleDateFormat(
                            "MM/dd/yyyy HH:mm",
                            java.util.Locale.getDefault()
                        ).format(timestamp)
                    } else {
                        ""
                    }

                    val summary = "Transactions: $count | Total: $%.2f".format(total)

                    batchList.add(
                        BatchItem(
                            batchId = batchId.take(8),
                            date = formattedDate,
                            summary = summary
                        )
                    )
                }

                recyclerBatches.adapter = BatchListAdapter(batchList)
            }
    }


    // ✅ CONFIRM CLOSE
    private fun confirmCloseBatch() {
        AlertDialog.Builder(this)
            .setTitle("Close Batch")
            .setMessage("Are you sure you want to close the open batch?")
            .setPositiveButton("YES") { _, _ ->
                settleBatchOnTerminal()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ✅ REAL TERMINAL SETTLE CALL
    private fun settleBatchOnTerminal() {

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
                        this@BatchManagementActivity,
                        "Settle Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    try {

                        val jsonObject = JSONObject(responseText)
                        val resultCode = jsonObject
                            .getJSONObject("GeneralResponse")
                            .getString("ResultCode")

                        if (resultCode == "0") {

                            // Only update Firestore if terminal approved
                            markTransactionsAsSettled()

                        } else {

                            Toast.makeText(
                                this@BatchManagementActivity,
                                "Terminal Rejected Close\n$responseText",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {

                        Toast.makeText(
                            this@BatchManagementActivity,
                            "Settle Error\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // ✅ UPDATE FIRESTORE AFTER TERMINAL SUCCESS
    private fun markTransactionsAsSettled() {

        db.collection("Transactions")
            .whereEqualTo("settled", false)
            .whereEqualTo("voided", false)
            .get()
            .addOnSuccessListener { documents ->

                if (documents.isEmpty) {
                    Toast.makeText(this, "No open transactions", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                var total = 0.0
                val batchId = UUID.randomUUID().toString()

                for (doc in documents) {
                    total += doc.getDouble("amount") ?: 0.0

                    db.collection("Transactions")
                        .document(doc.id)
                        .update(
                            mapOf(
                                "settled" to true,
                                "batchId" to batchId
                            )
                        )
                }

                val batchMap = hashMapOf(
                    "batchId" to batchId,
                    "transactionCount" to documents.size(),
                    "total" to total,
                    "timestamp" to Date()
                )

                db.collection("Batches")
                    .add(batchMap)
                    .addOnSuccessListener {

                        Toast.makeText(
                            this,
                            "Batch Closed Successfully",
                            Toast.LENGTH_LONG
                        ).show()

                        loadOpenBatch()
                        loadClosedBatches()
                    }
            }
    }
}
