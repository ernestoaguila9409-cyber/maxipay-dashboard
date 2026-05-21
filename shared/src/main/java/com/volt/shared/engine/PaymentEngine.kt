package com.volt.shared.engine

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.volt.shared.MerchantFirestore
import com.volt.shared.device.PosDevicePresenceSync
import com.volt.shared.helpers.BarTabSeatHelper
import com.volt.shared.helpers.TableFirestoreHelper
import java.util.Date
import java.util.UUID

class PaymentEngine(private val db: FirebaseFirestore) {

    fun processPayment(
        orderId: String,
        batchId: String,
        paymentType: String,
        amountInCents: Long,
        authCode: String = "",
        cardBrand: String = "",
        last4: String = "",
        entryType: String = "",
        referenceId: String = "",
        clientReferenceId: String = "",
        batchNumber: String = "",
        transactionNumber: String = "",
        invoiceNumber: String = "",
        pnReferenceId: String = "",
        cashTenderedInCents: Long = 0L,
        cashChangeInCents: Long = 0L,
        splitReceipt: Map<String, Any>? = null,
        finalTipFromCustomerScreen: Boolean = false,
        onSuccess: (Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val orderRef = MerchantFirestore.col("Orders").document(orderId)
        val transactionsRef = MerchantFirestore.col("Transactions")

        fun isCardPayment(pt: String): Boolean =
            pt.equals("Credit", ignoreCase = true) || pt.equals("Debit", ignoreCase = true)

        db.runTransaction { transaction ->
            val orderSnap = transaction.get(orderRef)
            val orderTotal = orderSnap.getLong("totalInCents") ?: 0L
            val currentPaid = orderSnap.getLong("totalPaidInCents") ?: 0L
            val orderNumber = orderSnap.getLong("orderNumber") ?: 0L
            val newPaid = currentPaid + amountInCents
            val newRemaining = orderTotal - newPaid
            val saleId = orderSnap.getString("saleTransactionId")
            val saleRef = if (saleId != null) {
                transactionsRef.document(saleId)
            } else {
                transactionsRef.document()
            }

            val paymentEntry = hashMapOf<String, Any>(
                "paymentId" to UUID.randomUUID().toString(),
                "paymentType" to paymentType,
                "amountInCents" to amountInCents,
                "timestamp" to Date(),
                "authCode" to authCode,
                "cardBrand" to cardBrand,
                "last4" to last4,
                "entryType" to entryType,
                "referenceId" to referenceId,
                "clientReferenceId" to clientReferenceId,
                "batchNumber" to batchNumber,
                "transactionNumber" to transactionNumber,
                "invoiceNumber" to invoiceNumber,
                "pnReferenceId" to pnReferenceId,
            )
            if (cashTenderedInCents > 0L) {
                paymentEntry["cashTenderedInCents"] = cashTenderedInCents
                paymentEntry["cashChangeInCents"] = cashChangeInCents
            }
            if (splitReceipt != null && splitReceipt.isNotEmpty()) {
                paymentEntry["splitReceipt"] = splitReceipt
            }

            if (saleId == null) {
                var appTxnNumber = 0L
                if (batchId.isNotBlank()) {
                    val batchRef = MerchantFirestore.col("Batches").document(batchId)
                    val batchSnap = transaction.get(batchRef)
                    val currentCounter = batchSnap.getLong("transactionCounter") ?: 0L
                    appTxnNumber = currentCounter + 1
                    transaction.update(batchRef, "transactionCounter", appTxnNumber)
                }
                val saleData = mutableMapOf<String, Any>(
                    "orderId" to orderId,
                    "batchId" to batchId,
                    "type" to "SALE",
                    "totalPaidInCents" to newPaid,
                    "payments" to listOf(paymentEntry),
                    "status" to if (newRemaining <= 0L) "COMPLETED" else "OPEN",
                    "createdAt" to Date(),
                    "voided" to false,
                    "settled" to false,
                )
                if (orderNumber > 0L) saleData["orderNumber"] = orderNumber
                if (appTxnNumber > 0L) saleData["appTransactionNumber"] = appTxnNumber
                PosDevicePresenceSync.currentDeviceId()?.let { saleData["posDeviceId"] = it }
                if (finalTipFromCustomerScreen && isCardPayment(paymentType)) {
                    val orderTipCents = orderSnap.getLong("tipAmountInCents") ?: 0L
                    saleData["tipAmountInCents"] = orderTipCents
                    saleData["tipAdjusted"] = true
                    saleData["tipAdjustedAt"] = Timestamp.now()
                }
                transaction.set(saleRef, saleData)
                val orderUpdates = mutableMapOf<String, Any>("saleTransactionId" to saleRef.id)
                if (batchId.isNotBlank()) orderUpdates["batchId"] = batchId
                transaction.update(orderRef, orderUpdates)
            } else {
                val saleSnap = transaction.get(saleRef)
                val saleUpdates = mutableMapOf<String, Any>(
                    "payments" to FieldValue.arrayUnion(paymentEntry),
                    "totalPaidInCents" to newPaid,
                    "status" to if (newRemaining <= 0L) "COMPLETED" else "OPEN",
                )
                val alreadyTipFinal = saleSnap.getBoolean("tipAdjusted") ?: false
                if (finalTipFromCustomerScreen && isCardPayment(paymentType) && !alreadyTipFinal) {
                    val orderTipCents = orderSnap.getLong("tipAmountInCents") ?: 0L
                    saleUpdates["tipAmountInCents"] = orderTipCents
                    saleUpdates["tipAdjusted"] = true
                    saleUpdates["tipAdjustedAt"] = Timestamp.now()
                }
                transaction.update(saleRef, saleUpdates)
            }

            val newStatus = if (newRemaining <= 0L) "CLOSED" else "OPEN"
            val orderUpdates = mutableMapOf<String, Any>(
                "totalPaidInCents" to newPaid,
                "remainingInCents" to newRemaining,
                "status" to newStatus,
            )
            if (batchId.isNotBlank()) orderUpdates["batchId"] = batchId
            transaction.update(orderRef, orderUpdates)

            if (newStatus == "CLOSED" && orderSnap.getString("orderType") == "BAR_TAB") {
                for (tid in BarTabSeatHelper.seatTableIdsFromOrder(orderSnap)) {
                    transaction.update(
                        MerchantFirestore.col("Tables").document(tid),
                        mapOf("currentOrderId" to FieldValue.delete()),
                    )
                }
            }

            if (newStatus == "CLOSED" && orderSnap.getString("orderType") == "DINE_IN") {
                val layoutId = orderSnap.getString("tableLayoutId")
                @Suppress("UNCHECKED_CAST")
                val joinedRaw = orderSnap.get("joinedTableIds") as? List<*>
                val joinedIds = joinedRaw?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }?.distinct()
                val dineTableId = orderSnap.getString("tableId")?.trim().orEmpty()
                val tableIdsToClear = when {
                    !joinedIds.isNullOrEmpty() && joinedIds.size > 1 -> joinedIds
                    dineTableId.isNotEmpty() -> listOf(dineTableId)
                    else -> emptyList()
                }
                for (tid in tableIdsToClear) {
                    val tref = TableFirestoreHelper.tableRef(db, tid, layoutId)
                    transaction.update(
                        tref,
                        mapOf(
                            "status" to FieldValue.delete(),
                            "dineInOrderId" to FieldValue.delete(),
                            "reservationId" to FieldValue.delete(),
                            "updatedAt" to Date(),
                        ),
                    )
                }
            }

            newRemaining
        }
            .addOnSuccessListener { remaining -> onSuccess(remaining) }
            .addOnFailureListener { onFailure(it) }
    }
}
