package com.ernesto.myapplication.data

data class Transaction(
    val referenceId: String,
    val amountInCents: Long,
    val date: Long,
    val paymentType: String,
    val cardBrand: String,
    val last4: String,
    val entryType: String,
    val voided: Boolean
)

