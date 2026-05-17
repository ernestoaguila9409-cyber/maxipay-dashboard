package com.volt.shared.data

import com.google.firebase.Timestamp

data class OrderRow(
    val id: String,
    val orderNumber: Long = 0L,
    val status: String,
    val badgeStatus: String = status,
    val totalCents: Long,
    val totalRefundedInCents: Long = 0L,
    val employeeName: String,
    val customerName: String = "",
    val createdAt: Timestamp,
    val orderType: String = "",
    val preAuthAmountCents: Long = 0L,
    val kdsAggregateStatus: String? = null,
    val orderSource: String = "",
    val itemsCount: Int = 0,
) {
    val isOnlineChannelOrder: Boolean
        get() = orderSource.isNotBlank() ||
            orderType == "UBER_EATS" ||
            orderType == "ONLINE_PICKUP"

    val netCents: Long get() = (totalCents - totalRefundedInCents).coerceAtLeast(0L)
    val originalTotal: Double get() = totalCents / 100.0
    val refundedAmount: Double get() = totalRefundedInCents / 100.0
    val remainingTotal: Double get() = netCents / 100.0
}
