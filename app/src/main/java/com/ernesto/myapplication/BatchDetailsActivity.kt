package com.ernesto.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.SaleWithRefunds

class BatchDetailsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtBatchTitle: TextView
    private lateinit var recyclerTransactions: RecyclerView

    private var batchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_details)

        txtBatchTitle = findViewById(R.id.txtBatchTitle)
        recyclerTransactions = findViewById(R.id.recyclerBatchTransactions)

        recyclerTransactions.layoutManager = LinearLayoutManager(this)

        batchId = intent.getStringExtra("batchId") ?: ""

        txtBatchTitle.text = "Batch ID: $batchId"

        loadBatchTransactions()
    }

    private fun loadBatchTransactions() {

        db.collection("Transactions")
            .whereEqualTo("batchId", batchId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->

                // 🔥 DEBUG LOGS
                Log.d("BATCH_DEBUG", "Batch ID: $batchId")
                Log.d("BATCH_DEBUG", "Documents size: ${documents.size()}")

                for (doc in documents) {
                    Log.d("BATCH_DEBUG", doc.data.toString())
                }

                if (documents.isEmpty) {
                    Toast.makeText(
                        this,
                        "No transactions found",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addOnSuccessListener
                }

                val allTransactions = mutableListOf<Transaction>()

                for (doc in documents) {

                    val transaction = Transaction(
                        referenceId = doc.getString("referenceId") ?: "",
                        amountInCents = ((doc.getDouble("amount") ?: 0.0) * 100).toLong(),
                        date = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L,
                        paymentType = doc.getString("paymentType") ?: "",
                        cardBrand = doc.getString("cardBrand") ?: "",
                        last4 = doc.getString("last4") ?: "",
                        entryType = doc.getString("entryType") ?: "",
                        voided = doc.getBoolean("voided") ?: false,
                        type = doc.getString("type") ?: "SALE",
                        originalReferenceId = doc.getString("originalReferenceId") ?: ""
                    )

                    allTransactions.add(transaction)
                }

                val sales = allTransactions.filter { it.type == "SALE" }
                val refunds = allTransactions.filter { it.type == "REFUND" }

                val groupedList = mutableListOf<SaleWithRefunds>()

                sales.forEach { sale ->
                    val saleRefunds =
                        refunds.filter { it.originalReferenceId == sale.referenceId }

                    groupedList.add(
                        SaleWithRefunds(
                            sale = sale,
                            refunds = saleRefunds
                        )
                    )
                }

                recyclerTransactions.adapter =
                    BatchTransactionAdapter(groupedList)
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Failed to load batch transactions",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }
}
