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
import com.ernesto.myapplication.data.TransactionPayment
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
        adapter = OrderItemsAdapter(itemDocs) { itemDoc -> onOrderItemClick(itemDoc) }
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
                val status = doc.getString("status") ?: ""
                val employee = doc.getString("employeeName") ?: "Unknown"
                if (status == "VOIDED") {
                    val voidedBy = doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: "—"
                    txtHeaderEmployee.text = "Voided by: $voidedBy"
                    val voidedAt = doc.getTimestamp("voidedAt")
                    if (voidedAt != null) {
                        val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                        txtHeaderTime.text = format.format(voidedAt.toDate())
                    } else {
                        txtHeaderTime.text = ""
                    }
                } else {
                    txtHeaderEmployee.text = "Employee: $employee"
                    val createdAt = doc.getTimestamp("createdAt")
                    if (createdAt != null) {
                        val date = createdAt.toDate()
                        val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                        txtHeaderTime.text = format.format(date)
                    } else {
                        txtHeaderTime.text = ""
                    }
                }

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
                        resolveBatchAndShowVoid(saleTransactionId)
                    }
                }
            }
    }

    private var refundHistoryLines: String = ""

    /**
     * For CLOSED orders: resolve batchId (from order or transaction), load Batch doc,
     * then show VOID button only when batch.closed == false (transaction not settled).
     */
    private fun resolveBatchAndShowVoid(saleTransactionId: String?) {
        val batchIdFromOrder = currentBatchId?.takeIf { it.isNotBlank() }
        if (!batchIdFromOrder.isNullOrBlank()) {
            loadBatchAndUpdateVoidButton(batchIdFromOrder)
            return
        }
        if (saleTransactionId.isNullOrBlank()) {
            btnVoid.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(saleTransactionId).get()
            .addOnSuccessListener { txDoc ->
                val batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                if (!batchId.isNullOrBlank()) {
                    currentBatchId = batchId
                    loadBatchAndUpdateVoidButton(batchId)
                } else {
                    btnVoid.visibility = View.GONE
                }
            }
            .addOnFailureListener { btnVoid.visibility = View.GONE }
    }

    private fun loadBatchAndUpdateVoidButton(batchId: String) {
        db.collection("Batches").document(batchId).get()
            .addOnSuccessListener { batchDoc ->
                val batchClosed = batchDoc.getBoolean("closed") ?: true
                if (!batchClosed) {
                    btnVoid.visibility = View.VISIBLE
                    btnVoid.setOnClickListener { confirmVoid() }
                } else {
                    btnVoid.visibility = View.GONE
                }
            }
            .addOnFailureListener { btnVoid.visibility = View.GONE }
    }

    private fun getPaymentTypeFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return (first?.get("paymentType")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("paymentType")?.takeIf { it.isNotBlank() }).orEmpty()
    }

    private fun getReferenceIdFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return first?.get("referenceId")?.toString()?.takeIf { it.isNotBlank() }
            ?: first?.get("terminalReference")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("referenceId")?.takeIf { it.isNotBlank() }
            ?: ""
    }

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
                    val itemName = refundDoc.getString("refundedItemName")?.takeIf { it.isNotBlank() }
                    val line = if (itemName != null) {
                        "🔵 Refund $itemName -$$amountStr by $by $dateStr"
                    } else {
                        "🔵 Refund -$$amountStr by $by $dateStr"
                    }
                    lines.add(line)
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
                val transactionId = orderDoc.getString("saleTransactionId")
                    ?: orderDoc.getString("transactionId")
                    ?: run {
                        Toast.makeText(this, "No transaction linked to this order.", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->
                        if (!txDoc.exists()) {
                            Toast.makeText(this, "Transaction not found.", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }
                        @Suppress("UNCHECKED_CAST")
                        val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val payments = paymentsRaw.map { p ->
                            val amountCents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
                            TransactionPayment(
                                paymentType = p["paymentType"]?.toString() ?: "",
                                cardBrand = p["cardBrand"]?.toString() ?: "",
                                last4 = p["last4"]?.toString() ?: "",
                                entryType = p["entryType"]?.toString() ?: "",
                                amountInCents = amountCents,
                                referenceId = p["referenceId"]?.toString() ?: p["terminalReference"]?.toString() ?: "",
                                clientReferenceId = p["clientReferenceId"]?.toString() ?: "",
                                batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                                transactionNumber = (p["transactionNumber"] as? Number)?.toString() ?: p["transactionNumber"]?.toString() ?: "",
                                paymentId = p["paymentId"]?.toString() ?: ""
                            )
                        }.ifEmpty {
                            val firstRef = getReferenceIdFromTransaction(txDoc)
                            val firstType = getPaymentTypeFromTransaction(txDoc).ifBlank { "Credit" }
                            val amountCents = txDoc.getLong("totalPaidInCents") ?: 0L
                            listOf(
                                TransactionPayment(
                                    paymentType = firstType,
                                    amountInCents = amountCents,
                                    referenceId = firstRef,
                                    clientReferenceId = txDoc.getString("clientReferenceId") ?: "",
                                    batchNumber = txDoc.getString("batchNumber") ?: "",
                                    transactionNumber = (txDoc.get("transactionNumber") as? Number)?.toString() ?: ""
                                )
                            )
                        }

                        val cashPayments = payments.filter { it.paymentType.equals("Cash", true) }
                        val cardPayments = payments.filter { !it.paymentType.equals("Cash", true) }
                        val totalCashCents = cashPayments.sumOf { it.amountInCents }
                        val totalCashDollars = totalCashCents / 100.0

                        if (totalCashCents > 0) {
                            AlertDialog.Builder(this)
                                .setTitle("Cash return required")
                                .setMessage("Return $%.2f in cash to the customer before completing the void.".format(totalCashDollars))
                                .setPositiveButton("I have returned the cash") { _, _ ->
                                    runVoidSequence(transactionId, cardPayments)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            runVoidSequence(transactionId, cardPayments)
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load transaction: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun runVoidSequence(txDocId: String, cardPayments: List<TransactionPayment>) {
        if (cardPayments.isEmpty()) {
            finalizeVoid(txDocId)
            return
        }
        voidCardPaymentsSequentially(txDocId, cardPayments, 0)
    }

    private fun voidCardPaymentsSequentially(
        txDocId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        if (index >= cardPayments.size) {
            finalizeVoid(txDocId)
            return
        }
        val payment = cardPayments[index]
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        if (refId.isBlank()) {
            Toast.makeText(this, "Cannot void: no ReferenceId for card payment.", Toast.LENGTH_LONG).show()
            return
        }
        callVoidApiForPayment(payment, txDocId, cardPayments, index)
    }

    private fun callVoidApiForPayment(
        payment: TransactionPayment,
        transactionId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        val amount = payment.amountInCents / 100.0
        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", payment.paymentType.ifBlank { "Credit" })
            put("ReferenceId", refId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", "11881706541A")
            put("Authkey", "Qt9N7CxhDs")
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
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
                        voidCardPaymentsSequentially(transactionId, cardPayments, index + 1)
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
                var batchId = orderDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                if (batchId.isNullOrBlank()) {
                    db.collection("Transactions").document(transactionId).get()
                        .addOnSuccessListener { txDoc ->
                            batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                            if (!batchId.isNullOrBlank()) {
                                runFinalizeVoidBatch(transactionId, orderDoc, batchId!!)
                            } else {
                                Toast.makeText(this, "Cannot void: batch not found", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Cannot void: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    return@addOnSuccessListener
                }
                runFinalizeVoidBatch(transactionId, orderDoc, batchId!!)
            }
    }

    private fun runFinalizeVoidBatch(transactionId: String, orderDoc: DocumentSnapshot, batchId: String) {
        val txRef = db.collection("Transactions").document(transactionId)
        txRef.get()
            .addOnSuccessListener { txDoc ->
                if (!txDoc.exists()) return@addOnSuccessListener
                val amount = txDoc.getLong("totalPaidInCents")?.let { it / 100.0 }
                    ?: txDoc.getDouble("totalPaid") ?: txDoc.getDouble("amount") ?: 0.0
                @Suppress("UNCHECKED_CAST")
                val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val updatedPayments = paymentsRaw.map { p ->
                    val mutable = p.toMutableMap()
                    mutable["status"] = "VOIDED"
                    mutable
                }

                db.runBatch { batch ->
                    val orderRef = db.collection("Orders").document(orderId)
                    val batchRef = db.collection("Batches").document(batchId)

                    batch.update(txRef, mapOf("voided" to true, "payments" to updatedPayments))

                    val voidedBy = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                        ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)
                    batch.update(orderRef, mapOf(
                        "status" to "VOIDED",
                        "voidedAt" to Date(),
                        "voidedBy" to voidedBy
                    ))

                    batch.update(batchRef, mapOf(
                        "totalSales" to FieldValue.increment(-amount),
                        "netTotal" to FieldValue.increment(-amount),
                        "transactionCount" to FieldValue.increment(-1)
                    ))
                }
                    .addOnSuccessListener {
                        Toast.makeText(this, "Sale Voided", Toast.LENGTH_LONG).show()
                        loadHeader()
                    }
            }
    }

    private fun confirmRefund() {

        AlertDialog.Builder(this)
            .setTitle("Refund Order")
            .setMessage("Refund this order?")
            .setPositiveButton("Refund") { _, _ -> executeRefundForAmount(null, finishAfter = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onOrderItemClick(itemDoc: DocumentSnapshot) {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                val status = orderDoc.getString("status") ?: ""
                if (status != "CLOSED" && status != "REFUNDED") {
                    Toast.makeText(this, "Refund is only available for closed orders.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                if (totalRefundedInCents >= totalInCents && totalInCents > 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val lineTotalInCents = itemDoc.getLong("lineTotalInCents") ?: 0L
                if (lineTotalInCents <= 0L) {
                    Toast.makeText(this, "This item has no amount to refund.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val itemName = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: "Item"
                val amountStr = String.format(Locale.US, "%.2f", lineTotalInCents / 100.0)
                AlertDialog.Builder(this)
                    .setTitle("Refund item")
                    .setMessage("Refund \"$itemName\" for \$$amountStr?")
                    .setPositiveButton("Refund") { _, _ ->
                        executeRefundForAmount(lineTotalInCents, finishAfter = false, refundedItemName = itemName)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }

    private fun executeRefundForAmount(amountInCents: Long?, finishAfter: Boolean, refundedItemName: String? = null) {

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

                        val fullAmountInCents = txDoc.getLong("totalPaidInCents") ?: 0L
                        val refundAmountInCents = amountInCents ?: fullAmountInCents
                        if (refundAmountInCents <= 0L) return@addOnSuccessListener
                        if (refundAmountInCents > fullAmountInCents) {
                            Toast.makeText(this, "Refund amount cannot exceed transaction amount.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val payments = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val firstPayment = payments.firstOrNull()
                        val paymentType = firstPayment?.get("paymentType")?.toString() ?: "Credit"
                        val referenceId = firstPayment?.get("referenceId")?.toString()
                            ?: firstPayment?.get("terminalReference")?.toString()

                        if (paymentType.equals("Cash", ignoreCase = true)) {
                            finalizeRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName)
                            return@addOnSuccessListener
                        }

                        if (!referenceId.isNullOrBlank()) {
                            callRefundApi(
                                referenceId = referenceId,
                                paymentType = paymentType,
                                amount = refundAmountInCents / 100.0,
                                originalTransactionId = transactionId,
                                amountInCents = refundAmountInCents,
                                finishAfter = finishAfter,
                                refundedItemName = refundedItemName
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
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null
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
                            finalizeRefund(originalTransactionId, amountInCents, finishAfter, refundedItemName)
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
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null
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
                        val employeeName = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                            ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)

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
                                                Toast.makeText(this, if (finishAfter) "Order Refunded" else "Refund applied", Toast.LENGTH_LONG).show()
                                                if (finishAfter) finish() else { loadHeader(); loadItems() }
                                            }
                                    }
                            }
                    }
            }
    }
}