package com.ernesto.myapplication

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
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
    private lateinit var taxBreakdownContainer: LinearLayout
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
        taxBreakdownContainer = findViewById(R.id.taxBreakdownContainer)
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

                val orderIds = mutableSetOf<String>()

                for (doc in documents) {
                    val txBatchId = doc.getString("batchId") ?: ""
                    if (txBatchId != batchId) continue

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE") {
                        doc.getString("orderId")?.takeIf { it.isNotBlank() }?.let { orderIds.add(it) }
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

                loadAndDisplayTaxBreakdown(orderIds.toList())

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

    private fun loadAndDisplayTaxBreakdown(orderIds: List<String>) {
        taxBreakdownContainer.removeAllViews()
        if (orderIds.isEmpty()) return

        val taxTotals = mutableMapOf<String, Long>()
        var pending = orderIds.size

        fun maybeDisplayTaxes() {
            pending--
            if (pending == 0) {
                if (taxTotals.isNotEmpty()) {
                    val header = TextView(this).apply {
                        text = "Taxes collected:"
                        textSize = 14f
                        setPadding(0, 8, 0, 4)
                    }
                    taxBreakdownContainer.addView(header)
                }
                for ((taxName, amountCents) in taxTotals.toSortedMap()) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    row.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 4) }
                    val label = TextView(this).apply {
                        text = "$taxName:"
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val amount = TextView(this).apply {
                        text = "$${format(amountCents.toDouble() / 100.0)}"
                        textSize = 16f
                        gravity = Gravity.END
                    }
                    row.addView(label)
                    row.addView(amount)
                    taxBreakdownContainer.addView(row)
                }
            }
        }

        for (oid in orderIds) {
            db.collection("Orders").document(oid).get()
                .addOnSuccessListener { snap ->
                    val breakdown = snap.get("taxBreakdown") as? List<*>
                    if (breakdown != null) {
                        for (item in breakdown) {
                            val map = item as? Map<*, *> ?: continue
                            val name = map["name"]?.toString() ?: continue
                            val cents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                            taxTotals[name] = (taxTotals[name] ?: 0L) + cents
                        }
                    }
                    maybeDisplayTaxes()
                }
                .addOnFailureListener { maybeDisplayTaxes() }
        }
    }

    private fun format(value: Double): String {
        return String.format("%.2f", value)
    }
}