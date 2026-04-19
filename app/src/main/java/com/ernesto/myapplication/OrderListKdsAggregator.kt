package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale

/**
 * Single icon on the **Orders list** from all line [OrderLineKdsStatus.FIELD] values:
 * - Any PREPARING → PREPARING
 * - All READY → READY
 * - Any READY but not all → PREPARING (ticket still in progress)
 * - Otherwise (e.g. all SENT / blank) → SENT
 */
object OrderListKdsAggregator {

    fun orderReleasedToKitchen(doc: DocumentSnapshot): Boolean =
        doc.getTimestamp("lastKitchenSentAt") != null ||
            doc.getTimestamp("kitchenChitsPrintedAt") != null

    private fun isActiveLine(doc: DocumentSnapshot): Boolean {
        if (doc.getBoolean("voided") == true) return false
        if (doc.getTimestamp("voidedAt") != null) return false
        return true
    }

    /**
     * @return [OrderLineKdsStatus] value, or null to hide the list icon (no active lines).
     */
    fun aggregatePhase(itemDocs: List<DocumentSnapshot>): String? {
        val active = itemDocs.filter { isActiveLine(it) }
        if (active.isEmpty()) return null
        val st = active.map { doc ->
            doc.getString(OrderLineKdsStatus.FIELD)?.trim()?.uppercase(Locale.US).orEmpty()
        }
        if (st.any { it == OrderLineKdsStatus.PREPARING }) return OrderLineKdsStatus.PREPARING
        if (st.isNotEmpty() && st.all { it == OrderLineKdsStatus.READY }) return OrderLineKdsStatus.READY
        if (st.any { it == OrderLineKdsStatus.READY }) return OrderLineKdsStatus.PREPARING
        return OrderLineKdsStatus.SENT
    }
}
