package com.volt.maximobile.data

object TransactionStore {

    private val transactions = mutableListOf<Transaction>()

    fun addTransaction(transaction: Transaction) {
        transactions.add(transaction)
    }

    fun getTransactions(): List<Transaction> {
        return transactions
    }
}
