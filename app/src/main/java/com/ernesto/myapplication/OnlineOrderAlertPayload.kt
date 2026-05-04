package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Minimal order snapshot for the new-online-order alert UI + navigation.
 *
 * Firestore field names are configurable via [OnlineOrderAlertFirestoreListener] query;
 * parsing here supports common variants used across POS / web backends.
 */
data class OnlineOrderAlertPayload(
    val orderId: String,
    val orderNumber: Long,
    val customerName: String,
    val itemCount: Int,
)

internal fun DocumentSnapshot.toOnlineOrderAlertPayloadOrNull(): OnlineOrderAlertPayload? {
    val id = id.ifBlank { return null }
    val orderNumber = getLong("orderNumber") ?: 0L
    val customer = getString("customerName")?.trim().orEmpty().ifBlank { "Guest" }
    val itemCount = resolveItemCount(this)
    return OnlineOrderAlertPayload(
        orderId = id,
        orderNumber = orderNumber,
        customerName = customer,
        itemCount = itemCount,
    )
}

private fun resolveItemCount(doc: DocumentSnapshot): Int {
    val direct = doc.getLong("itemsCount")
        ?: doc.getLong("itemCount")
        ?: doc.getLong("items")
    if (direct != null && direct > 0L) return direct.toInt().coerceAtLeast(0)
    return 0
}
