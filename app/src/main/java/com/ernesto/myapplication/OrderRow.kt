package com.ernesto.myapplication

import com.google.firebase.Timestamp

data class OrderRow(
    val id: String,
    val status: String,
    val totalCents: Long,
    val employeeName: String,
    val createdAt: Timestamp
)