package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.ernesto.myapplication.data.TransactionPayment
import com.ernesto.myapplication.payments.SpinGatewayP
import com.ernesto.myapplication.payments.TransactionVoidReferenceResolver
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.UUID

/**
 * Card refund (SPIn `/Payment/Return`) + Firestore updates for a settled sale, used from
 * [RemoteVoidCommandListener] when the web dashboard queues `refundTransaction` commands.
 * Same gateway rules as [com.ernesto.myapplication.TransactionActivity.completeCardRefundAfterHostApproved].
 */
object RemoteRefundExecutor {

    private const val TAG = "RemoteRefundExec"

    private fun isEcommerce(txDoc: DocumentSnapshot): Boolean {
        if (txDoc.getBoolean("ecommerce") == true) return true
        @Suppress("UNCHECKED_CAST")
        val payments = txDoc.get("payments") as? List<Map<String, Any>> ?: return false
        return payments.any { p ->
            p["entryType"]?.toString()?.equals("ECOMMERCE", ignoreCase = true) == true
        }
    }

    private fun hasCashTender(txDoc: DocumentSnapshot): Boolean {
        @Suppress("UNCHECKED_CAST")
        val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        if (paymentsRaw.isNotEmpty()) {
            return paymentsRaw.any { p ->
                p["paymentType"]?.toString()?.equals("Cash", ignoreCase = true) == true
            }
        }
        return txDoc.getString("paymentType")?.equals("Cash", ignoreCase = true) == true
    }

    private fun cardLegCount(txDoc: DocumentSnapshot): Int {
        @Suppress("UNCHECKED_CAST")
        val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        fun numStr(p: Map<String, Any>, key: String): String {
            val v = p[key] ?: return ""
            return (v as? Number)?.toString() ?: v.toString().trim()
        }
        val parsed = paymentsRaw.map { p ->
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
                batchNumber = numStr(p, "batchNumber"),
                transactionNumber = numStr(p, "transactionNumber"),
                invoiceNumber = p["invoiceNumber"]?.toString() ?: "",
                pnReferenceId = TransactionVoidReferenceResolver.pnReferenceFromPaymentMap(p),
            )
        }
        val cardPayments = parsed.filter { !it.paymentType.equals("Cash", ignoreCase = true) }
        val enriched = TransactionVoidReferenceResolver.enrichPaymentsForVoid(txDoc, cardPayments)
        return enriched.size
    }

    /**
     * @param saleTxId Firestore `Transactions` document id of the original sale/capture/pre-auth.
     * @param orderId `Orders` doc to update totals (must match the sale's `orderId` when present).
     */
    fun execute(
        context: Context,
        saleTxId: String,
        orderId: String,
        amountInCentsRequested: Long,
        refundedBy: String,
        refundedLineKey: String?,
        refundedItemName: String?,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        if (amountInCentsRequested <= 0L) {
            onDone(false, "Refund amount must be greater than zero")
            return
        }

        val db = FirebaseFirestore.getInstance()
        db.collection("Transactions").document(saleTxId).get()
            .addOnSuccessListener { txDoc ->
                if (!txDoc.exists()) {
                    onDone(false, "Transaction not found")
                    return@addOnSuccessListener
                }
                val type = txDoc.getString("type") ?: ""
                if (type != "SALE" && type != "CAPTURE" && type != "PRE_AUTH") {
                    onDone(false, "Unsupported transaction type for refund")
                    return@addOnSuccessListener
                }
                if (txDoc.getBoolean("voided") == true) {
                    onDone(false, "Transaction is voided")
                    return@addOnSuccessListener
                }
                if (txDoc.getBoolean("settled") != true) {
                    onDone(false, "Batch not settled — use Request void on POS instead")
                    return@addOnSuccessListener
                }
                if (hasCashTender(txDoc)) {
                    onDone(false, "Includes cash tender — complete refund on the POS")
                    return@addOnSuccessListener
                }
                if (isEcommerce(txDoc)) {
                    onDone(
                        false,
                        "Online / hosted card payments cannot be refunded through this queue — use the iPOS portal or the POS.",
                    )
                    return@addOnSuccessListener
                }
                if (cardLegCount(txDoc) > 1) {
                    onDone(false, "Split card sale — complete refund on the POS")
                    return@addOnSuccessListener
                }

                val txOrderId = txDoc.getString("orderId")?.trim().orEmpty()
                val effectiveOrderId = orderId.trim().ifBlank { txOrderId }
                if (txOrderId.isNotBlank() && orderId.isNotBlank() && !txOrderId.equals(orderId.trim(), ignoreCase = false)) {
                    onDone(false, "Order id does not match this transaction")
                    return@addOnSuccessListener
                }
                if (effectiveOrderId.isEmpty()) {
                    onDone(false, "Missing orderId for this refund command")
                    return@addOnSuccessListener
                }

                db.collection("Orders").document(effectiveOrderId).get()
                    .addOnSuccessListener { orderDoc ->
                        if (!orderDoc.exists()) {
                            onDone(false, "Order not found")
                            return@addOnSuccessListener
                        }
                        val saleOnOrder = (
                            orderDoc.getString("saleTransactionId")
                                ?: orderDoc.getString("transactionId")
                            )?.trim().orEmpty()
                        if (saleOnOrder.isNotBlank() && !saleOnOrder.equals(saleTxId, ignoreCase = false)) {
                            onDone(false, "Order is linked to a different sale transaction")
                            return@addOnSuccessListener
                        }

                        val orderTotalInCents = orderDoc.getLong("totalInCents") ?: 0L
                        val alreadyRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                        val remainingOnOrder = orderTotalInCents - alreadyRefundedInCents
                        if (remainingOnOrder <= 0L) {
                            onDone(false, "Order is already fully refunded")
                            return@addOnSuccessListener
                        }

                        val fullAmountInCents = txDoc.getLong("totalPaidInCents") ?: 0L
                        val uncapped = amountInCentsRequested
                        val refundAmountInCents = minOf(uncapped, remainingOnOrder, fullAmountInCents)
                        if (refundAmountInCents <= 0L) {
                            onDone(false, "Nothing to refund")
                            return@addOnSuccessListener
                        }
                        if (refundAmountInCents > fullAmountInCents) {
                            onDone(false, "Refund amount cannot exceed transaction amount")
                            return@addOnSuccessListener
                        }

                        val leg = SpinGatewayP.cardLegForHostReturnFromTxDoc(txDoc)
                        if (leg.referenceId.isBlank() && leg.clientReferenceId.isBlank()) {
                            onDone(false, "Cannot refund: no gateway reference for this transaction")
                            return@addOnSuccessListener
                        }

                        val firstPaymentType = (
                            (txDoc.get("payments") as? List<Map<String, Any>>)?.firstOrNull()
                                ?.get("paymentType") as? String
                            ) ?: txDoc.getString("paymentType") ?: "Credit"
                        val returnPaymentType = if (firstPaymentType.equals("Debit", ignoreCase = true)) {
                            "Credit"
                        } else {
                            firstPaymentType
                        }
                        val refundAmount = refundAmountInCents / 100.0

                        SpinGatewayP.enqueueRefundPayment(
                            context,
                            refundAmount,
                            returnPaymentType,
                            leg,
                            readTimeoutSeconds = 180,
                        ) { result ->
                            if (result.networkError != null) {
                                Log.e(TAG, "[REFUND] Network: ${result.networkError}")
                                onDone(false, result.networkError)
                                return@enqueueRefundPayment
                            }
                            if (!result.hostApproved) {
                                val reason = SpinGatewayP.voidDeclineMessage(result.responseBody)
                                onDone(false, if (reason.isNotBlank()) "Refund declined: $reason" else "Refund declined")
                                return@enqueueRefundPayment
                            }
                            val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
                            persistApprovedCardRefund(
                                db = db,
                                saleReferenceId = saleTxId,
                                refundAmount = refundAmount,
                                paymentType = firstPaymentType,
                                refundedBy = refundedBy,
                                orderId = effectiveOrderId,
                                orderNumber = orderNumber,
                                refundedLineKey = refundedLineKey,
                                refundedItemName = refundedItemName,
                                onDone = onDone,
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "load order", e)
                        onDone(false, e.message ?: "Failed to load order")
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "load tx", e)
                onDone(false, e.message ?: "Failed to load transaction")
            }
    }

    /** Called after SPIn host approves the Return (same shape as TransactionActivity). */
    private fun persistApprovedCardRefund(
        db: FirebaseFirestore,
        saleReferenceId: String,
        refundAmount: Double,
        paymentType: String,
        refundedBy: String,
        orderId: String,
        orderNumber: Long,
        refundedLineKey: String?,
        refundedItemName: String?,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        val refundAmountCents = (refundAmount * 100).toLong()

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { batchSnap ->
                val openBatchId = if (!batchSnap.isEmpty) batchSnap.documents.first().id else ""

                val refundMap = hashMapOf<String, Any>(
                    "referenceId" to UUID.randomUUID().toString(),
                    "originalReferenceId" to saleReferenceId,
                    "amount" to refundAmount,
                    "amountInCents" to refundAmountCents,
                    "type" to "REFUND",
                    "paymentType" to paymentType,
                    "cardBrand" to "",
                    "last4" to "",
                    "entryType" to "",
                    "voided" to false,
                    "settled" to false,
                    "createdAt" to Date(),
                    "refundedBy" to refundedBy,
                    "batchId" to openBatchId,
                    "orderId" to orderId,
                    "orderNumber" to orderNumber,
                )
                refundedLineKey?.takeIf { it.isNotBlank() }?.let { refundMap["refundedLineKey"] = it }
                refundedItemName?.takeIf { it.isNotBlank() }?.let { refundMap["refundedItemName"] = it }

                val refundRef = db.collection("Transactions").document()
                db.runTransaction { firestoreTxn ->
                    if (openBatchId.isNotBlank()) {
                        val batchRef = db.collection("Batches").document(openBatchId)
                        val batchDoc = firestoreTxn.get(batchRef)
                        val counter = batchDoc.getLong("transactionCounter") ?: 0L
                        val next = counter + 1
                        firestoreTxn.update(batchRef, "transactionCounter", next)
                        refundMap["appTransactionNumber"] = next
                    }
                    firestoreTxn.set(refundRef, refundMap)
                }.addOnSuccessListener {
                    if (openBatchId.isNotBlank()) {
                        db.collection("Batches").document(openBatchId)
                            .update(
                                mapOf(
                                    "totalRefundsInCents" to FieldValue.increment(refundAmountCents),
                                    "netTotalInCents" to FieldValue.increment(-refundAmountCents),
                                    "transactionCount" to FieldValue.increment(1),
                                ),
                            )
                    }

                    val orderRef = db.collection("Orders").document(orderId)
                    orderRef.get()
                        .addOnSuccessListener { orderDoc ->
                            if (!orderDoc.exists()) {
                                onDone(true, "Refund approved")
                                return@addOnSuccessListener
                            }
                            val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                            val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                            val newTotalRefunded = currentRefunded + refundAmountCents
                            val updates = mutableMapOf<String, Any>(
                                "totalRefundedInCents" to newTotalRefunded,
                                "refundedAt" to Date(),
                            )
                            if (newTotalRefunded >= totalInCents) {
                                updates["status"] = "REFUNDED"
                            }
                            orderRef.update(updates)
                                .addOnSuccessListener {
                                    onDone(true, "Refund completed")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "order update", e)
                                    onDone(true, "Refund approved (order totals may need manual check)")
                                }
                        }
                        .addOnFailureListener {
                            onDone(true, "Refund approved")
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "persist refund tx", e)
                    onDone(false, "Refund approved but failed to save: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "batch lookup", e)
                onDone(false, e.message ?: "Failed to look up open batch")
            }
    }
}
