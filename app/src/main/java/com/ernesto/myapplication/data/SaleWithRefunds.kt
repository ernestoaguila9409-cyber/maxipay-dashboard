package com.ernesto.myapplication.data

data class SaleWithRefunds(
    val sale: Transaction,
    val refunds: List<Transaction>
)
