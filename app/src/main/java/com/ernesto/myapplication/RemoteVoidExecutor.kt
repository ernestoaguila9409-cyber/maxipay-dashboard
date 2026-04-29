package com.ernesto.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionPayment
import com.ernesto.myapplication.payments.SpinGatewayP
import com.ernesto.myapplication.payments.TransactionVoidReferenceResolver
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale

/**
 * Runs card void + Firestore updates for a [Transactions] row, used from [RemoteVoidCommandListener]
 * (web dashboard queue) with the same SpinPOS / Dejavoo rules as [TransactionActivity].
 */
object RemoteVoidExecutor {

    private const val TAG = "RemoteVoidExec"
    private const val VOID_GAP_BETWEEN_LEGS_MS = 1_800L
    private const val VOID_BUSY_MAX_RETRIES = 5
    private const val VOID_BUSY_RETRY_BASE_MS = 2_000L

    private val voidSequenceHandler = Handler(Looper.getMainLooper())

    private fun isHostBusyVoidMessage(msg: String): Boolean = SpinGatewayP.isVoidHostBusyMessage(msg)

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
                referenceId = TransactionVoidReferenceResolver.gatewayRefFromPaymentMap(p),
                clientReferenceId = TransactionVoidReferenceResolver.clientRefFromPaymentMap(p),
                authCode = p["authCode"]?.toString() ?: "",
                batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                transactionNumber = (p["transactionNumber"] as? Number)?.toString()
                    ?: p["transactionNumber"]?.toString() ?: "",
                invoiceNumber = p["invoiceNumber"]?.toString() ?: "",
                pnReferenceId = TransactionVoidReferenceResolver.pnReferenceFromPaymentMap(p),
                paymentId = p["paymentId"]?.toString() ?: "",
            )
        }
        val firstPayment = payments.firstOrNull()
        val paymentType = firstPayment?.paymentType ?: doc.getString("paymentType") ?: ""
        val cardBrand = firstPayment?.cardBrand ?: doc.getString("cardBrand") ?: ""
        val last4 = firstPayment?.last4 ?: doc.getString("last4") ?: ""
        val entryType = firstPayment?.entryType ?: doc.getString("entryType") ?: ""
        val firstRaw = paymentsRaw.firstOrNull()
        val gatewayRef = TransactionVoidReferenceResolver.gatewayRefFromPaymentMap(firstRaw)
            .ifBlank { doc.getString("referenceId") ?: doc.getString("gatewayReferenceId") ?: "" }
        val clientRef = TransactionVoidReferenceResolver.clientRefFromPaymentMap(firstRaw)
            .ifBlank { doc.getString("clientReferenceId") ?: doc.getString("hppTransactionRefId") ?: "" }
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
                            invoiceNumber = tx.invoiceNumber,
                        ),
                    )
                }
                val cashPayments = payments.filter { it.paymentType.equals("Cash", ignoreCase = true) }
                val totalCash = cashPayments.sumOf { it.amountInCents }
                if (totalCash > 0) {
                    onDone(false, "Includes cash tender — complete void on the POS")
                    return@addOnSuccessListener
                }
                val cardPaymentsBase = payments.filter { !it.paymentType.equals("Cash", ignoreCase = true) }
                if (cardPaymentsBase.isEmpty()) {
                    doFinalVoidUpdate(db, txDocId, voidedBy) { ok ->
                        onDone(ok, if (ok) "Voided" else "Firestore update failed")
                    }
                    return@addOnSuccessListener
                }
                val enriched = TransactionVoidReferenceResolver.enrichPaymentsForVoid(doc, cardPaymentsBase)
                if (TransactionVoidReferenceResolver.anyCardLegMissingGatewayRef(enriched)) {
                    val orderId = doc.getString("orderId")?.trim().orEmpty()
                    if (orderId.isNotEmpty()) {
                        db.collection("Orders").document(orderId).get()
                            .addOnSuccessListener { orderDoc ->
                                val final = TransactionVoidReferenceResolver.enrichPaymentsFromOrderDoc(orderDoc, enriched)
                                voidCardPaymentsSequentially(context, db, txDocId, voidedBy, final, 0, onDone, 0)
                            }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Order lookup for void refs failed: ${e.message}")
                                voidCardPaymentsSequentially(context, db, txDocId, voidedBy, enriched, 0, onDone, 0)
                            }
                        return@addOnSuccessListener
                    }
                }
                voidCardPaymentsSequentially(context, db, txDocId, voidedBy, enriched, 0, onDone, 0)
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
        busyRetryOnLeg: Int = 0,
    ) {
        if (index >= cardPayments.size) {
            doFinalVoidUpdate(db, txDocId, voidedBy) { ok ->
                onDone(ok, if (ok) "Voided" else "Firestore update failed")
            }
            return
        }
        val payment = cardPayments[index]
        var refId = payment.referenceId.trim()
        if (refId.isBlank()) {
            val c = payment.clientReferenceId.trim()
            if (c.isNotBlank()) {
                Log.w(TAG, "Void using clientReferenceId (gateway ref blank) — leg ${index + 1}/${cardPayments.size}")
                refId = c
            }
        }
        if (refId.isBlank()) {
            val last4 = payment.last4.trim().ifBlank { "????" }
            onDone(false, "Cannot void card ${index + 1} of ${cardPayments.size} (••••$last4): missing ReferenceId on this payment leg in Firestore")
            return
        }
        sendVoidRequestForOnePayment(context, payment, refId) { approved, errMsg ->
            if (!approved) {
                if (isHostBusyVoidMessage(errMsg) && busyRetryOnLeg < VOID_BUSY_MAX_RETRIES) {
                    val waitMs = VOID_BUSY_RETRY_BASE_MS * (busyRetryOnLeg + 1)
                    voidSequenceHandler.postDelayed(
                        {
                            voidCardPaymentsSequentially(
                                context, db, txDocId, voidedBy, cardPayments, index, onDone, busyRetryOnLeg + 1
                            )
                        },
                        waitMs
                    )
                    return@sendVoidRequestForOnePayment
                }
                val partial = if (index > 0) {
                    " Earlier leg(s) may already be voided on the host—verify the terminal before retrying."
                } else {
                    ""
                }
                onDone(false, (errMsg.ifBlank { "VOID declined" }) + partial)
                return@sendVoidRequestForOnePayment
            }
            val hasMoreLegs = index + 1 < cardPayments.size
            if (hasMoreLegs) {
                voidSequenceHandler.postDelayed(
                    {
                        voidCardPaymentsSequentially(
                            context, db, txDocId, voidedBy, cardPayments, index + 1, onDone, 0
                        )
                    },
                    VOID_GAP_BETWEEN_LEGS_MS
                )
            } else {
                voidCardPaymentsSequentially(context, db, txDocId, voidedBy, cardPayments, index + 1, onDone, 0)
            }
        }
    }

    private fun sendVoidRequestForOnePayment(
        context: Context,
        payment: TransactionPayment,
        referenceIdForVoid: String,
        onHttpDone: (approved: Boolean, message: String) -> Unit,
    ) {
        SpinGatewayP.enqueueVoidPayment(context, payment, referenceIdForVoid, readTimeoutSeconds = 180) { result ->
            if (result.networkError != null) {
                Log.e(TAG, "[VOID] Network error: ${result.networkError}")
                onHttpDone(false, result.networkError)
                return@enqueueVoidPayment
            }
            Log.d(TAG, "[VOID] HTTP ${result.httpCode} Response: ${result.responseBody}")
            if (!result.hostApproved) {
                val reason = SpinGatewayP.voidDeclineMessage(result.responseBody)
                val msg = if (reason.isNotBlank()) "VOID declined: $reason" else "VOID declined"
                onHttpDone(false, msg)
                return@enqueueVoidPayment
            }
            onHttpDone(true, "")
        }
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
