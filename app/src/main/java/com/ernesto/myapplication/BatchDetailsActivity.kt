package com.ernesto.myapplication

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

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

        // Use the new Transactions schema (payments[] + totalPaidInCents) and
        // aggregate amounts per payment type for this batch.
        db.collection("Transactions")
            .whereEqualTo("settled", true)
            .whereEqualTo("voided", false)
            .get()
            .addOnSuccessListener { documents ->

                var cashSales = 0.0
                var creditSales = 0.0
                var debitSales = 0.0

                var cashRefunds = 0.0
                var cardRefunds = 0.0

                for (doc in documents) {
                    val txBatchId = doc.getString("batchId") ?: ""
                    if (txBatchId != batchId) continue

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE") {
                        // New schema: payments array with per‑payment amounts and types
                        val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                        if (payments.isNotEmpty()) {
                            for (p in payments) {
                                val map = p as? Map<*, *> ?: continue
                                val paymentType =
                                    map["paymentType"]?.toString() ?: ""
                                val cents =
                                    (map["amountInCents"] as? Number)?.toLong() ?: 0L
                                val amount = cents / 100.0

                                when (paymentType) {
                                    "Cash" -> cashSales += amount
                                    "Credit" -> creditSales += amount
                                    "Debit" -> debitSales += amount
                                }
                            }
                        } else {
                            // Fallback: older flat schema with amount + paymentType
                            val paymentType = doc.getString("paymentType") ?: ""
                            val amount =
                                doc.getDouble("totalPaid")
                                    ?: doc.getDouble("amount")
                                    ?: 0.0
                            when (paymentType) {
                                "Cash" -> cashSales += amount
                                "Credit" -> creditSales += amount
                                "Debit" -> debitSales += amount
                            }
                        }
                    } else if (type == "REFUND") {
                        val paymentType = doc.getString("paymentType") ?: ""
                        val amount = doc.getDouble("amount") ?: 0.0

                        when (paymentType) {
                            "Cash" -> cashRefunds += amount
                            "Credit", "Debit" -> cardRefunds += amount
                        }
                    }
                }

                if (cashSales == 0.0 &&
                    creditSales == 0.0 &&
                    debitSales == 0.0 &&
                    cashRefunds == 0.0 &&
                    cardRefunds == 0.0
                ) {
                    Toast.makeText(
                        this,
                        "No transactions found",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addOnSuccessListener
                }

                val netCash = cashSales - cashRefunds
                val netCard = (creditSales + debitSales) - cardRefunds
                val batchNetTotal = netCash + netCard

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