package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.ernesto.myapplication.payments.SpinApiUrls
import com.ernesto.myapplication.payments.SpinCallTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
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

    companion object {
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val db = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var txtOpenBatch: TextView
    private lateinit var btnCloseBatch: Button
    private lateinit var recyclerBatches: RecyclerView
    private lateinit var progressBar: ProgressBar

    private var openTransactionCount = 0
    private var isSettling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_management)

        txtOpenBatch = findViewById(R.id.txtOpenBatch)
        btnCloseBatch = findViewById(R.id.btnCloseBatch)
        recyclerBatches = findViewById(R.id.recyclerBatches)
        progressBar = findViewById(R.id.progressBatchMgmt)

        recyclerBatches.layoutManager = LinearLayoutManager(this)

        btnCloseBatch.setOnClickListener {
            confirmCloseBatch()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isSettling) {
            loadOpenBatch()
            loadClosedBatches()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
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

                val displayTotal = if (openTotal < 0.005) 0.0 else openTotal
                val label = StringBuilder()
                label.append(String.format(Locale.US, "Open Transactions: %d | Total: $%.2f", settleable, displayTotal))
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

    private fun setSettlingState(settling: Boolean) {
        isSettling = settling
        btnCloseBatch.isEnabled = !settling && openTransactionCount > 0
        progressBar.visibility = if (settling) View.VISIBLE else View.GONE
    }

    private fun confirmCloseBatch() {
        if (isSettling) return

        if (hasOpenPreAuths) {
            AlertDialog.Builder(this)
                .setTitle("Open Pre-Auths")
                .setMessage("There are uncaptured pre-authorizations. Capture or void all open bar tabs before closing the batch.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Receipt tip mode: warn if card sales have not had tips finalized (Add Tip / confirm $0.00).
        if (TipConfig.isTipsEnabled(this) && !TipConfig.isTipOnCustomerScreen(this)) {
            db.collection("Transactions")
                .whereEqualTo("settled", false)
                .whereEqualTo("voided", false)
                .get()
                .addOnSuccessListener { snapshots ->
                    val untipped = countUnsettledReceiptTipTransactions(snapshots)
                    if (untipped > 0) {
                        AlertDialog.Builder(this)
                            .setTitle("Tips not finalized")
                            .setMessage(
                                "You have $untipped card transaction(s) where the tip has not been added or confirmed on the transaction yet (including \$0.00). Use Tip adjustment to finalize, or close the batch anyway."
                            )
                            .setNegativeButton("Cancel", null)
                            .setNeutralButton("Tip adjustment") { _, _ ->
                                startActivity(Intent(this, TipAdjustmentActivity::class.java))
                            }
                            .setPositiveButton("Close anyway") { _, _ ->
                                showCloseBatchConfirmDialog()
                            }
                            .show()
                    } else {
                        showCloseBatchConfirmDialog()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(
                        this,
                        "Could not verify tips: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            return
        }

        showCloseBatchConfirmDialog()
    }

    private fun showCloseBatchConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Close Batch")
            .setMessage("Are you sure you want to close the open batch?")
            .setPositiveButton("YES") { _, _ ->
                sendSettleRequest()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Same eligibility as [TipAdjustmentActivity]: open SALE/CAPTURE with credit/debit payment
     * and a gateway reference for tip adjust.
     */
    private fun isTipAdjustableCardTransaction(doc: DocumentSnapshot): Boolean {
        val type = doc.getString("type") ?: "SALE"
        if (type != "SALE" && type != "CAPTURE") return false
        val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
        val hasCard = payments.any { p ->
            val m = p as? Map<*, *> ?: return@any false
            val pt = (m["paymentType"] as? String) ?: ""
            val status = (m["status"] as? String) ?: ""
            (pt.equals("Credit", true) || pt.equals("Debit", true)) &&
                !status.equals("VOIDED", true)
        }
        if (!hasCard) return false
        var gatewayRefId = ""
        for (p in payments) {
            val m = p as? Map<*, *> ?: continue
            val pt = (m["paymentType"] as? String) ?: ""
            val st = (m["status"] as? String) ?: ""
            if ((pt.equals("Credit", true) || pt.equals("Debit", true)) &&
                !st.equals("VOIDED", true)
            ) {
                gatewayRefId = (m["referenceId"] as? String) ?: ""
                break
            }
        }
        return gatewayRefId.isNotBlank()
    }

    /** Card transactions that still need tip entry or confirmation (tipAdjusted is false). */
    private fun countUnsettledReceiptTipTransactions(snapshots: QuerySnapshot): Int {
        var n = 0
        for (doc in snapshots.documents) {
            if (!isTipAdjustableCardTransaction(doc)) continue
            val tipAdjusted = doc.getBoolean("tipAdjusted") ?: false
            if (!tipAdjusted) n++
        }
        return n
    }

    private fun sendSettleRequest() {
        setSettlingState(true)

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

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SpinApiUrls.settle(this))
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        SpinCallTracker.beginCall()
        httpClient.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.e("SETTLE_ERROR", "Network error: ${e.message}", e)
                runOnUiThread {
                    setSettlingState(false)
                    Toast.makeText(
                        this@BatchManagementActivity,
                        "Settlement Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                SpinCallTracker.endCall()

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
                                "Batch closed successfully" + if (detail.isNotBlank()) "\n($detail)" else ""
                            } else {
                                "Batch closed successfully"
                            }

                            Toast.makeText(
                                this@BatchManagementActivity,
                                msg,
                                Toast.LENGTH_LONG
                            ).show()

                            closeBatchInFirebase()

                        } else {
                            setSettlingState(false)
                            val msg = if (resultText.isNotBlank()) resultText else "Code: $resultCode"
                            Log.w("SETTLE_ERROR", "Settlement declined – $msg\n$responseText")
                            Toast.makeText(
                                this@BatchManagementActivity,
                                "Settlement Failed: $msg",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {
                        setSettlingState(false)
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

    // 🔹 CLOSE BATCH IN FIREBASE AFTER PROCESSOR SETTLE CONFIRMS
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
                        "type" to "OPEN",
                        "transactionCounter" to 0
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
        scope.launch {
            try {
                val batchRef = db.collection("Batches").document(batchId)

                val transactions = withContext(Dispatchers.IO) {
                    db.collection("Transactions")
                        .whereEqualTo("settled", false)
                        .whereEqualTo("voided", false)
                        .get()
                        .await()
                }

                val batchWrite = db.batch()
                var totalSales = 0.0
                var totalTipsCents = 0L
                var count = 0

                for (tx in transactions) {
                    val net = computeNetAmount(tx)
                    if (net != 0.0) {
                        totalSales += net
                        count++
                    }

                    val type = tx.getString("type") ?: "SALE"
                    if (type == "SALE" || type == "CAPTURE") {
                        totalTipsCents += tx.getLong("tipAmountInCents") ?: 0L
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
                        "totalTipsInCents" to totalTipsCents,
                        "transactionCount" to count,
                        "type" to "SETTLEMENT"
                    )
                )

                withContext(Dispatchers.IO) {
                    batchWrite.commit().await()
                }

                val newBatchId = "BATCH_${System.currentTimeMillis()}"
                val newBatchData = hashMapOf(
                    "batchId" to newBatchId,
                    "total" to 0.0,
                    "count" to 0,
                    "closed" to false,
                    "createdAt" to Date(),
                    "type" to "OPEN",
                    "transactionCounter" to 0
                )
                withContext(Dispatchers.IO) {
                    db.collection("Batches").document(newBatchId).set(newBatchData).await()
                }

                setSettlingState(false)

                val cashFlowIntent = Intent(this@BatchManagementActivity, CashFlowActivity::class.java).apply {
                    putExtra(CashFlowActivity.EXTRA_BATCH_ID, batchId)
                    putExtra(CashFlowActivity.EXTRA_BATCH_CLOSED, true)
                }
                startActivity(cashFlowIntent)
            } catch (e: Exception) {
                Log.e("SETTLE_BATCH", "Failed to settle batch in Firebase: ${e.message}", e)
                setSettlingState(false)
                Toast.makeText(
                    this@BatchManagementActivity,
                    "Failed to close batch: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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
