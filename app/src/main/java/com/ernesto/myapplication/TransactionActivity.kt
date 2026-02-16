package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionStore

class TransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerTransactions)

        val transactions = TransactionStore.getTransactions()

        recyclerView.layoutManager = LinearLayoutManager(this)

        recyclerView.adapter = TransactionAdapter(transactions) { transaction ->
            showTransactionOptions(transaction)
        }
    }

    private fun showTransactionOptions(transaction: Transaction) {

        AlertDialog.Builder(this)
            .setTitle("Transaction Options")
            .setMessage("Choose an action for this transaction")
            .setPositiveButton("Refund") { _, _ ->
                processRefund(transaction)
            }
            .setNegativeButton("Void") { _, _ ->
                processVoid(transaction)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun processVoid(transaction: Transaction) {
        Toast.makeText(this, "Transaction Voided", Toast.LENGTH_SHORT).show()
    }

    private fun processRefund(transaction: Transaction) {
        Toast.makeText(this, "Refund Processed", Toast.LENGTH_SHORT).show()
    }
}
