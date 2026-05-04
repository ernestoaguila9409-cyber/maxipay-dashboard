package com.ernesto.myapplication

import com.google.firebase.Timestamp

data class OrderRow(
    val id: String,
    val orderNumber: Long = 0L,
    /** Firestore workflow status — used for filters and selection (OPEN, CLOSED, …). */
    val status: String,
    /** List accent + pill text; may be UNPAID for web online pay-at-store while [status] stays OPEN. */
    val badgeStatus: String = status,
    val totalCents: Long,
    val totalRefundedInCents: Long = 0L,
    val employeeName: String,
    val customerName: String = "",
    val createdAt: Timestamp,
    val orderType: String = "",
    val preAuthAmountCents: Long = 0L,
    /** Aggregated kitchen phase for list icon; null = hide. */
    val kdsAggregateStatus: String? = null,
    /** Non-empty for online orders (e.g. "uber_eats", "doordash"). */
    val orderSource: String = "",
    val itemsCount: Int = 0,
) {
    /** Web / third-party / Uber Eats / online pickup — same scope as the dashboard Online tile. */
    val isOnlineChannelOrder: Boolean
        get() = orderSource.isNotBlank() ||
            orderType == "UBER_EATS" ||
            orderType == "ONLINE_PICKUP"

    val netCents: Long get() = (totalCents - totalRefundedInCents).coerceAtLeast(0L)

    /** Original order total in dollars; mirrors Firestore `totalInCents` (not reduced by refunds). */
    val originalTotal: Double get() = totalCents / 100.0

    /** Total refunded in dollars; mirrors `totalRefundedInCents` (defaults to 0 when absent in DB). */
    val refundedAmount: Double get() = totalRefundedInCents / 100.0

    /** Remaining after refunds: originalTotal − refundedAmount. */
    val remainingTotal: Double get() = netCents / 100.0
}