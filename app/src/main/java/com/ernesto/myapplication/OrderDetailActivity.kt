package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var txtHeaderEmployee: TextView
    private lateinit var txtHeaderTime: TextView
    private lateinit var txtRefundHistory: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView
    private lateinit var btnCheckout: MaterialButton
    private lateinit var bottomActions: View
    private lateinit var btnVoid: MaterialButton
    private lateinit var btnRefund: MaterialButton

    private lateinit var adapter: OrderItemsAdapter
    private val itemDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var orderId: String
    private var currentBatchId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        orderId = intent.getStringExtra("orderId") ?: ""

        if (orderId.isBlank()) {
            Toast.makeText(this, "Invalid order ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        txtHeaderEmployee = findViewById(R.id.txtHeaderEmployee)
        txtHeaderTime = findViewById(R.id.txtHeaderTime)
        txtRefundHistory = findViewById(R.id.txtRefundHistory)
        recycler = findViewById(R.id.recyclerOrderItems)
        txtEmptyItems = findViewById(R.id.txtEmptyItems)
        btnCheckout = findViewById(R.id.btnCheckout)
        bottomActions = findViewById(R.id.bottomActions)
        btnVoid = findViewById(R.id.btnVoid)
        btnRefund = findViewById(R.id.btnRefund)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = OrderItemsAdapter(itemDocs)
        recycler.adapter = adapter

        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(12, 12, 12, 12)
            }
        })

        loadHeader()
        loadItems()
    }

    override fun onResume() {
        super.onResume()
        loadHeader()
    }

    // ===============================
    // LOAD HEADER
    // ===============================

    private fun loadHeader() {

        // Reset UI first
        btnCheckout.visibility = View.GONE
        bottomActions.visibility = View.GONE
        btnVoid.visibility = View.GONE
        btnRefund.visibility = View.GONE

        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) return@addOnSuccessListener

                // ✅ HEADER DATA (MUST BE INSIDE HERE)
                val employee = doc.getString("employeeName") ?: "Unknown"
                txtHeaderEmployee.text = "Employee: $employee"

                val createdAt = doc.getTimestamp("createdAt")
                if (createdAt != null) {
                    val date = createdAt.toDate()
                    val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                    txtHeaderTime.text = format.format(date)
                } else {
                    txtHeaderTime.text = ""
                }

                val status = doc.getString("status") ?: ""
                currentBatchId = doc.getString("batchId")

                if (status == "OPEN") {
                    btnCheckout.visibility = View.VISIBLE
                    btnCheckout.setOnClickListener {
                        val i = Intent(this, MenuActivity::class.java)
                        i.putExtra("ORDER_ID", orderId)
                        startActivity(i)
                    }
                }

                if (status == "VOIDED") {
                    bottomActions.visibility = View.VISIBLE
                    btnVoid.visibility = View.GONE
                    btnRefund.visibility = View.GONE
                    txtRefundHistory.visibility = View.GONE
                    return@addOnSuccessListener
                }

                val saleTransactionId = doc.getString("saleTransactionId") ?: doc.getString("transactionId")
                val totalInCents = doc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val isFullyRefunded = totalRefundedInCents >= totalInCents && totalInCents > 0L

                loadRefundHistory(saleTransactionId) { totalFromRefunds ->
                    val fullyRefunded = isFullyRefunded || (totalFromRefunds >= totalInCents && totalInCents > 0L)
                    if (refundHistoryLines.isNotEmpty()) {
                        txtRefundHistory.text = refundHistoryLines
                        txtRefundHistory.visibility = View.VISIBLE
                    } else {
                        txtRefundHistory.visibility = View.GONE
                    }
                    if (status == "REFUNDED" || fullyRefunded) {
                        bottomActions.visibility = View.VISIBLE
                        btnVoid.visibility = View.GONE
                        btnRefund.visibility = View.GONE
                        return@loadRefundHistory
                    }
                    if (status == "CLOSED") {
                        bottomActions.visibility = View.VISIBLE
                        if (!fullyRefunded) {
                            btnRefund.visibility = View.VISIBLE
                            btnRefund.setOnClickListener { confirmRefund() }
                        } else {
                            btnRefund.visibility = View.GONE
                        }
                        val batchId = currentBatchId ?: return@loadRefundHistory
                        if (saleTransactionId == null) return@loadRefundHistory
                        db.collection("Transactions").document(saleTransactionId).get()
                            .addOnSuccessListener { txDoc ->
                                val isVoided = txDoc.getBoolean("voided") ?: false
                                if (!isVoided) {
                                    db.collection("Batches").document(batchId).get()
                                        .addOnSuccessListener { batchDoc ->
                                            val batchClosed = batchDoc.getBoolean("closed") ?: true
                                            btnVoid.visibility = if (!batchClosed) View.VISIBLE else View.GONE
                                            if (!batchClosed) btnVoid.setOnClickListener { confirmVoid() }
                                        }
                                } else {
                                    btnVoid.visibility = View.GONE
                                }
                            }
                    }
                }
            }
    }

    private var refundHistoryLines: String = ""

    private fun loadRefundHistory(saleTransactionId: String?, onLoaded: (totalRefundedCents: Long) -> Unit) {
        refundHistoryLines = ""
        if (saleTransactionId.isNullOrBlank()) {
            onLoaded(0L)
            return
        }
        db.collection("Transactions")
            .whereEqualTo("type", "REFUND")
            .whereEqualTo("originalReferenceId", saleTransactionId)
            .get()
            .addOnSuccessListener { snap ->
                var totalCents = 0L
                val lines = mutableListOf<String>()
                val dateFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                for (refundDoc in snap.documents) {
                    val amountCents = refundDoc.getLong("amountInCents")
                        ?: ((refundDoc.getDouble("amount") ?: 0.0) * 100).toLong()
                    totalCents += amountCents
                    val amountStr = String.format(Locale.US, "%.2f", amountCents / 100.0)
                    val by = refundDoc.getString("refundedBy")?.takeIf { it.isNotBlank() } ?: "—"
                    val createdAt = refundDoc.getTimestamp("createdAt")?.toDate()
                    val dateStr = if (createdAt != null) dateFormat.format(createdAt) else ""
                    lines.add("🔵 Refund -$$amountStr by $by $dateStr")
                }
                refundHistoryLines = lines.joinToString("\n")
                onLoaded(totalCents)
            }
            .addOnFailureListener { onLoaded(0L) }
    }
    // ===============================
    // LOAD ITEMS
    // ===============================

    private fun loadItems() {
        db.collection("Orders").document(orderId)
            .collection("items")
            .get()
            .addOnSuccessListener { docs ->
                itemDocs.clear()
                itemDocs.addAll(docs.documents)
                adapter.notifyDataSetChanged()

                if (itemDocs.isEmpty()) {
                    txtEmptyItems.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                } else {
                    txtEmptyItems.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                }
            }
    }

    // ===============================
    // VOID
    // ===============================

    private fun confirmVoid() {

        val batchId = currentBatchId ?: return

        db.collection("Batches")
            .document(batchId)
            .get()
            .addOnSuccessListener { batchDoc ->

                val batchClosed = batchDoc.getBoolean("closed") ?: true

                if (batchClosed) {
                    Toast.makeText(this, "Batch already closed. Cannot void.", Toast.LENGTH_LONG).show()
                    btnVoid.visibility = View.GONE
                    return@addOnSuccessListener
                }

                AlertDialog.Builder(this)
                    .setTitle("Void Order")
                    .setMessage("Void this order?")
                    .setPositiveButton("Void") { _, _ -> executeVoid() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }

    private fun executeVoid() {
        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                val transactionId = orderDoc.getString("transactionId") ?: return@addOnSuccessListener

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->

                        val referenceId = txDoc.getString("referenceId") ?: return@addOnSuccessListener
                        val paymentType = txDoc.getString("paymentType") ?: "Credit"
                        val amountInCents = txDoc.getLong("amountInCents") ?: 0L
                        val amount = amountInCents / 100.0

                        callVoidApi(referenceId, paymentType, amount, transactionId)
                    }
            }
    }

    private fun callVoidApi(
        referenceId: String,
        paymentType: String,
        amount: Double,
        transactionId: String
    ) {

        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", paymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", "11881706541A")
            put("Authkey", "Qt9N7CxhDs")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Void")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@OrderDetailActivity, "Void Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Void error ${response.code}: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    val resultCode = JSONObject(responseText)
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode")

                    if (resultCode == "0") {
                        finalizeVoid(transactionId)
                    } else {
                        Toast.makeText(this@OrderDetailActivity, "Void Declined", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun finalizeVoid(transactionId: String) {

        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                val batchId = orderDoc.getString("batchId") ?: return@addOnSuccessListener
                val amountInCents = orderDoc.getLong("totalInCents") ?: 0L
                val amount = amountInCents / 100.0

                db.runBatch { batch ->

                    val orderRef = db.collection("Orders").document(orderId)
                    val txRef = db.collection("Transactions").document(transactionId)
                    val batchRef = db.collection("Batches").document(batchId)

                    batch.update(txRef, "voided", true)

                    batch.update(orderRef, mapOf(
                        "status" to "VOIDED",
                        "voidedAt" to Date()
                    ))

                    batch.update(batchRef, mapOf(
                        "totalSales" to FieldValue.increment(amount),
                        "netTotal" to FieldValue.increment(amount),
                        "transactionCount" to FieldValue.increment(1)
                    ))
                }
                    .addOnSuccessListener {
                        Toast.makeText(this, "Sale Voided", Toast.LENGTH_LONG).show()
                        finish()
                    }
            }
    }

    private fun confirmRefund() {

        AlertDialog.Builder(this)
            .setTitle("Refund Order")
            .setMessage("Refund this order?")
            .setPositiveButton("Refund") { _, _ -> executeRefund() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun executeRefund() {

        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                if (!orderDoc.exists()) return@addOnSuccessListener

                val status = orderDoc.getString("status") ?: ""
                if (status == "REFUNDED") {
                    Toast.makeText(this, "Order already refunded", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val transactionId = orderDoc.getString("saleTransactionId")
                    ?: orderDoc.getString("transactionId")
                    ?: return@addOnSuccessListener

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->

                        val amountInCents = txDoc.getLong("totalPaidInCents") ?: 0L
                        val payments = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val firstPayment = payments.firstOrNull()
                        val paymentType = firstPayment?.get("paymentType")?.toString() ?: "Credit"
                        val referenceId = firstPayment?.get("referenceId")?.toString()
                            ?: firstPayment?.get("terminalReference")?.toString()

                        if (paymentType.equals("Cash", ignoreCase = true)) {
                            finalizeRefund(transactionId, amountInCents)
                            return@addOnSuccessListener
                        }

                        if (!referenceId.isNullOrBlank()) {
                            callRefundApi(
                                referenceId = referenceId,
                                paymentType = paymentType,
                                amount = amountInCents / 100.0,
                                originalTransactionId = transactionId,
                                amountInCents = amountInCents
                            )
                        } else {
                            Toast.makeText(this, "Cannot refund: no reference for this transaction.", Toast.LENGTH_LONG).show()
                        }
                    }
            }
    }
    private fun callRefundApi(
        referenceId: String,
        paymentType: String,
        amount: Double,
        originalTransactionId: String,
        amountInCents: Long
    ) {

        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", paymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", "11881706541A")
            put("Authkey", "Qt9N7CxhDs")
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Return")
            .post(body)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "Refund Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Refund error ${response.code}: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    try {
                        val jsonResponse = JSONObject(responseText)
                        val general = jsonResponse.optJSONObject("GeneralResponse")
                        val resultCode = general?.optString("ResultCode") ?: ""

                        if (resultCode == "0") {
                            finalizeRefund(originalTransactionId, amountInCents)
                        } else {
                            Toast.makeText(
                                this@OrderDetailActivity,
                                "Refund Declined",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Refund parse error: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }
    private fun finalizeRefund(
        originalTransactionId: String,
        amountInCents: Long
    ) {

        // 1️⃣ Find open batch
        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { batchSnap ->

                if (batchSnap.isEmpty) {
                    Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val openBatch = batchSnap.documents.first()
                val openBatchId = openBatch.id

                val orderRef = db.collection("Orders").document(orderId)
                orderRef.get()
                    .addOnSuccessListener { orderDoc ->
                        if (!orderDoc.exists()) return@addOnSuccessListener
                        val employeeName = orderDoc.getString("employeeName") ?: ""

                        val newRefundTxRef = db.collection("Transactions").document()
                        val batchRef = db.collection("Batches").document(openBatchId)

                        val refundDocData = mapOf(
                            "orderId" to orderId,
                            "type" to "REFUND",
                            "amount" to (amountInCents / 100.0),
                            "amountInCents" to amountInCents,
                            "originalReferenceId" to originalTransactionId,
                            "createdAt" to Date(),
                            "batchId" to openBatchId,
                            "voided" to false,
                            "settled" to false,
                            "refundedBy" to employeeName
                        )
                        db.collection("Transactions").document(newRefundTxRef.id).set(refundDocData)
                            .addOnSuccessListener {
                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                val newTotalRefunded = currentRefunded + amountInCents
                                val orderUpdates = mutableMapOf<String, Any>(
                                    "totalRefundedInCents" to newTotalRefunded,
                                    "refundedAt" to Date()
                                )
                                if (newTotalRefunded >= totalInCents) {
                                    orderUpdates["status"] = "REFUNDED"
                                }
                                orderRef.update(orderUpdates)
                                    .addOnSuccessListener {
                                        db.collection("Batches").document(openBatchId)
                                            .update(mapOf(
                                                "totalRefundsInCents" to FieldValue.increment(amountInCents),
                                                "netTotalInCents" to FieldValue.increment(-amountInCents),
                                                "transactionCount" to FieldValue.increment(1)
                                            ))
                                            .addOnSuccessListener {
                                                Toast.makeText(this, "Order Refunded", Toast.LENGTH_LONG).show()
                                                finish()
                                            }
                                    }
                            }
                    }
            }
    }
}