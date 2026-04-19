package com.ernesto.kds.data

import java.util.Date

data class OrderItem(
    val name: String,
    val quantity: Int,
    val modifierLines: List<OrderModifierLine>,
    /** MenuItems document id from POS line; used for KDS category filter. */
    val itemId: String = "",
    /** POS line printerLabel; used with BY_LABEL kitchen config (same as kitchen printers). */
    val printerLabel: String = "",
    /** Firestore `Orders/{orderId}/items/{lineDocId}` document id; used to update per-line [kdsStatus]. */
    val lineDocId: String = "",
    /** POS batch id inside [kdsSendBatches] when this row is one send batch on the line. */
    val kdsBatchId: String = "",
    /** Kitchen send time for this batch (timer anchor for follow-up cards). */
    val batchSentAt: Date? = null,
    /** Same field as POS `kdsStatus`: SENT / PREPARING / READY. */
    val kdsStatus: String = "",
    /** Set when this line moves to PREPARING (per-station prep timer). */
    val kdsStartedAt: Date? = null,
    /** Units already cleared by KDS READY; follow-up sends only add new work. */
    val kdsReadyCoversQty: Long = 0L,
) {
    /** Quantity to show on KDS: only the new/pending work, not previously completed units. */
    val displayQuantity: Int
        get() = if (kdsReadyCoversQty > 0L) {
            (quantity - kdsReadyCoversQty.toInt()).coerceAtLeast(1)
        } else {
            quantity
        }
}
