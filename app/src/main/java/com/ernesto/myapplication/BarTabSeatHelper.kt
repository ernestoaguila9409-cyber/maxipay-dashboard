package com.ernesto.myapplication

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Date

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

    /**
     * Frees one bar seat from an OPEN BAR_TAB: clears [currentOrderId] on that table.
     * If it was the only seat, deletes the order (same idea as canceling an empty tab).
     * Requires zero tab total and zero remaining balance.
     *
     * @param fallbackSeatIds when the order has no [seatIds]/[tableId] (legacy), use this list
     */
    fun detachOccupiedBarSeat(
        db: FirebaseFirestore,
        orderId: String,
        tableIdToFree: String,
        seatNamesByTableId: Map<String, String>,
        fallbackSeatIds: List<String>,
    ): Task<Void> {
        val orderRef = db.collection("Orders").document(orderId)
        return db.runTransaction { tx ->
            val snap = tx.get(orderRef)
            if (!snap.exists()) {
                throw FirebaseFirestoreException("Order not found", FirebaseFirestoreException.Code.NOT_FOUND)
            }
            if (snap.getString("orderType") != "BAR_TAB" || snap.getString("status") != "OPEN") {
                throw FirebaseFirestoreException(
                    "Order is not an open bar tab",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION
                )
            }
            val total = snap.getLong("totalInCents") ?: 0L
            val remaining = snap.getLong("remainingInCents") ?: 0L
            if (total > 0L || remaining > 0L) {
                throw FirebaseFirestoreException(
                    "Tab must be at $0.00 to free the seat from here",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION
                )
            }
            var seatIds = seatTableIdsFromOrder(snap).distinct().toMutableList()
            if (seatIds.isEmpty() && fallbackSeatIds.isNotEmpty()) {
                seatIds = fallbackSeatIds.distinct().toMutableList()
            }
            if (!seatIds.contains(tableIdToFree)) {
                throw FirebaseFirestoreException(
                    "Seat is not linked to this tab",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION
                )
            }
            seatIds.remove(tableIdToFree)
            val tableRef = db.collection("Tables").document(tableIdToFree)
            tx.update(tableRef, mapOf("currentOrderId" to FieldValue.delete()))
            when {
                seatIds.isEmpty() -> tx.delete(orderRef)
                else -> {
                    val combined = seatIds.joinToString(", ") { tid ->
                        seatNamesByTableId[tid] ?: tid
                    }
                    tx.update(
                        orderRef,
                        mapOf(
                            "seatIds" to seatIds,
                            "seatName" to combined,
                            "tableName" to combined,
                            "tableId" to seatIds.first(),
                            "guestCount" to seatIds.size,
                            "updatedAt" to Date(),
                        )
                    )
                }
            }
            null
        }
    }
}
