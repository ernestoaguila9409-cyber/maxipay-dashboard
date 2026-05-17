package com.volt.shared.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.volt.shared.MerchantFirestore
import java.util.Date
import java.util.Locale

object OrderLineKdsStatus {
    const val FIELD = "kdsStatus"
    const val FIELD_KDS_SEND_BATCHES = "kdsSendBatches"
    const val BATCH_SUBFIELD_ID = "batchId"
    const val BATCH_SUBFIELD_SEND_GROUP_ID = "sendGroupId"
    const val BATCH_SUBFIELD_KDS_STATUS = "kdsStatus"
    const val BATCH_SUBFIELD_STARTED_AT = "batchStartedAt"

    const val SENT = "SENT"
    const val PREPARING = "PREPARING"
    const val READY = "READY"

    data class KdsSendBatch(
        val batchId: String,
        val sentAtMillis: Long,
        val quantity: Int,
        val kdsStatus: String,
        val batchStartedAtMillis: Long?,
    )

    fun parseKdsSendBatches(doc: DocumentSnapshot): List<KdsSendBatch> {
        @Suppress("UNCHECKED_CAST")
        val raw = doc.get(FIELD_KDS_SEND_BATCHES) as? List<*> ?: return emptyList()
        val out = mutableListOf<KdsSendBatch>()
        for (el in raw) {
            val m = el as? Map<*, *> ?: continue
            val qty = (m["quantity"] as? Number)?.toInt() ?: continue
            if (qty <= 0) continue
            val sentMs = when (val s = m["sentAt"]) {
                is Timestamp -> s.toDate().time
                is Date -> s.time
                else -> 0L
            }
            val bid = m[BATCH_SUBFIELD_ID]?.toString()?.trim().orEmpty()
            val st = m[BATCH_SUBFIELD_KDS_STATUS]?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
                .ifEmpty { SENT }
            val bsa = when (val s = m[BATCH_SUBFIELD_STARTED_AT]) {
                is Timestamp -> s.toDate().time
                is Date -> s.time
                else -> null
            }
            out.add(
                KdsSendBatch(
                    batchId = bid,
                    sentAtMillis = sentMs,
                    quantity = qty,
                    kdsStatus = st,
                    batchStartedAtMillis = bsa,
                ),
            )
        }
        return out.sortedBy { it.sentAtMillis }
    }

    fun latestBatchKdsStatusForLine(doc: DocumentSnapshot): String? {
        val batches = parseKdsSendBatches(doc)
        if (batches.isEmpty()) return null
        return batches.last().kdsStatus
    }

    const val FIELD_READY_COVERS_QTY = "kdsReadyCoversQty"
    private const val FIELD_KDS_STARTED_AT = "kdsStartedAt"

    fun markSentOnKitchenAfterSend(db: FirebaseFirestore, orderId: String) {
        if (orderId.isBlank()) return
        MerchantFirestore.col("Orders").document(orderId).collection("items")
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) return@addOnSuccessListener
                val batch = db.batch()
                var pending = 0
                for (doc in snap.documents) {
                    val cur = doc.getString(FIELD)?.trim()?.uppercase(Locale.US).orEmpty()
                    if (cur == PREPARING) continue
                    val qty = doc.getLong("quantity") ?: 0L
                    val rawCovered = doc.getLong(FIELD_READY_COVERS_QTY)
                    val covered = when {
                        rawCovered != null -> rawCovered
                        cur == READY && qty <= 1L -> qty
                        cur == READY -> 0L
                        else -> 0L
                    }
                    if (cur == READY && qty <= covered) continue
                    val updates = mutableMapOf<String, Any>(FIELD to SENT)
                    updates[FIELD_KDS_STARTED_AT] = FieldValue.delete()
                    batch.update(doc.reference, updates)
                    pending++
                }
                if (pending == 0) return@addOnSuccessListener
                val orderRef = MerchantFirestore.col("Orders").document(orderId)
                batch.update(orderRef, mapOf("updatedAt" to Date()))
                batch.commit()
            }
    }
}
