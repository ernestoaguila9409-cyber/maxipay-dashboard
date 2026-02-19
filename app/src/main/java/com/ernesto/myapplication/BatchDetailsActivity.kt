package com.ernesto.myapplication

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

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
            .get()
            .addOnSuccessListener { documents ->

                val transactionList = mutableListOf<Map<String, Any>>()

                for (doc in documents) {
                    transactionList.add(doc.data)
                }

                recyclerTransactions.adapter =
                    BatchTransactionAdapter(transactionList)
            }
    }
}
