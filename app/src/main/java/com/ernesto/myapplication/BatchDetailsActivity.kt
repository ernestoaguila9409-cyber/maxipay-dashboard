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
    private lateinit var orderTypeBreakdownContainer: LinearLayout
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
        orderTypeBreakdownContainer = findViewById(R.id.orderTypeBreakdownContainer)
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
                val orderAmounts = mutableMapOf<String, Double>()

                for (doc in documents) {
                    val txBatchId = doc.getString("batchId") ?: ""
                    if (txBatchId != batchId) continue

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE" || type == "CAPTURE") {
                        val orderId = doc.getString("orderId")?.takeIf { it.isNotBlank() }
                        orderId?.let { orderIds.add(it) }

                        var txTotal = 0.0
                        val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                        if (payments.isNotEmpty()) {
                            for (p in payments) {
                                val map = p as? Map<*, *> ?: continue
                                val paymentType =
                                    map["paymentType"]?.toString() ?: ""
                                val cents =
                                    (map["amountInCents"] as? Number)?.toLong() ?: 0L
                                val amount = cents / 100.0
                                txTotal += amount

                                when (paymentType) {
                                    "Cash" -> cashSales += amount
                                    "Credit" -> creditSales += amount
                                    "Debit" -> debitSales += amount
                                }
                            }
                        } else {
                            val paymentType = doc.getString("paymentType") ?: ""
                            val amount =
                                doc.getDouble("totalPaid")
                                    ?: doc.getDouble("amount")
                                    ?: 0.0
                            txTotal = amount
                            when (paymentType) {
                                "Cash" -> cashSales += amount
                                "Credit" -> creditSales += amount
                                "Debit" -> debitSales += amount
                            }
                        }

                        if (orderId != null && txTotal > 0.0) {
                            orderAmounts[orderId] = (orderAmounts[orderId] ?: 0.0) + txTotal
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

                loadAndDisplayOrderTypeBreakdown(orderAmounts)
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

    private fun loadAndDisplayOrderTypeBreakdown(orderAmounts: Map<String, Double>) {
        orderTypeBreakdownContainer.removeAllViews()
        if (orderAmounts.isEmpty()) return

        val typeTotals = mutableMapOf<String, Double>()
        var pending = orderAmounts.size

        fun maybeDisplay() {
            pending--
            if (pending > 0) return

            if (typeTotals.isEmpty()) return

            val header = TextView(this).apply {
                text = "Sales by Order Type:"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            }
            orderTypeBreakdownContainer.addView(header)

            val displayOrder = listOf("DINE_IN", "TO_GO", "BAR_TAB", "BAR")
            val allKeys = typeTotals.keys.sortedBy { key -> displayOrder.indexOf(key).let { if (it == -1) 99 else it } }

            for (key in allKeys) {
                val amount = typeTotals[key] ?: continue
                val label = when (key) {
                    "DINE_IN" -> "Dine In"
                    "TO_GO" -> "To-Go"
                    "BAR_TAB" -> "Bar Tab"
                    "BAR" -> "Bar"
                    else -> key
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 4) }
                }
                val txtLabel = TextView(this).apply {
                    text = "$label:"
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val txtAmount = TextView(this).apply {
                    text = "$${format(amount)}"
                    textSize = 16f
                    gravity = Gravity.END
                }
                row.addView(txtLabel)
                row.addView(txtAmount)
                orderTypeBreakdownContainer.addView(row)
            }
        }

        for ((orderId, amount) in orderAmounts) {
            db.collection("Orders").document(orderId).get()
                .addOnSuccessListener { snap ->
                    val orderType = snap.getString("orderType") ?: "OTHER"
                    typeTotals[orderType] = (typeTotals[orderType] ?: 0.0) + amount
                    maybeDisplay()
                }
                .addOnFailureListener {
                    typeTotals["OTHER"] = (typeTotals["OTHER"] ?: 0.0) + amount
                    maybeDisplay()
                }
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