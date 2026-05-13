package com.volt.maximobile

import com.google.firebase.firestore.FirebaseFirestore

object OrderNumberGenerator {

    private val counterRef get() = MerchantFirestore.doc("Counters", "orderNumber")

    fun nextOrderNumber(
        onSuccess: (Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val db = FirebaseFirestore.getInstance()
        db.runTransaction { transaction ->
            val snap = transaction.get(counterRef)
            val current = snap.getLong("current") ?: 0L
            val next = current + 1
            transaction.set(counterRef, hashMapOf("current" to next))
            next
        }
            .addOnSuccessListener { next -> onSuccess(next) }
            .addOnFailureListener { e -> onFailure(e) }
    }
}
