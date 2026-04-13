package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

/**
 * Resolves table documents between legacy [Tables] and [tableLayouts/{id}/tables].
 * Dine-in / reservation flows set [status] on the same doc the floor plan reads.
 */
object TableFirestoreHelper {

    fun tableRef(db: FirebaseFirestore, tableId: String, tableLayoutId: String?): DocumentReference {
        val lid = tableLayoutId?.trim().orEmpty()
        return if (lid.isNotEmpty()) {
            db.collection("tableLayouts").document(lid).collection("tables").document(tableId)
        } else {
            db.collection("Tables").document(tableId)
        }
    }

    fun markDineInTableOccupied(
        db: FirebaseFirestore,
        tableId: String,
        tableLayoutId: String?,
        orderId: String
    ) {
        val ref = tableRef(db, tableId, tableLayoutId)
        ref.update(
            mapOf(
                "status" to "OCCUPIED",
                "dineInOrderId" to orderId,
                "updatedAt" to Date()
            )
        ).addOnFailureListener {
            // Non-fatal: floor plan still uses OPEN orders; status is best-effort for reservations.
        }
    }

    /** Marks every table in a joined group (or a single table) as occupied for the same order. */
    fun markDineInJoinedTablesOccupied(
        db: FirebaseFirestore,
        tableIds: List<String>,
        tableLayoutId: String?,
        orderId: String,
    ) {
        val ids = tableIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        for (id in ids) {
            markDineInTableOccupied(db, id, tableLayoutId, orderId)
        }
    }

    fun clearDineInTableStatus(db: FirebaseFirestore, tableId: String, tableLayoutId: String?) {
        val ref = tableRef(db, tableId, tableLayoutId)
        ref.update(
            mapOf(
                "status" to FieldValue.delete(),
                "dineInOrderId" to FieldValue.delete(),
                "reservationId" to FieldValue.delete(),
                "joinedTableIds" to FieldValue.delete(),
                ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1 to FieldValue.delete(),
                "updatedAt" to Date()
            )
        ).addOnFailureListener { }
    }

    fun clearDineInJoinedTablesStatus(
        db: FirebaseFirestore,
        tableIds: List<String>,
        tableLayoutId: String?,
    ) {
        val ids = tableIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (id in ids) {
            clearDineInTableStatus(db, id, tableLayoutId)
        }
    }
}
