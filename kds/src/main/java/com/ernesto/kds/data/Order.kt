package com.ernesto.kds.data

import java.util.Date

data class Order(
    /** Firestore `Orders/{id}` document id (updates use this). */
    val id: String,
    /**
     * Stable key for UI lists when one Firestore order maps to multiple KDS cards
     * (e.g. an in-progress wave and a newly sent wave). Defaults to [id].
     */
    val cardKey: String = id,
    val tableName: String,
    /** From Firestore `customerName` when attached on POS (dine-in, etc.). */
    val customerName: String = "",
    val status: String,
    val createdAt: Date?,
    /** Latest POS kitchen send; elapsed timer while waiting on START uses this after follow-up sends. */
    val lastKitchenSentAt: Date? = null,
    /** Prep timer anchor: earliest [OrderItem.kdsStartedAt] among visible PREPARING lines (split KDS). */
    val kitchenStartedAt: Date? = null,
    val items: List<OrderItem>,
    /** One of: DINE_IN, TO_GO, BAR (POS orderType). */
    val orderType: String,
    /** POS order number for header (e.g. 283 shown as #283). */
    val orderNumber: Long = 0L,
    /** True when the order comes from an online channel (web, Uber Eats, etc.). */
    val isOnlineOrder: Boolean = false,
) {
    fun isOpen(): Boolean = status.equals("OPEN", ignoreCase = true)
    fun isPreparing(): Boolean = status.equals("PREPARING", ignoreCase = true)
    fun isReady(): Boolean = status.equals("READY", ignoreCase = true)
}
