package com.volt.maximobile.data

data class SaleWithRefunds(
    val sale: Transaction,
    val refunds: List<Transaction>
)
