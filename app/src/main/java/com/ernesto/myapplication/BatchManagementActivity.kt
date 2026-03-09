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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
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

    private var hasOpenPreAuths = false

    // 🔹 LOAD OPEN TRANSACTIONS (UNSETTLED, NOT VOIDED)
    private fun loadOpenBatch() {
        db.collection("Transactions")
            .whereEqualTo("settled", false)
            .whereEqualTo("voided", false)
            .get()
            .addOnSuccessListener { documents ->
                var settleable = 0
                var openTotal = 0.0
                var preAuthCount = 0

                for (doc in documents) {
                    val type = doc.getString("type") ?: "SALE"
                    if (type == "PRE_AUTH") {
                        preAuthCount++
                    } else {
                        settleable++
                        openTotal += computeNetAmount(doc)
                    }
                }

                openTransactionCount = settleable
                hasOpenPreAuths = preAuthCount > 0

                val label = StringBuilder()
                label.append(String.format(Locale.US, "Open Transactions: %d | Total: $%.2f", settleable, openTotal))
                if (preAuthCount > 0) {
                    label.append(String.format(Locale.US, "\nOpen Pre-Auths: %d (capture or void before closing)", preAuthCount))
                }
                txtOpenBatch.text = label.toString()

                btnCloseBatch.isEnabled = settleable > 0
            }
    }

    // 🔹 LOAD CLOSED BATCHES
    private fun loadClosedBatches() {

        db.collection("Batches")
            .whereEqualTo("closed", true)
            .get()
            .addOnSuccessListener { documents ->

                val batchList = mutableListOf<HashMap<String, Any>>()

                for (doc in documents) {

                    val total = doc.getDouble("totalSales") ?: 0.0
                    val count = doc.getLong("transactionCount") ?: 0
                    val date = doc.getDate("closedAt")

                    val formattedDate = SimpleDateFormat(
                        "MM/dd/yyyy HH:mm",
                        Locale.getDefault()
                    ).format(date ?: Date())

                    val batchMap = hashMapOf<String, Any>(
                        "batchId" to doc.id,
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
        if (hasOpenPreAuths) {
            AlertDialog.Builder(this)
                .setTitle("Open Pre-Auths")
                .setMessage("There are uncaptured pre-authorizations. Capture or void all open bar tabs before closing the batch.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

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

        val tpn = TerminalPrefs.getTpn(this)
        val registerId = TerminalPrefs.getRegisterId(this)
        val authKey = TerminalPrefs.getAuthKey(this)

        val jsonObj = JSONObject().apply {
            put("PrintReceipt", false)
            put("GetReceipt", false)
            put("SettlementType", "Force")
            put("Tpn", tpn)
            put("RegisterId", registerId)
            put("Authkey", authKey)
        }
        val json = jsonObj.toString()

        Log.d("SETTLE_REQUEST", json)

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
                Log.e("SETTLE_ERROR", "Network error: ${e.message}", e)
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
                Log.d("SETTLE_RESPONSE", responseText)

                runOnUiThread {

                    try {

                        val jsonObject = JSONObject(responseText)
                        val generalResponse = jsonObject.optJSONObject("GeneralResponse")
                        val resultCode = generalResponse?.optString("ResultCode", "-1") ?: "-1"
                        val resultText = generalResponse?.optString("DetailedMessage", "") ?: ""

                        val settleDetails = jsonObject.optJSONArray("SettleDetails")
                        val hostApproved = settleDetails?.let { arr ->
                            (0 until arr.length()).any { arr.getJSONObject(it).optString("HostStatus") == "0" }
                        } ?: false

                        if (resultCode == "0" || hostApproved) {

                            val msg = if (hostApproved && resultCode != "0") {
                                val detail = settleDetails?.optJSONObject(0)?.optString("DetailedMessage", "") ?: ""
                                "Z8 Batch Closed Successfully" + if (detail.isNotBlank()) "\n($detail)" else ""
                            } else {
                                "Z8 Batch Closed Successfully"
                            }

                            Toast.makeText(
                                this@BatchManagementActivity,
                                msg,
                                Toast.LENGTH_LONG
                            ).show()

                            closeBatchInFirebase()

                        } else {
                            val msg = if (resultText.isNotBlank()) resultText else "Code: $resultCode"
                            Log.w("SETTLE_ERROR", "Settlement declined – $msg\n$responseText")
                            Toast.makeText(
                                this@BatchManagementActivity,
                                "Settlement Failed: $msg",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {
                        Log.e("SETTLE_ERROR", "Parse error: ${e.message}\n$responseText", e)
                        Toast.makeText(
                            this@BatchManagementActivity,
                            "Settlement Parse Error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // 🔹 CLOSE BATCH IN FIREBASE AFTER Z8 CONFIRMS
    private fun closeBatchInFirebase() {

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { batchSnap ->

                if (batchSnap.isEmpty) {
                    val newBatchId = "BATCH_${System.currentTimeMillis()}"
                    val batchData = hashMapOf(
                        "batchId" to newBatchId,
                        "total" to 0.0,
                        "count" to 0,
                        "closed" to false,
                        "createdAt" to Date(),
                        "type" to "OPEN"
                    )
                    db.collection("Batches").document(newBatchId).set(batchData)
                        .addOnSuccessListener { settleOpenBatch(newBatchId) }
                        .addOnFailureListener {
                            Toast.makeText(this, "Failed to create batch: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    return@addOnSuccessListener
                }

                val batchId = batchSnap.documents.first().id
                settleOpenBatch(batchId)
            }
    }

    private fun settleOpenBatch(batchId: String) {
        val batchRef = db.collection("Batches").document(batchId)

        db.collection("Transactions")
            .whereEqualTo("settled", false)
            .whereEqualTo("voided", false)
            .get()
            .addOnSuccessListener { transactions ->

                val batchWrite = db.batch()

                var totalSales = 0.0
                var count = 0

                for (tx in transactions) {

                    val net = computeNetAmount(tx)
                    if (net != 0.0) {
                        totalSales += net
                        count++
                    }

                    batchWrite.update(
                        tx.reference,
                        mapOf(
                            "settled" to true,
                            "batchId" to batchId
                        )
                    )
                }

                batchWrite.update(
                    batchRef,
                    mapOf(
                        "closed" to true,
                        "closedAt" to Date(),
                        "totalSales" to totalSales,
                        "transactionCount" to count,
                        "type" to "SETTLEMENT"
                    )
                )

                batchWrite.commit()
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

    // 🔹 Helper to compute net amount for a transaction document
    // SALE  → positive amount
    // REFUND → negative amount
    private fun computeNetAmount(doc: DocumentSnapshot): Double {
        val type = doc.getString("type") ?: "SALE"

        return if (type == "SALE" || type == "CAPTURE") {
            // New schema: totalPaidInCents / totalPaid (multi‑payment)
            val cents = doc.getLong("totalPaidInCents")
            if (cents != null) {
                cents / 100.0
            } else {
                doc.getDouble("totalPaid")
                    ?: doc.getDouble("amount")
                    ?: 0.0
            }
        } else if (type == "REFUND") {
            // Refunds always store a positive amount; treat as negative
            -(doc.getDouble("amount") ?: 0.0)
        } else {
            0.0
        }
    }
}
