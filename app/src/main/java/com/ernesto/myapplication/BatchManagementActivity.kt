package com.ernesto.myapplication

import android.content.Intent
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
import java.text.SimpleDateFormat
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

    // 🔹 LOAD OPEN TRANSACTIONS
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

    // 🔹 LOAD CLOSED BATCHES
    private fun loadClosedBatches() {

        db.collection("Batches")
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { documents ->

                val batchList = mutableListOf<HashMap<String, Any>>()

                for (doc in documents) {

                    val batchId = doc.getString("batchId") ?: ""
                    val total = doc.getDouble("total") ?: 0.0
                    val count = doc.getLong("transactionCount") ?: 0
                    val date = doc.getDate("timestamp")

                    val formattedDate = SimpleDateFormat(
                        "MM/dd/yyyy HH:mm",
                        Locale.getDefault()
                    ).format(date ?: Date())

                    val batchMap = hashMapOf<String, Any>(
                        "batchId" to batchId,
                        "total" to total,
                        "transactionCount" to count,
                        "formattedDate" to formattedDate
                    )

                    batchList.add(batchMap)
                }

                recyclerBatches.adapter =
                    BatchListAdapter(batchList) { selectedBatchId ->
                        val intent = Intent(
                            this,
                            BatchDetailsActivity::class.java
                        )
                        intent.putExtra("batchId", selectedBatchId)
                        startActivity(intent)
                    }
            }
    }

    // 🔹 CONFIRM CLOSE
    private fun confirmCloseBatch() {
        AlertDialog.Builder(this)
            .setTitle("Close Batch")
            .setMessage("Are you sure you want to close the open batch?")
            .setPositiveButton("YES") { _, _ ->
                sendSettleRequest()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 🔥 SEND SETTLEMENT REQUEST TO Z8
    private fun sendSettleRequest() {

        val referenceId = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

        val json = """
{
  "ReferenceId": "$referenceId",
  "GetReceipt": false,
  "SettlementType": "Close",
  "Tpn": "11881706541A",
  "RegisterId": "134909005",
  "Authkey": "Qt9N7CxhDs",
  "SPInProxyTimeout": null,
  "CustomFields": {}
}
""".trimIndent()

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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
                        "Settlement Failed: ${e.message}",
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

                            Toast.makeText(
                                this@BatchManagementActivity,
                                "Z8 Batch Closed Successfully",
                                Toast.LENGTH_LONG
                            ).show()

                            closeBatchInFirebase()

                        } else {

                            Toast.makeText(
                                this@BatchManagementActivity,
                                "Settlement Failed\n$responseText",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {

                        Toast.makeText(
                            this@BatchManagementActivity,
                            "Settlement Parse Error\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // 🔹 CLOSE BATCH IN FIREBASE AFTER Z8 CONFIRMS
    private fun closeBatchInFirebase() {

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
                val batchId = UUID.randomUUID().toString().take(8)

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
                            "Batch Saved to Cloud",
                            Toast.LENGTH_LONG
                        ).show()

                        loadOpenBatch()
                        loadClosedBatches()
                    }
            }
    }
}
