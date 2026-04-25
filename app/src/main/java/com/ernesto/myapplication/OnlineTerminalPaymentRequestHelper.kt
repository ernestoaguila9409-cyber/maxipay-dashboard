package com.ernesto.myapplication

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Web online ordering creates `OnlineTerminalPaymentRequests/{orderId}` so the POS can prompt
 * staff to run SPIn / Dejavoo for that order. Cleared when the order is fully paid on the tablet.
 */
object OnlineTerminalPaymentRequestHelper {

    const val COLLECTION = "OnlineTerminalPaymentRequests"

    fun markCompletedIfPresent(db: FirebaseFirestore, orderId: String?) {
        val oid = orderId?.trim().orEmpty()
        if (oid.isEmpty()) return
        db.collection(COLLECTION).document(oid).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) return@addOnSuccessListener
                snap.reference.update(
                    mapOf(
                        "status" to "completed",
                        "completedAt" to FieldValue.serverTimestamp(),
                    ),
                ).addOnFailureListener { /* doc may be missing or rules */ }
            }
    }
}
