package com.ernesto.myapplication

import com.google.firebase.Timestamp

data class OrderRow(
    val id: String,
    val status: String,
    val totalCents: Long,
    val totalRefundedInCents: Long = 0L,
    val employeeName: String,
    val createdAt: Timestamp
) {
    val netCents: Long get() = (totalCents - totalRefundedInCents).coerceAtLeast(0L)
}