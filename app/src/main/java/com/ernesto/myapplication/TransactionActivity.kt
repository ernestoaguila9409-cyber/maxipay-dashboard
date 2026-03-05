package com.ernesto.myapplication

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.ernesto.myapplication.data.Transaction
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import android.util.Log
import java.util.*
import com.ernesto.myapplication.data.SaleWithRefunds
import com.google.firebase.firestore.FieldValue
class TransactionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TransactionAdapter
    private val transactionList = mutableListOf<SaleWithRefunds>()
    private val db = FirebaseFirestore.getInstance()
    private var currentEmployeeName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        recyclerView = findViewById(R.id.recyclerTransactions)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(transactionList) { transaction ->
            showTransactionOptions(transaction)
        }

        recyclerView.adapter = adapter
        currentEmployeeName = intent.getStringExtra("employeeName") ?: ""
        loadTransactions()
    }

    // 🔥 LOAD + GROUP SALES WITH REFUNDS (CORRECT VERSION)
    private fun loadTransactions() {

        db.collection("Transactions")
            .addSnapshotListener { snapshots, error ->

                if (error != null) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                if (snapshots == null) return@addSnapshotListener

                transactionList.clear()
                val allTransactions = mutableListOf<Transaction>()

                snapshots.forEach { doc ->

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE" &&
                        !doc.contains("totalPaid") &&
                        !doc.contains("totalPaidInCents")
                    ) {
                        return@forEach
                    }

                    val createdAt = doc.getTimestamp("createdAt")?.toDate()
                    val oldTimestamp = doc.getTimestamp("timestamp")?.toDate()

                    val dateMillis = createdAt?.time ?: oldTimestamp?.time ?: 0L

                    val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                    val firstPayment = payments.firstOrNull()

                    val paymentType = firstPayment?.get("paymentType")?.toString() ?: ""
                    val cardBrand = firstPayment?.get("cardBrand")?.toString() ?: ""
                    val last4 = firstPayment?.get("last4")?.toString() ?: ""
                    val entryType = firstPayment?.get("entryType")?.toString() ?: ""
                    // Dejavoo sale response fields for Void: referenceId, batchNumber, transactionNumber
                    val gatewayRef = firstPayment?.get("referenceId")?.toString()
                        ?: firstPayment?.get("terminalReference")?.toString() ?: ""
                    val clientRef = firstPayment?.get("clientReferenceId")?.toString() ?: ""
                    val batchNum = firstPayment?.get("batchNumber")?.toString() ?: ""
                    val txNum = firstPayment?.get("transactionNumber")?.toString() ?: ""
                    val invNum = firstPayment?.get("invoiceNumber")?.toString() ?: ""

                    val isMixed = payments.size > 1

                    val amountInCents = when (type) {
                        "REFUND" -> ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
                        else -> doc.getLong("totalPaidInCents")
                            ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
                    }

                    val transaction = Transaction(
                        referenceId = doc.id,
                        gatewayReferenceId = gatewayRef,
                        clientReferenceId = clientRef,
                        batchNumber = batchNum,
                        transactionNumber = txNum,
                        invoiceNumber = invNum,
                        amountInCents = amountInCents,
                        date = dateMillis,
                        paymentType = paymentType,
                        cardBrand = cardBrand,
                        last4 = last4,
                        entryType = entryType,
                        voided = doc.getBoolean("voided") ?: false,
                        settled = doc.getBoolean("settled") ?: false,
                        type = type,
                        originalReferenceId = doc.getString("originalReferenceId") ?: "",
                        isMixed = isMixed
                    )

                    allTransactions.add(transaction)
                }

                // Sort newest first
                allTransactions.sortByDescending { it.date }

                val sales = allTransactions.filter { it.type == "SALE" }
                val refunds = allTransactions.filter { it.type == "REFUND" }

                sales.forEach { sale ->
                    val relatedRefunds =
                        if (sale.referenceId.isBlank()) emptyList()
                        else refunds.filter { it.originalReferenceId == sale.referenceId }

                    transactionList.add(
                        SaleWithRefunds(
                            sale = sale,
                            refunds = relatedRefunds
                        )
                    )
                }

                adapter.notifyDataSetChanged()
            }
    }

    private fun showTransactionOptions(transaction: Transaction) {
        if (transaction.voided) {
            Toast.makeText(this, "This transaction has already been voided.", Toast.LENGTH_SHORT).show()
            return
        }
        if (transaction.paymentType.equals("Cash", ignoreCase = true)) {
            // For cash: only allow local refund, no Devajoo/Z8 calls
            AlertDialog.Builder(this)
                .setTitle("Cash Transaction")
                .setMessage("Select an option for this cash payment.")
                .setPositiveButton("Refund") { _, _ -> processCashRefund(transaction) }
                .setNegativeButton("Cancel", null)
                .show()
        } else if (transaction.paymentType.equals("Debit", ignoreCase = true)) {
            // For debit: debit sales cannot be voided, only refunded even if batch is still open
            AlertDialog.Builder(this)
                .setTitle("Transaction Options")
                .setMessage("Debit sale can only be refunded.")
                .setPositiveButton("Refund") { _, _ -> processRefund(transaction) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // For card: Void only before batch close; after batch closed (settled) only Refund
            if (transaction.settled) {
                AlertDialog.Builder(this)
                    .setTitle("Transaction Options")
                    .setMessage("Batch already closed. Refund only.")
                    .setPositiveButton("Refund") { _, _ -> processRefund(transaction) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Transaction Options")
                    .setPositiveButton("Refund") { _, _ -> processRefund(transaction) }
                    .setNegativeButton("Void") { _, _ -> processVoid(transaction) }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun processVoid(transaction: Transaction) {
        // Void must use exact Dejavoo sale response values: ReferenceId, BatchNumber, TransactionNumber
        val referenceId = transaction.gatewayReferenceId.ifBlank { transaction.clientReferenceId }
        if (referenceId.isBlank()) {
            Toast.makeText(this, "Cannot void: no ReferenceId for this transaction.", Toast.LENGTH_LONG).show()
            return
        }

        val amountNumber = transaction.amountInCents / 100.0

        val json = org.json.JSONObject().apply {
            put("Amount", amountNumber)
            put("PaymentType", transaction.paymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("MerchantNumber", org.json.JSONObject.NULL)
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", org.json.JSONObject().apply { put("Url", "") })
            put("Tpn", "11881706541A")
            put("Authkey", "Qt9N7CxhDs")
            put("SPInProxyTimeout", org.json.JSONObject.NULL)
            put("CustomFields", org.json.JSONObject())
            if (transaction.batchNumber.isNotBlank()) {
                put("BatchNumber", transaction.batchNumber.toIntOrNull() ?: transaction.batchNumber)
            }
            if (transaction.transactionNumber.isNotBlank()) {
                put("TransactionNumber", transaction.transactionNumber.toIntOrNull() ?: transaction.transactionNumber)
            }
        }.toString()

        sendApiRequest(
            url = "https://spinpos.net/v2/Payment/Void",
            json = json,
            type = "VOID",
            referenceId = transaction.referenceId  // Firestore doc id for updating our DB
        )
    }

    // Card refund: send request to Devajoo and, on approval, store refund transaction
    private fun processRefund(transaction: Transaction) {

        val fullAmount = transaction.amountInCents / 100.0

        val input = EditText(this)
        input.hint = "Enter refund amount (Max: $fullAmount)"

        AlertDialog.Builder(this)
            .setTitle("Refund")
            .setView(input)
            .setPositiveButton("Refund") { _, _ ->
                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null || entered <= 0 || entered > fullAmount) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                } else {
                    sendRefundRequest(transaction, entered)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Cash refund: local Firestore-only refund, no terminal call
    private fun processCashRefund(transaction: Transaction) {

        val fullAmount = transaction.amountInCents / 100.0

        val input = EditText(this)
        input.hint = "Enter refund amount (Max: $fullAmount)"

        AlertDialog.Builder(this)
            .setTitle("Cash Refund")
            .setView(input)
            .setPositiveButton("Refund") { _, _ ->
                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null || entered <= 0 || entered > fullAmount) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                } else {
                    createLocalRefund(transaction, entered)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createLocalRefund(original: Transaction, amount: Double) {
        val refundAmountCents = (amount * 100).toLong()

        val refundMap = hashMapOf(
            "referenceId" to UUID.randomUUID().toString(),
            "originalReferenceId" to original.referenceId,
            "amount" to amount,
            "amountInCents" to refundAmountCents,
            "type" to "REFUND",
            "paymentType" to "Cash",
            "cardBrand" to "",
            "last4" to "",
            "entryType" to "",
            "voided" to false,
            "settled" to false,
            "createdAt" to Date(),
            "refundedBy" to currentEmployeeName
        )

        db.collection("Transactions")
            .add(refundMap)
            .addOnSuccessListener {
                // Mirror card refund behavior: update Order totals and status so it shows REFUNDED
                db.collection("Transactions").document(original.referenceId).get()
                    .addOnSuccessListener { saleDoc ->
                        if (!saleDoc.exists()) return@addOnSuccessListener
                        val orderId = saleDoc.getString("orderId") ?: return@addOnSuccessListener
                        val orderRef = db.collection("Orders").document(orderId)
                        orderRef.get()
                            .addOnSuccessListener { orderDoc ->
                                if (!orderDoc.exists()) return@addOnSuccessListener
                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                val newTotalRefunded = currentRefunded + refundAmountCents
                                val updates = mutableMapOf<String, Any>(
                                    "totalRefundedInCents" to newTotalRefunded,
                                    "refundedAt" to Date()
                                )
                                if (newTotalRefunded >= totalInCents) {
                                    updates["status"] = "REFUNDED"
                                }
                                orderRef.update(updates)
                            }
                    }

                Toast.makeText(this, "Cash refund saved", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save refund: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun sendRefundRequest(transaction: Transaction, amount: Double) {
        val refForGateway = transaction.gatewayReferenceId.ifBlank { transaction.clientReferenceId }
        if (refForGateway.isBlank()) {
            Toast.makeText(this, "Cannot refund: no gateway reference for this transaction.", Toast.LENGTH_LONG).show()
            return
        }

        val json = org.json.JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", transaction.paymentType)
            put("ReferenceId", refForGateway)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("Tpn", "11881706541A")
            put("RegisterId", "134909005")
            put("Authkey", "Qt9N7CxhDs")
        }.toString()

        sendApiRequest(
            url = "https://spinpos.net/v2/Payment/Return",
            json = json,
            type = "REFUND",
            referenceId = transaction.referenceId,
            refundAmount = amount,
            paymentType = transaction.paymentType,
            refundedBy = currentEmployeeName
        )
    }

    private fun sendApiRequest(
        url: String,
        json: String,
        type: String,
        referenceId: String? = null,
        refundAmount: Double? = null,
        paymentType: String? = null,
        refundedBy: String = ""
    ) {
        Log.d("TX_API", "[$type] URL: $url")
        Log.d("TX_API", "[$type] Request: $json")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                Log.e("TX_API", "[$type] Network error", e)
                runOnUiThread {
                    Toast.makeText(
                        this@TransactionActivity,
                        "$type Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""
                Log.d("TX_API", "[$type] HTTP ${response.code}")
                Log.d("TX_API", "[$type] Response: $responseText")

                runOnUiThread {

                    // Same success check as PaymentActivity: GeneralResponse.ResultCode == "0"
                    val approved = try {
                        val obj = org.json.JSONObject(responseText)
                        val resultCode = obj.optJSONObject("GeneralResponse")?.optString("ResultCode", "") ?: ""
                        resultCode == "0"
                    } catch (e: Exception) {
                        false
                    }

                    if (!response.isSuccessful || !approved) {
                        val reason = try {
                            val gen = org.json.JSONObject(responseText).optJSONObject("GeneralResponse")
                            gen?.optString("DetailedMessage", "")?.ifBlank { gen.optString("Message", "") } ?: ""
                        } catch (e: Exception) { "" }
                        val message = if (reason.isNotBlank()) "$type Declined: $reason" else "$type Declined"
                        val hint = if (type == "VOID" && reason.contains("not found", ignoreCase = true))
                            "\n(Use Refund if batch was already closed.)" else ""
                        Log.w("TX_API", "[$type] Declined. Reason: $reason")
                        Toast.makeText(
                            this@TransactionActivity,
                            message + hint,
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    // ================= VOID =================
                    // referenceId = Firestore Transactions doc id; use it to get orderId and update Order to VOIDED
                    if (type == "VOID" && referenceId != null) {

                        val txRef = db.collection("Transactions").document(referenceId)
                        txRef.get()
                            .addOnSuccessListener { document ->
                                if (!document.exists()) return@addOnSuccessListener
                                val amount = document.getLong("totalPaidInCents")?.let { it / 100.0 }
                                    ?: document.getDouble("totalPaid")
                                    ?: document.getDouble("amount")
                                    ?: 0.0
                                val orderId = document.getString("orderId") ?: ""
                                val batchId = document.getString("batchId") ?: ""

                                // 1️⃣ mark transaction voided
                                txRef.update("voided", true)

                                // 2️⃣ update Order to VOIDED (use orderId from transaction doc so Order screen shows Voided)
                                if (orderId.isNotBlank()) {
                                    val orderRef = db.collection("Orders").document(orderId)
                                    orderRef.update(
                                        mapOf(
                                            "status" to "VOIDED",
                                            "voidedAt" to Date(),
                                            "voidedBy" to currentEmployeeName
                                        )
                                    ).addOnFailureListener { e ->
                                        android.util.Log.e("TX_API", "Failed to update Order to VOIDED", e)
                                    }
                                }

                                // 3️⃣ update batch totals
                                if (batchId.isNotBlank()) {
                                    val batchRef = db.collection("Batches").document(batchId)
                                    batchRef.update(
                                        mapOf(
                                            "totalSales" to FieldValue.increment(-amount),
                                            "netTotal" to FieldValue.increment(-amount),
                                            "transactionCount" to FieldValue.increment(-1)
                                        )
                                    ).addOnFailureListener { e ->
                                        android.util.Log.e("TX_API", "Failed to update Batch on void", e)
                                    }
                                }
                            }
                    }

                    // ================= REFUND (CARD) =================
                    if (type == "REFUND" && referenceId != null && refundAmount != null) {

                        val refundAmountCents = (refundAmount * 100).toLong()
                        val refundMap = hashMapOf(
                            "referenceId" to UUID.randomUUID().toString(),
                            "originalReferenceId" to referenceId,
                            "amount" to refundAmount,
                            "amountInCents" to refundAmountCents,
                            "type" to "REFUND",
                            "paymentType" to (paymentType ?: ""),
                            "cardBrand" to "",
                            "last4" to "",
                            "entryType" to "",
                            "voided" to false,
                            "settled" to false,
                            "createdAt" to Date(),
                            "refundedBy" to refundedBy
                        )

                        db.collection("Transactions").add(refundMap)
                            .addOnSuccessListener {
                                // Update Order so it shows REFUNDED on order screen and detail
                                db.collection("Transactions").document(referenceId).get()
                                    .addOnSuccessListener { saleDoc ->
                                        if (!saleDoc.exists()) return@addOnSuccessListener
                                        val orderId = saleDoc.getString("orderId") ?: return@addOnSuccessListener
                                        val orderRef = db.collection("Orders").document(orderId)
                                        orderRef.get()
                                            .addOnSuccessListener { orderDoc ->
                                                if (!orderDoc.exists()) return@addOnSuccessListener
                                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                                val newTotalRefunded = currentRefunded + refundAmountCents
                                                val updates = mutableMapOf<String, Any>(
                                                    "totalRefundedInCents" to newTotalRefunded,
                                                    "refundedAt" to Date()
                                                )
                                                if (newTotalRefunded >= totalInCents) {
                                                    updates["status"] = "REFUNDED"
                                                }
                                                orderRef.update(updates)
                                            }
                                    }
                            }
                    }

                    Toast.makeText(
                        this@TransactionActivity,
                        "$type APPROVED",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }
}
