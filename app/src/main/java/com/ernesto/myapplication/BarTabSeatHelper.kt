package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Bar tabs: orders reference bar seat [Tables] docs via [seatIds]; each seat may store [currentOrderId].
 * Legacy orders used only [seatName] (matched to table name).
 */
object BarTabSeatHelper {

    /** Table document IDs linked to a bar-tab order. */
    fun seatTableIdsFromOrder(snap: DocumentSnapshot): List<String> {
        @Suppress("UNCHECKED_CAST")
        val fromList = snap.get("seatIds") as? List<*>
        if (!fromList.isNullOrEmpty()) {
            return fromList.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
        }
        val single = snap.getString("tableId")
        if (!single.isNullOrBlank()) return listOf(single)
        return emptyList()
    }

    /** Clear [currentOrderId] on all seats for this order (async). */
    fun releaseSeatsForOrderId(db: FirebaseFirestore, orderId: String) {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) releaseSeatsFromOrderSnapshot(db, snap)
            }
    }

    fun releaseSeatsFromOrderSnapshot(db: FirebaseFirestore, snap: DocumentSnapshot) {
        val ids = seatTableIdsFromOrder(snap)
        if (ids.isEmpty()) return
        val batch = db.batch()
        for (tid in ids) {
            batch.update(
                db.collection("Tables").document(tid),
                mapOf("currentOrderId" to FieldValue.delete())
            )
        }
        batch.commit()
    }
}
