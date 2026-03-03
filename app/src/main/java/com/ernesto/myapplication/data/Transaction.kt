package com.ernesto.myapplication.data

data class Transaction(
    val id: String = "",

    val referenceId: String = "",
    val amountInCents: Long = 0L,
    val date: Long = 0L,

    val paymentType: String = "",
    val cardBrand: String = "",
    val last4: String = "",
    val entryType: String = "",

    val voided: Boolean = false,

    val type: String = "SALE",
    val originalReferenceId: String = "",

    val isMixed: Boolean = false
)