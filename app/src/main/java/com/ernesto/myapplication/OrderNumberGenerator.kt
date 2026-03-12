package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore

/**
 * Generates sequential order numbers using a Firestore counter document.
 * Uses a transaction to atomically read-and-increment, guaranteeing uniqueness.
 * Counter lives at: Counters/orderNumber { current: Long }
 */
object OrderNumberGenerator {

    private val counterRef = FirebaseFirestore.getInstance()
        .collection("Counters")
        .document("orderNumber")

    fun nextOrderNumber(
        onSuccess: (Long) -> Unit,
        onFailure: (Exception) -> Unit
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
