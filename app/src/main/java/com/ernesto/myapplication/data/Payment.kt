package com.ernesto.myapplication.data

data class Payment(
    val amount: Double = 0.0,
    val paymentType: String = "",
    val authCode: String = "",
    val cardBrand: String = "",
    val last4: String = "",
    val entryType: String = "",
    val terminalReference: String = ""
)