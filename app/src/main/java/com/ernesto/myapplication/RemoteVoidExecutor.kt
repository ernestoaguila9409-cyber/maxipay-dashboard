package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionPayment
import com.ernesto.myapplication.payments.SpinApiUrls
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Runs card void + Firestore updates for a [Transactions] row, used from [RemoteVoidCommandListener]
 * (web dashboard queue) with the same SpinPOS / Dejavoo rules as [TransactionActivity].
 */
object RemoteVoidExecutor {

    private const val TAG = "RemoteVoidExec"

    fun parseTransactionForVoid(doc: DocumentSnapshot): Transaction? {
        if (!doc.exists()) return null
        val type = doc.getString("type") ?: "SALE"
        if (type == "SALE" &&
            !doc.contains("totalPaid") &&
            !doc.contains("totalPaidInCents")
        ) {
            return null
        }
        if (type == "PRE_AUTH" && !doc.contains("totalPaidInCents")) {
            return null
        }
        val createdAt = doc.getTimestamp("createdAt")?.toDate()
        val oldTimestamp = doc.getTimestamp("timestamp")?.toDate()
        val dateMillis = createdAt?.time ?: oldTimestamp?.time ?: 0L

        val paymentsRaw = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
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
                authCode = p["authCode"]?.toString() ?: "",
                batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                transactionNumber = (p["transactionNumber"] as? Number)?.toString()
                    ?: p["transactionNumber"]?.toString() ?: "",
                paymentId = p["paymentId"]?.toString() ?: "",
            )
        }
        val firstPayment = payments.firstOrNull()
        val paymentType = firstPayment?.paymentType ?: doc.getString("paymentType") ?: ""
        val cardBrand = firstPayment?.cardBrand ?: doc.getString("cardBrand") ?: ""
        val last4 = firstPayment?.last4 ?: doc.getString("last4") ?: ""
        val entryType = firstPayment?.entryType ?: doc.getString("entryType") ?: ""
        val firstRaw = paymentsRaw.firstOrNull()
        val gatewayRef = firstRaw?.get("referenceId")?.toString()
            ?: firstRaw?.get("terminalReference")?.toString() ?: ""
        val clientRef = firstRaw?.get("clientReferenceId")?.toString() ?: ""
        val batchNum = firstRaw?.get("batchNumber")?.toString() ?: ""
        val txNum = firstRaw?.get("transactionNumber")?.toString() ?: ""
        val invNum = firstRaw?.get("invoiceNumber")?.toString() ?: ""

        val amountInCents = when (type) {
            "REFUND" -> ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
            else -> doc.getLong("totalPaidInCents")
                ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
        }

        return Transaction(
            referenceId = doc.id,
            orderId = doc.getString("orderId") ?: "",
            orderNumber = doc.getLong("orderNumber") ?: 0L,
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
            voidedBy = doc.getString("voidedBy") ?: "",
            settled = doc.getBoolean("settled") ?: false,
            batchId = doc.getString("batchId") ?: "",
            type = type,
            originalReferenceId = doc.getString("originalReferenceId") ?: "",
            isMixed = payments.size > 1,
            payments = payments,
            tipAmountInCents = doc.getLong("tipAmountInCents") ?: 0L,
            tipAdjusted = doc.getBoolean("tipAdjusted") ?: false,
            appTransactionNumber = doc.getLong("appTransactionNumber") ?: 0L,
        )
    }

    /**
     * Card void(s) + Firestore void fields. [voidedBy] is stored on the transaction/order (e.g. dashboard email).
     */
    fun execute(
        context: Context,
        txDocId: String,
        voidedBy: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        val db = FirebaseFirestore.getInstance()
        db.collection("Transactions").document(txDocId).get()
            .addOnSuccessListener { doc ->
                val tx = parseTransactionForVoid(doc) ?: run {
                    onDone(false, "Transaction not found")
                    return@addOnSuccessListener
                }
                if (tx.voided) {
                    onDone(true, "Already voided")
                    return@addOnSuccessListener
                }
                if (tx.settled) {
                    onDone(false, "Batch already closed — use refund on POS")
                    return@addOnSuccessListener
                }
                if (tx.type != "SALE" && tx.type != "CAPTURE" && tx.type != "PRE_AUTH") {
                    onDone(false, "Unsupported transaction type for void")
                    return@addOnSuccessListener
                }

                val payments = tx.payments.ifEmpty {
                    listOf(
                        TransactionPayment(
                            paymentType = tx.paymentType.ifBlank { "Credit" },
                            cardBrand = tx.cardBrand,
                            last4 = tx.last4,
                            entryType = tx.entryType,
                            amountInCents = tx.amountInCents,
                            referenceId = tx.gatewayReferenceId,
                            clientReferenceId = tx.clientReferenceId,
                            batchNumber = tx.batchNumber,
                            transactionNumber = tx.transactionNumber,
                        ),
                    )
                }
                val cashPayments = payments.filter { it.paymentType.equals("Cash", ignoreCase = true) }
                val totalCash = cashPayments.sumOf { it.amountInCents }
                if (totalCash > 0) {
                    onDone(false, "Includes cash tender — complete void on the POS")
                    return@addOnSuccessListener
                }
                val cardPayments = payments.filter { !it.paymentType.equals("Cash", ignoreCase = true) }
                if (cardPayments.isEmpty()) {
                    doFinalVoidUpdate(db, txDocId, voidedBy) { ok ->
                        onDone(ok, if (ok) "Voided" else "Firestore update failed")
                    }
                    return@addOnSuccessListener
                }
                voidCardPaymentsSequentially(context, db, txDocId, voidedBy, cardPayments, 0, onDone)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "load tx", e)
                onDone(false, e.message ?: "Failed to load transaction")
            }
    }

    private fun voidCardPaymentsSequentially(
        context: Context,
        db: FirebaseFirestore,
        txDocId: String,
        voidedBy: String,
        cardPayments: List<TransactionPayment>,
        index: Int,
        onDone: (Boolean, String) -> Unit,
    ) {
        if (index >= cardPayments.size) {
            doFinalVoidUpdate(db, txDocId, voidedBy) { ok ->
                onDone(ok, if (ok) "Voided" else "Firestore update failed")
            }
            return
        }
        val payment = cardPayments[index]
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        if (refId.isBlank()) {
            onDone(false, "Cannot void: missing ReferenceId for card payment")
            return
        }
        sendVoidRequestForOnePayment(context, payment) { approved, errMsg ->
            if (!approved) {
                onDone(false, errMsg.ifBlank { "VOID declined" })
                return@sendVoidRequestForOnePayment
            }
            voidCardPaymentsSequentially(context, db, txDocId, voidedBy, cardPayments, index + 1, onDone)
        }
    }

    private fun sendVoidRequestForOnePayment(
        context: Context,
        payment: TransactionPayment,
        onHttpDone: (approved: Boolean, message: String) -> Unit,
    ) {
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        val amountNumber = payment.amountInCents / 100.0
        val json = JSONObject().apply {
            put("Amount", amountNumber)
            put("PaymentType", payment.paymentType.ifBlank { "Credit" })
            put("ReferenceId", refId)
            if (payment.authCode.isNotBlank()) put("AuthCode", payment.authCode)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
        }.toString()
        Log.d(TAG, "[VOID_REQ] $json")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SpinApiUrls.voidPayment(context))
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "[VOID] Network error", e)
                onHttpDone(false, e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                Log.d(TAG, "[VOID] HTTP ${response.code} Response: $responseText")
                val approved = try {
                    val obj = JSONObject(responseText)
                    obj.optJSONObject("GeneralResponse")?.optString("ResultCode", "") == "0"
                } catch (_: Exception) {
                    false
                }
                if (!response.isSuccessful || !approved) {
                    val reason = try {
                        val gen = JSONObject(responseText).optJSONObject("GeneralResponse")
                        gen?.optString("DetailedMessage", "")?.ifBlank { gen.optString("Message", "") } ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    val msg = if (reason.isNotBlank()) "VOID declined: $reason" else "VOID declined"
                    onHttpDone(false, msg)
                    return
                }
                onHttpDone(true, "")
            }
        })
    }

    private fun doFinalVoidUpdate(
        db: FirebaseFirestore,
        txDocId: String,
        voidedBy: String,
        onComplete: (Boolean) -> Unit,
    ) {
        val txRef = db.collection("Transactions").document(txDocId)
        txRef.get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    onComplete(false)
                    return@addOnSuccessListener
                }
                @Suppress("UNCHECKED_CAST")
                val paymentsRaw = document.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val updatedPayments = paymentsRaw.map { p ->
                    val mutable = p.toMutableMap()
                    mutable["status"] = "VOIDED"
                    mutable
                }
                val amount = document.getLong("totalPaidInCents")?.let { it / 100.0 }
                    ?: document.getDouble("totalPaid") ?: document.getDouble("amount") ?: 0.0
                val orderId = document.getString("orderId") ?: ""
                val batchId = document.getString("batchId") ?: ""

                txRef.update(
                    mapOf(
                        "voided" to true,
                        "voidedBy" to voidedBy,
                        "payments" to updatedPayments,
                    ),
                )
                    .addOnSuccessListener {
                        if (batchId.isNotBlank()) {
                            db.collection("Batches").document(batchId).update(
                                mapOf(
                                    "totalSales" to FieldValue.increment(-amount),
                                    "netTotal" to FieldValue.increment(-amount),
                                    "transactionCount" to FieldValue.increment(-1),
                                ),
                            ).addOnFailureListener { e ->
                                Log.e(TAG, "Failed to update Batch on void", e)
                            }
                        }
                        if (orderId.isNotBlank()) {
                            db.collection("Orders").document(orderId).update(
                                mapOf(
                                    "status" to "VOIDED",
                                    "voidedAt" to Date(),
                                    "voidedBy" to voidedBy,
                                ),
                            ).addOnSuccessListener {
                                onComplete(true)
                            }.addOnFailureListener { e ->
                                Log.e(TAG, "Failed to update Order to VOIDED", e)
                                onComplete(true)
                            }
                        } else {
                            onComplete(true)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "tx void update failed", e)
                        onComplete(false)
                    }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }
}
