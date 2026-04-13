package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.UUID

/**
 * Links floor-plan tables for large parties. Stored on each table doc as [FIELD_JOINED_IDS]
 * (same sorted list on every member). Web table layout merge preserves unknown fields.
 */
object TableJoinGroupFirestore {

    const val FIELD_JOINED_IDS = "joinedTableIds"
    const val FIELD_GROUP_ID = "tableJoinGroupId"

    fun parseJoinedIds(doc: DocumentSnapshot, selfId: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val raw = doc.get(FIELD_JOINED_IDS) as? List<*>
        val parsed = raw?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }?.distinct()?.sorted()
        val sid = selfId.trim()
        if (parsed.isNullOrEmpty()) return listOf(sid.ifEmpty { doc.id })
        return if (sid.isEmpty() || parsed.contains(sid)) parsed else parsed + sid
    }

    fun saveJoinGroup(
        db: FirebaseFirestore,
        tableLayoutId: String?,
        tableIds: List<String>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val sorted = tableIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (sorted.size < 2) {
            onFailure(IllegalArgumentException("Select at least two tables"))
            return
        }
        val groupId = UUID.randomUUID().toString()
        val batch = db.batch()
        val now = Date()
        for (id in sorted) {
            val ref = TableFirestoreHelper.tableRef(db, id, tableLayoutId)
            batch.update(
                ref,
                mapOf(
                    FIELD_JOINED_IDS to sorted,
                    FIELD_GROUP_ID to groupId,
                    "updatedAt" to now,
                ),
            )
        }
        batch.commit().addOnSuccessListener { onSuccess() }.addOnFailureListener { onFailure(it) }
    }

    fun clearJoinGroup(
        db: FirebaseFirestore,
        tableLayoutId: String?,
        tableIds: List<String>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val sorted = tableIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (sorted.isEmpty()) {
            onSuccess()
            return
        }
        val batch = db.batch()
        val now = Date()
        for (id in sorted) {
            val ref = TableFirestoreHelper.tableRef(db, id, tableLayoutId)
            batch.update(
                ref,
                mapOf(
                    FIELD_JOINED_IDS to FieldValue.delete(),
                    FIELD_GROUP_ID to FieldValue.delete(),
                    "updatedAt" to now,
                ),
            )
        }
        batch.commit().addOnSuccessListener { onSuccess() }.addOnFailureListener { onFailure(it) }
    }
}
