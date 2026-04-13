package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot

/**
 * Shared rules for when a table doc marked [RESERVED] should look/behave as reserved on the floor:
 * layout [reservationHoldStartsMinutesBeforeSlot] + [reservationGraceAfterSlotMinutes], plus the
 * optional preview set built from [Reservations] (same as [TableSelectionActivity]).
 */
object TableReservationHoldDisplay {

    fun activeReservationDocForGroup(
        groupIds: List<String>,
        snap: QuerySnapshot?,
        tableReservationIds: Map<String, String>,
    ): DocumentSnapshot? {
        val s = snap ?: return null
        for (tid in groupIds) {
            val rid = tableReservationIds[tid]?.trim().orEmpty()
            if (rid.isNotEmpty()) {
                s.documents.find { it.id == rid }
                    ?.takeIf { ReservationFirestoreHelper.isReservationActiveForList(it) }
                    ?.let { return it }
            }
        }
        for (doc in s.documents) {
            if (!ReservationFirestoreHelper.isReservationActiveForList(doc)) continue
            val primary = doc.getString("tableId")?.trim().orEmpty()
            val joined = TableJoinGroupFirestore.parseJoinedIds(doc, primary)
            if (groupIds.any { it in joined }) return doc
        }
        return null
    }

    fun isFirestoreReservedForHoldUi(
        groupIds: List<String>,
        tableStatuses: Map<String, String>,
        tableReservationIds: Map<String, String>,
        snap: QuerySnapshot?,
        layoutGraceAfterSlotMs: Long,
        layoutHoldStartsBeforeSlotMs: Long,
    ): Boolean {
        if (!groupIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "RESERVED" }) return false
        val resDoc = activeReservationDocForGroup(groupIds, snap, tableReservationIds) ?: return true
        if (ReservationFirestoreHelper.isReservationHoldExpired(resDoc, layoutGraceAfterSlotMs)) return false
        val slot = ReservationFirestoreHelper.reservationSlotMillisForExpiry(resDoc) ?: return true
        val now = System.currentTimeMillis()
        return now >= slot - layoutHoldStartsBeforeSlotMs
    }

    fun rebuildPreviewReservedTableIds(
        snap: QuerySnapshot?,
        beforeMs: Long,
        graceAfterMs: Long,
    ): Set<String> {
        if (beforeMs <= 0L || snap == null) return emptySet()
        val now = System.currentTimeMillis()
        val out = mutableSetOf<String>()
        for (doc in snap.documents) {
            if (!ReservationFirestoreHelper.isReservationActiveForList(doc)) continue
            val slot = ReservationFirestoreHelper.reservationSlotMillisForExpiry(doc) ?: continue
            if (ReservationFirestoreHelper.isReservationHoldExpired(doc, graceAfterMs)) continue
            if (now < slot - beforeMs) continue
            val primary = doc.getString("tableId")?.trim().orEmpty()
            if (primary.isEmpty()) continue
            out.addAll(TableJoinGroupFirestore.parseJoinedIds(doc, primary))
        }
        return out
    }

    fun isPreviewReservedForGroup(groupIds: List<String>, previewIds: Set<String>): Boolean =
        groupIds.any { it in previewIds }

    fun isUiReservedForHold(
        groupIds: List<String>,
        tableStatuses: Map<String, String>,
        tableReservationIds: Map<String, String>,
        snap: QuerySnapshot?,
        layoutGraceAfterSlotMs: Long,
        layoutHoldStartsBeforeSlotMs: Long,
        previewIds: Set<String>,
    ): Boolean =
        isFirestoreReservedForHoldUi(
            groupIds,
            tableStatuses,
            tableReservationIds,
            snap,
            layoutGraceAfterSlotMs,
            layoutHoldStartsBeforeSlotMs,
        ) || isPreviewReservedForGroup(groupIds, previewIds)
}
