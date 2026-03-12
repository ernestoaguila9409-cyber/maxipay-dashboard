package com.ernesto.myapplication

import com.google.firebase.Timestamp

data class OrderRow(
    val id: String,
    val orderNumber: Long = 0L,
    val status: String,
    val totalCents: Long,
    val totalRefundedInCents: Long = 0L,
    val employeeName: String,
    val customerName: String = "",
    val createdAt: Timestamp,
    val orderType: String = "",
    val preAuthAmountCents: Long = 0L
) {
    val netCents: Long get() = (totalCents - totalRefundedInCents).coerceAtLeast(0L)
}