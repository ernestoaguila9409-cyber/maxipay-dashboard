package com.ernesto.myapplication.engine

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import java.util.*

class PaymentEngine(private val db: FirebaseFirestore) {

    fun processPayment(
        orderId: String,
        paymentType: String,
        amountInCents: Long,

        authCode: String = "",
        cardBrand: String = "",
        last4: String = "",
        entryType: String = "",
        terminalReference: String = "",

        onSuccess: (Long) -> Unit,
        onFailure: (Exception) -> Unit
    ) {

        val orderRef = db.collection("Orders").document(orderId)
        val transactionsRef = db.collection("Transactions")

        db.runTransaction { transaction ->

            val orderSnap = transaction.get(orderRef)

            val orderTotal = orderSnap.getLong("totalInCents") ?: 0L
            val currentPaid = orderSnap.getLong("totalPaidInCents") ?: 0L

            val newPaid = currentPaid + amountInCents
            val newRemaining = orderTotal - newPaid

            val saleId = orderSnap.getString("saleTransactionId")

            val saleRef = if (saleId != null) {
                transactionsRef.document(saleId)
            } else {
                transactionsRef.document()
            }

            val paymentEntry = hashMapOf(
                "paymentType" to paymentType,
                "amountInCents" to amountInCents,
                "timestamp" to Date(),
                "authCode" to authCode,
                "cardBrand" to cardBrand,
                "last4" to last4,
                "entryType" to entryType,
                "terminalReference" to terminalReference
            )

            if (saleId == null) {

                transaction.set(saleRef, mapOf(
                    "orderId" to orderId,
                    "type" to "SALE",
                    "totalPaidInCents" to newPaid,
                    "payments" to listOf(paymentEntry),
                    "status" to if (newRemaining <= 0L) "COMPLETED" else "OPEN",
                    "createdAt" to Date(),
                    "voided" to false,
                    "settled" to false
                ))

                transaction.update(orderRef, mapOf(
                    "saleTransactionId" to saleRef.id
                ))
            }

            transaction.update(orderRef, mapOf(
                "totalPaidInCents" to newPaid,
                "remainingInCents" to newRemaining,
                "status" to if (newRemaining <= 0L) "CLOSED" else "OPEN"
            ))

            newRemaining

        }.addOnSuccessListener { remaining ->
            onSuccess(remaining)
        }.addOnFailureListener {
            onFailure(it)
        }
    }
}