package com.ernesto.myapplication

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.ernesto.myapplication.data.Transaction

class BatchDetailsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtBatchTitle: TextView

    private lateinit var txtCashSales: TextView
    private lateinit var txtCreditSales: TextView
    private lateinit var txtDebitSales: TextView
    private lateinit var txtCashRefunds: TextView
    private lateinit var txtCardRefunds: TextView
    private lateinit var txtNetCash: TextView
    private lateinit var txtNetCard: TextView
    private lateinit var txtBatchTotal: TextView

    private var batchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_details)

        txtBatchTitle = findViewById(R.id.txtBatchTitle)

        txtCashSales = findViewById(R.id.txtCashSales)
        txtCreditSales = findViewById(R.id.txtCreditSales)
        txtDebitSales = findViewById(R.id.txtDebitSales)
        txtCashRefunds = findViewById(R.id.txtCashRefunds)
        txtCardRefunds = findViewById(R.id.txtCardRefunds)
        txtNetCash = findViewById(R.id.txtNetCash)
        txtNetCard = findViewById(R.id.txtNetCard)
        txtBatchTotal = findViewById(R.id.txtBatchTotal)

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

                // ===============================
                // CALCULATE TOTALS
                // ===============================

                var cashSales = 0.0
                var creditSales = 0.0
                var debitSales = 0.0

                var cashRefunds = 0.0
                var cardRefunds = 0.0

                for (transaction in allTransactions) {

                    val amount = transaction.amountInCents / 100.0

                    if (transaction.type == "SALE") {
                        when (transaction.paymentType) {
                            "Cash" -> cashSales += amount
                            "Credit" -> creditSales += amount
                            "Debit" -> debitSales += amount
                        }
                    }

                    if (transaction.type == "REFUND") {
                        when (transaction.paymentType) {
                            "Cash" -> cashRefunds += amount
                            "Credit", "Debit" -> cardRefunds += amount
                        }
                    }
                }

                val netCash = cashSales - cashRefunds
                val netCard = (creditSales + debitSales) - cardRefunds
                val batchNetTotal = netCash + netCard

                // ===============================
                // DISPLAY SUMMARY
                // ===============================

                txtCashSales.text = "Cash Sales: $${format(cashSales)}"
                txtCreditSales.text = "Credit Sales: $${format(creditSales)}"
                txtDebitSales.text = "Debit Sales: $${format(debitSales)}"

                txtCashRefunds.text = "Cash Refunds: -$${format(cashRefunds)}"
                txtCardRefunds.text = "Card Refunds: -$${format(cardRefunds)}"

                txtNetCash.text = "Net Cash: $${format(netCash)}"
                txtNetCard.text = "Net Card: $${format(netCard)}"

                txtBatchTotal.text = "Batch Net Total: $${format(batchNetTotal)}"
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Failed to load batch transactions",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun format(value: Double): String {
        return String.format("%.2f", value)
    }
}