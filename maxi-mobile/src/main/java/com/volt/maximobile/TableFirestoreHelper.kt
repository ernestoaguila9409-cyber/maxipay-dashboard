package com.volt.maximobile

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

object TableFirestoreHelper {

    private const val FIELD_RESERVATION_MAP_UI_NORMS_V1 = "reservationMapUiNormsV1"

    fun tableRef(db: FirebaseFirestore, tableId: String, tableLayoutId: String?): DocumentReference {
        val lid = tableLayoutId?.trim().orEmpty()
        return if (lid.isNotEmpty()) {
            MerchantFirestore.col("tableLayouts").document(lid).collection("tables").document(tableId)
        } else {
            MerchantFirestore.col("Tables").document(tableId)
        }
    }

    fun markDineInTableOccupied(
        db: FirebaseFirestore,
        tableId: String,
        tableLayoutId: String?,
        orderId: String,
    ) {
        val ref = tableRef(db, tableId, tableLayoutId)
        ref.update(
            mapOf(
                "status" to "OCCUPIED",
                "dineInOrderId" to orderId,
                "updatedAt" to Date(),
            ),
        ).addOnFailureListener { }
    }

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
                FIELD_RESERVATION_MAP_UI_NORMS_V1 to FieldValue.delete(),
                "updatedAt" to Date(),
            ),
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
