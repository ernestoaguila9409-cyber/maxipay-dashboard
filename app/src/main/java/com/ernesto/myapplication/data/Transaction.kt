package com.ernesto.myapplication.data

data class Transaction(
    val referenceId: String,
    val amountInCents: Long,
    val date: Long,
    val paymentType: String
)
