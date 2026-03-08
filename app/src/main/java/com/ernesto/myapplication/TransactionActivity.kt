package com.ernesto.myapplication

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionPayment
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import android.util.Log
import java.util.*
import com.ernesto.myapplication.data.SaleWithRefunds
import com.google.firebase.firestore.FieldValue
class TransactionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnFilter: ImageButton
    private lateinit var adapter: TransactionAdapter
    private val transactionList = mutableListOf<SaleWithRefunds>()
    private val allSalesWithRefunds = mutableListOf<SaleWithRefunds>()
    private val db = FirebaseFirestore.getInstance()
    private var currentEmployeeName: String = ""

    private var typeFilter: String = "ALL" // ALL, VOID, REFUND, CASH, CREDIT_DEBIT
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null
    private var last4Filter: String? = null // last 4 digits of card to filter by, null = any
    private var filterBatchId: String? = null // when set, show only transactions in this (open) batch
    private var currentTransactionNoBatch: Boolean = false // true when "Current Transaction" but no open batch → show empty
    private var showUnsettledAndTodayRefunds: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        val currentTransactionView = intent.getBooleanExtra("CURRENT_TRANSACTION", false)
        showUnsettledAndTodayRefunds = intent.getBooleanExtra("SHOW_UNSETTLED_AND_TODAY_REFUNDS", false)
        filterBatchId = intent.getStringExtra("BATCH_ID")?.takeIf { it.isNotBlank() }
        currentTransactionNoBatch = currentTransactionView && filterBatchId == null && !showUnsettledAndTodayRefunds

        recyclerView = findViewById(R.id.recyclerTransactions)
        val txtTitle = findViewById<TextView>(R.id.txtTitle)
        txtTitle.text = if (currentTransactionView) "Current Transaction" else "Transactions"
        btnFilter = findViewById(R.id.btnFilter)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(transactionList) { transaction ->
            showTransactionOptions(transaction)
        }

        recyclerView.adapter = adapter
        currentEmployeeName = intent.getStringExtra("employeeName") ?: ""
        btnFilter.setOnClickListener { showFilterDialog() }
        loadTransactions()
    }

    // 🔥 LOAD + GROUP SALES WITH REFUNDS (CORRECT VERSION)
    private fun loadTransactions() {

        db.collection("Transactions")
            .addSnapshotListener { snapshots, error ->

                if (error != null) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                if (snapshots == null) return@addSnapshotListener

                transactionList.clear()
                val allTransactions = mutableListOf<Transaction>()

                val startOfToday = if (showUnsettledAndTodayRefunds) {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.time
                } else null

                snapshots.forEach { doc ->

                    if (currentTransactionNoBatch) return@forEach

                    if (showUnsettledAndTodayRefunds) {
                        val voided = doc.getBoolean("voided") ?: false
                        if (voided) return@forEach
                        val settled = doc.getBoolean("settled") ?: false
                        val docType = doc.getString("type") ?: "SALE"
                        if (docType == "SALE" && settled) return@forEach
                        if (docType == "REFUND") {
                            val ts = doc.getTimestamp("createdAt")?.toDate()
                                ?: doc.getTimestamp("timestamp")?.toDate()
                            if (ts == null || ts.before(startOfToday)) return@forEach
                        }
                    } else if (filterBatchId != null) {
                        val docBatchId = doc.getString("batchId")?.takeIf { it.isNotBlank() }
                        if (docBatchId != filterBatchId) return@forEach
                    }

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE" &&
                        !doc.contains("totalPaid") &&
                        !doc.contains("totalPaidInCents")
                    ) {
                        return@forEach
                    }

                    val createdAt = doc.getTimestamp("createdAt")?.toDate()
                    val oldTimestamp = doc.getTimestamp("timestamp")?.toDate()

                    val dateMillis = createdAt?.time ?: oldTimestamp?.time ?: 0L

                    val paymentsRaw = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                    val payments = paymentsRaw.map { p ->
                        val amountCents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
                        TransactionPayment(
                            paymentType = p["paymentType"]?.toString() ?: "",
                            cardBrand = p["cardBrand"]?.toString() ?: "",
                            last4 = p["last4"]?.toString() ?: "",
                            entryType = p["entryType"]?.toString() ?: "",
                            amountInCents = amountCents,
                            referenceId = p["referenceId"]?.toString() ?: p["terminalReference"]?.toString() ?: "",
                            clientReferenceId = p["clientReferenceId"]?.toString() ?: "",
                            batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                            transactionNumber = (p["transactionNumber"] as? Number)?.toString() ?: p["transactionNumber"]?.toString() ?: "",
                            paymentId = p["paymentId"]?.toString() ?: ""
                        )
                    }
                    val firstPayment = payments.firstOrNull()

                    val paymentType = firstPayment?.paymentType ?: doc.getString("paymentType") ?: ""
                    val cardBrand = firstPayment?.cardBrand ?: doc.getString("cardBrand") ?: ""
                    val last4 = firstPayment?.last4 ?: doc.getString("last4") ?: ""
                    val entryType = firstPayment?.entryType ?: doc.getString("entryType") ?: ""
                    // Dejavoo sale response fields for Void: referenceId, batchNumber, transactionNumber (from first payment)
                    val firstRaw = paymentsRaw.firstOrNull()
                    val gatewayRef = firstRaw?.get("referenceId")?.toString()
                        ?: firstRaw?.get("terminalReference")?.toString() ?: ""
                    val clientRef = firstRaw?.get("clientReferenceId")?.toString() ?: ""
                    val batchNum = firstRaw?.get("batchNumber")?.toString() ?: ""
                    val txNum = firstRaw?.get("transactionNumber")?.toString() ?: ""
                    val invNum = firstRaw?.get("invoiceNumber")?.toString() ?: ""

                    val isMixed = payments.size > 1

                    val amountInCents = when (type) {
                        "REFUND" -> ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
                        else -> doc.getLong("totalPaidInCents")
                            ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
                    }

                    val transaction = Transaction(
                        referenceId = doc.id,
                        gatewayReferenceId = gatewayRef,
                        clientReferenceId = clientRef,
                        batchNumber = batchNum,
                        transactionNumber = txNum,
                        invoiceNumber = invNum,
                        amountInCents = amountInCents,
                        date = dateMillis,
                        paymentType = paymentType,
                        cardBrand = cardBrand,
                        last4 = last4,
                        entryType = entryType,
                        voided = doc.getBoolean("voided") ?: false,
                        settled = doc.getBoolean("settled") ?: false,
                        batchId = doc.getString("batchId") ?: "",
                        type = type,
                        originalReferenceId = doc.getString("originalReferenceId") ?: "",
                        isMixed = isMixed,
                        payments = payments
                    )

                    allTransactions.add(transaction)
                }

                // Sort newest first
                allTransactions.sortByDescending { it.date }

                val sales = allTransactions.filter { it.type == "SALE" }
                val refunds = allTransactions.filter { it.type == "REFUND" }

                allSalesWithRefunds.clear()
                val attachedRefundIds = mutableSetOf<String>()
                sales.forEach { sale ->
                    val relatedRefunds =
                        if (sale.referenceId.isBlank()) emptyList()
                        else refunds.filter { it.originalReferenceId == sale.referenceId }

                    relatedRefunds.forEach { attachedRefundIds.add(it.referenceId) }

                    allSalesWithRefunds.add(
                        SaleWithRefunds(
                            sale = sale,
                            refunds = relatedRefunds
                        )
                    )
                }

                // Orphan refunds (parent sale was filtered out, e.g. settled)
                // shown as standalone entries so they appear in "Current Transaction"
                if (showUnsettledAndTodayRefunds) {
                    refunds.filter { it.referenceId !in attachedRefundIds }.forEach { orphan ->
                        allSalesWithRefunds.add(
                            SaleWithRefunds(sale = orphan, refunds = emptyList())
                        )
                    }
                    allSalesWithRefunds.sortByDescending { it.sale.date }
                }

                transactionList.clear()
                transactionList.addAll(applyTransactionFilter(allSalesWithRefunds))
                adapter.notifyDataSetChanged()

                if ((currentTransactionNoBatch || showUnsettledAndTodayRefunds || (filterBatchId != null && !showUnsettledAndTodayRefunds)) && transactionList.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setMessage("NO TRANSACTIONS AT THE MOMENT")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
    }

    private fun applyTransactionFilter(list: List<SaleWithRefunds>): List<SaleWithRefunds> {
        var result = list
        if (dateFromMillis != null) {
            result = result.filter { it.sale.date >= dateFromMillis!! }
        }
        if (dateToMillis != null) {
            result = result.filter { it.sale.date <= dateToMillis!! }
        }
        val last4 = last4Filter?.trim()?.takeIf { it.isNotEmpty() }
        if (last4 != null) {
            result = result.filter { swr ->
                swr.sale.payments.any { p ->
                    val digits = p.last4.trim()
                    digits == last4 || (digits.length >= last4.length && digits.endsWith(last4))
                }
            }
        }
        result = when (typeFilter) {
            "VOID" -> result.filter { it.sale.voided }
            "REFUND" -> result.filter { it.refunds.isNotEmpty() }
            "CASH" -> result.filter { swr ->
                val p = swr.sale.payments
                if (p.isNotEmpty()) p.all { it.paymentType.equals("Cash", true) }
                else swr.sale.paymentType.equals("Cash", true)
            }
            "CREDIT_DEBIT" -> result.filter { swr ->
                val p = swr.sale.payments
                if (p.isNotEmpty()) p.any { it.paymentType.equals("Credit", true) || it.paymentType.equals("Debit", true) }
                else swr.sale.paymentType.equals("Credit", true) || swr.sale.paymentType.equals("Debit", true)
            }
            else -> result
        }
        return result
    }

    private fun showFilterDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_transaction_filter, null)
        val radioAll = view.findViewById<android.widget.RadioButton>(R.id.radioAll)
        val radioVoid = view.findViewById<android.widget.RadioButton>(R.id.radioVoid)
        val radioRefund = view.findViewById<android.widget.RadioButton>(R.id.radioRefund)
        val radioCash = view.findViewById<android.widget.RadioButton>(R.id.radioCash)
        val radioCreditDebit = view.findViewById<android.widget.RadioButton>(R.id.radioCreditDebit)
        val txtDateFrom = view.findViewById<TextView>(R.id.txtDateFrom)
        val txtDateTo = view.findViewById<TextView>(R.id.txtDateTo)
        val edtLast4 = view.findViewById<EditText>(R.id.edtLast4)
        edtLast4.setText(last4Filter ?: "")

        when (typeFilter) {
            "VOID" -> radioVoid.isChecked = true
            "REFUND" -> radioRefund.isChecked = true
            "CASH" -> radioCash.isChecked = true
            "CREDIT_DEBIT" -> radioCreditDebit.isChecked = true
            else -> radioAll.isChecked = true
        }

        val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        fun formatDate(ms: Long?) = if (ms != null) dateFormat.format(ms) else "Any"
        txtDateFrom.text = "From: ${formatDate(dateFromMillis)}"
        txtDateTo.text = "To: ${formatDate(dateToMillis)}"

        txtDateFrom.setOnClickListener {
            val cal = Calendar.getInstance()
            dateFromMillis?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                dateFromMillis = cal.timeInMillis
                txtDateFrom.text = "From: ${formatDate(dateFromMillis)}"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        txtDateTo.setOnClickListener {
            val cal = Calendar.getInstance()
            dateToMillis?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                cal.set(Calendar.MILLISECOND, 999)
                dateToMillis = cal.timeInMillis
                txtDateTo.text = "To: ${formatDate(dateToMillis)}"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<android.widget.Button>(R.id.btnFilterClear).setOnClickListener {
            typeFilter = "ALL"
            dateFromMillis = null
            dateToMillis = null
            last4Filter = null
            dialog.dismiss()
            transactionList.clear()
            transactionList.addAll(applyTransactionFilter(allSalesWithRefunds))
            adapter.notifyDataSetChanged()
        }
        view.findViewById<android.widget.Button>(R.id.btnFilterApply).setOnClickListener {
            val checkedId = view.findViewById<android.widget.RadioGroup>(R.id.radioGroupType).checkedRadioButtonId
            typeFilter = when (checkedId) {
                R.id.radioVoid -> "VOID"
                R.id.radioRefund -> "REFUND"
                R.id.radioCash -> "CASH"
                R.id.radioCreditDebit -> "CREDIT_DEBIT"
                else -> "ALL"
            }
            last4Filter = edtLast4.text.toString().trim().takeIf { it.isNotEmpty() }
            dialog.dismiss()
            transactionList.clear()
            transactionList.addAll(applyTransactionFilter(allSalesWithRefunds))
            adapter.notifyDataSetChanged()
        }
        dialog.show()
    }

    private fun showTransactionOptions(transaction: Transaction) {
        if (transaction.voided) {
            Toast.makeText(this, "This transaction has already been voided.", Toast.LENGTH_SHORT).show()
            return
        }
        val payments = transaction.payments.ifEmpty { null }
        val hasCard = payments?.any { p ->
            !p.paymentType.equals("Cash", true) && !p.paymentType.equals("Debit", true)
        } ?: (!transaction.paymentType.equals("Cash", true) && !transaction.paymentType.equals("Debit", true))
        val allCash = payments?.all { it.paymentType.equals("Cash", true) }
            ?: transaction.paymentType.equals("Cash", true)
        val debitOnly = if (payments != null) {
            payments.any { it.paymentType.equals("Debit", true) } && !hasCard
        } else {
            transaction.paymentType.equals("Debit", true)
        }

        if (transaction.settled) {
            // Cash refunds are local-only (no gateway); card refunds need gateway reference
            AlertDialog.Builder(this)
                .setTitle("Transaction Options")
                .setMessage("Batch already closed. Refund only.")
                .setPositiveButton("Refund") { _, _ ->
                    if (allCash) processCashRefund(transaction) else processRefund(transaction)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (debitOnly) {
            AlertDialog.Builder(this)
                .setTitle("Transaction Options")
                .setMessage("Debit sale can only be refunded.")
                .setPositiveButton("Refund") { _, _ -> processRefund(transaction) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (allCash) {
            AlertDialog.Builder(this)
                .setTitle("Cash Transaction")
                .setMessage("Select an option for this cash payment.")
                .setPositiveButton("Refund") { _, _ -> processCashRefund(transaction) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        // Has card (and possibly cash): allow Refund or Void when batch is open
        AlertDialog.Builder(this)
            .setTitle("Transaction Options")
            .setPositiveButton("Refund") { _, _ -> processRefund(transaction) }
            .setNegativeButton("Void") { _, _ -> processVoid(transaction) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun processVoid(transaction: Transaction) {
        // VOID allowed only when batch is still open (settled == false means batch not closed)
        if (transaction.settled) {
            Toast.makeText(this, "Batch already closed. Cannot void. Use Refund instead.", Toast.LENGTH_LONG).show()
            return
        }
        val payments = transaction.payments.ifEmpty {
            // Fallback: single payment from legacy fields
            listOf(
                TransactionPayment(
                    paymentType = transaction.paymentType,
                    cardBrand = transaction.cardBrand,
                    last4 = transaction.last4,
                    entryType = transaction.entryType,
                    amountInCents = transaction.amountInCents,
                    referenceId = transaction.gatewayReferenceId,
                    clientReferenceId = transaction.clientReferenceId,
                    batchNumber = transaction.batchNumber,
                    transactionNumber = transaction.transactionNumber
                )
            )
        }
        val cashPayments = payments.filter { it.paymentType.equals("Cash", true) }
        val cardPayments = payments.filter { !it.paymentType.equals("Cash", true) }
        val totalCashCents = cashPayments.sumOf { it.amountInCents }
        val totalCashDollars = totalCashCents / 100.0

        if (totalCashCents > 0) {
            AlertDialog.Builder(this)
                .setTitle("Cash return required")
                .setMessage("Return $%.2f in cash to the customer before completing the void.".format(totalCashDollars))
                .setPositiveButton("I have returned the cash") { _, _ ->
                    runVoidSequence(transaction.referenceId, cardPayments)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            runVoidSequence(transaction.referenceId, cardPayments)
        }
    }

    private fun runVoidSequence(txDocId: String, cardPayments: List<TransactionPayment>) {
        if (cardPayments.isEmpty()) {
            doFinalVoidUpdate(txDocId)
            Toast.makeText(this, "VOID APPROVED", Toast.LENGTH_LONG).show()
            return
        }
        voidCardPaymentsSequentially(txDocId, cardPayments, 0)
    }

    private fun voidCardPaymentsSequentially(
        txDocId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        if (index >= cardPayments.size) {
            doFinalVoidUpdate(txDocId)
            runOnUiThread { Toast.makeText(this, "VOID APPROVED", Toast.LENGTH_LONG).show() }
            return
        }
        val payment = cardPayments[index]
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        if (refId.isBlank()) {
            Toast.makeText(this, "Cannot void: no ReferenceId for card payment.", Toast.LENGTH_LONG).show()
            return
        }
        sendVoidRequestForOnePayment(payment, txDocId, cardPayments, index)
    }

    private fun sendVoidRequestForOnePayment(
        payment: TransactionPayment,
        txDocId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        val amountNumber = payment.amountInCents / 100.0
        val json = org.json.JSONObject().apply {
            put("Amount", amountNumber)
            put("PaymentType", payment.paymentType.ifBlank { "Credit" })
            put("ReferenceId", refId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("MerchantNumber", org.json.JSONObject.NULL)
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", org.json.JSONObject().apply { put("Url", "") })
            put("Tpn", "11881706541A")
            put("Authkey", "Qt9N7CxhDs")
            put("SPInProxyTimeout", org.json.JSONObject.NULL)
            put("CustomFields", org.json.JSONObject())
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
        }.toString()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Void")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TX_API", "[VOID] Network error", e)
                runOnUiThread {
                    Toast.makeText(this@TransactionActivity, "VOID Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                Log.d("TX_API", "[VOID] HTTP ${response.code} Response: $responseText")
                val approved = try {
                    val obj = org.json.JSONObject(responseText)
                    obj.optJSONObject("GeneralResponse")?.optString("ResultCode", "") == "0"
                } catch (e: Exception) { false }
                runOnUiThread {
                    if (!response.isSuccessful || !approved) {
                        val reason = try {
                            val gen = org.json.JSONObject(responseText).optJSONObject("GeneralResponse")
                            gen?.optString("DetailedMessage", "")?.ifBlank { gen?.optString("Message", "") } ?: ""
                        } catch (e: Exception) { "" }
                        val msg = if (reason.isNotBlank()) "VOID Declined: $reason" else "VOID Declined"
                        Toast.makeText(this@TransactionActivity, msg, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    voidCardPaymentsSequentially(txDocId, cardPayments, index + 1)
                }
            }
        })
    }

    private fun doFinalVoidUpdate(txDocId: String) {
        val txRef = db.collection("Transactions").document(txDocId)
        txRef.get()
            .addOnSuccessListener { document ->
                if (!document.exists()) return@addOnSuccessListener
                @Suppress("UNCHECKED_CAST")
                val paymentsRaw = document.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val updatedPayments = paymentsRaw.map { p ->
                    val mutable = p.toMutableMap()
                    mutable["status"] = "VOIDED"
                    mutable
                }
                val amount = document.getLong("totalPaidInCents")?.let { it / 100.0 }
                    ?: document.getDouble("totalPaid") ?: document.getDouble("amount") ?: 0.0
                val orderId = document.getString("orderId") ?: ""
                val batchId = document.getString("batchId") ?: ""

                txRef.update("voided", true, "payments", updatedPayments)

                if (orderId.isNotBlank()) {
                    db.collection("Orders").document(orderId).update(
                        mapOf(
                            "status" to "VOIDED",
                            "voidedAt" to Date(),
                            "voidedBy" to currentEmployeeName
                        )
                    ).addOnFailureListener { e ->
                        Log.e("TX_API", "Failed to update Order to VOIDED", e)
                    }
                }
                if (batchId.isNotBlank()) {
                    db.collection("Batches").document(batchId).update(
                        mapOf(
                            "totalSales" to FieldValue.increment(-amount),
                            "netTotal" to FieldValue.increment(-amount),
                            "transactionCount" to FieldValue.increment(-1)
                        )
                    ).addOnFailureListener { e ->
                        Log.e("TX_API", "Failed to update Batch on void", e)
                    }
                }
            }
    }

    // Card refund: send request to Devajoo and, on approval, store refund transaction
    private fun processRefund(transaction: Transaction) {

        val fullAmount = transaction.amountInCents / 100.0

        val input = EditText(this)
        input.hint = "Enter refund amount (Max: $fullAmount)"

        AlertDialog.Builder(this)
            .setTitle("Refund")
            .setView(input)
            .setPositiveButton("Refund") { _, _ ->
                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null || entered <= 0 || entered > fullAmount) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                } else {
                    sendRefundRequest(transaction, entered)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Cash refund: local Firestore-only refund, no terminal call
    private fun processCashRefund(transaction: Transaction) {

        val fullAmount = transaction.amountInCents / 100.0

        val input = EditText(this)
        input.hint = "Enter refund amount (Max: $fullAmount)"

        AlertDialog.Builder(this)
            .setTitle("Cash Refund")
            .setView(input)
            .setPositiveButton("Refund") { _, _ ->
                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null || entered <= 0 || entered > fullAmount) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                } else {
                    createLocalRefund(transaction, entered)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createLocalRefund(original: Transaction, amount: Double) {
        val refundAmountCents = (amount * 100).toLong()

        val refundMap = hashMapOf(
            "referenceId" to UUID.randomUUID().toString(),
            "originalReferenceId" to original.referenceId,
            "amount" to amount,
            "amountInCents" to refundAmountCents,
            "type" to "REFUND",
            "paymentType" to "Cash",
            "cardBrand" to "",
            "last4" to "",
            "entryType" to "",
            "voided" to false,
            "settled" to false,
            "createdAt" to Date(),
            "refundedBy" to currentEmployeeName
        )

        db.collection("Transactions")
            .add(refundMap)
            .addOnSuccessListener {
                // Mirror card refund behavior: update Order totals and status so it shows REFUNDED
                db.collection("Transactions").document(original.referenceId).get()
                    .addOnSuccessListener { saleDoc ->
                        if (!saleDoc.exists()) return@addOnSuccessListener
                        val orderId = saleDoc.getString("orderId") ?: return@addOnSuccessListener
                        val orderRef = db.collection("Orders").document(orderId)
                        orderRef.get()
                            .addOnSuccessListener { orderDoc ->
                                if (!orderDoc.exists()) return@addOnSuccessListener
                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                val newTotalRefunded = currentRefunded + refundAmountCents
                                val updates = mutableMapOf<String, Any>(
                                    "totalRefundedInCents" to newTotalRefunded,
                                    "refundedAt" to Date()
                                )
                                if (newTotalRefunded >= totalInCents) {
                                    updates["status"] = "REFUNDED"
                                }
                                orderRef.update(updates)
                            }
                    }

                Toast.makeText(this, "Cash refund saved", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save refund: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun sendRefundRequest(transaction: Transaction, amount: Double) {
        val refForGateway = transaction.gatewayReferenceId.ifBlank { transaction.clientReferenceId }
        if (refForGateway.isBlank()) {
            Toast.makeText(this, "Cannot refund: no gateway reference for this transaction.", Toast.LENGTH_LONG).show()
            return
        }

        // Debit refunds use the same flow as credit (Credit Return); do not send Debit Return (Z8)
        val returnPaymentType = if (transaction.paymentType.equals("Debit", true)) "Credit" else transaction.paymentType
        val json = org.json.JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", returnPaymentType)
            put("ReferenceId", refForGateway)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("Tpn", "11881706541A")
            put("RegisterId", "134909005")
            put("Authkey", "Qt9N7CxhDs")
        }.toString()

        sendApiRequest(
            url = "https://spinpos.net/v2/Payment/Return",
            json = json,
            type = "REFUND",
            referenceId = transaction.referenceId,
            refundAmount = amount,
            paymentType = transaction.paymentType,
            refundedBy = currentEmployeeName
        )
    }

    private fun sendApiRequest(
        url: String,
        json: String,
        type: String,
        referenceId: String? = null,
        refundAmount: Double? = null,
        paymentType: String? = null,
        refundedBy: String = ""
    ) {
        Log.d("TX_API", "[$type] URL: $url")
        Log.d("TX_API", "[$type] Request: $json")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                Log.e("TX_API", "[$type] Network error", e)
                runOnUiThread {
                    Toast.makeText(
                        this@TransactionActivity,
                        "$type Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""
                Log.d("TX_API", "[$type] HTTP ${response.code}")
                Log.d("TX_API", "[$type] Response: $responseText")

                runOnUiThread {

                    // Same success check as PaymentActivity: GeneralResponse.ResultCode == "0"
                    val approved = try {
                        val obj = org.json.JSONObject(responseText)
                        val resultCode = obj.optJSONObject("GeneralResponse")?.optString("ResultCode", "") ?: ""
                        resultCode == "0"
                    } catch (e: Exception) {
                        false
                    }

                    if (!response.isSuccessful || !approved) {
                        val reason = try {
                            val gen = org.json.JSONObject(responseText).optJSONObject("GeneralResponse")
                            gen?.optString("DetailedMessage", "")?.ifBlank { gen.optString("Message", "") } ?: ""
                        } catch (e: Exception) { "" }
                        val message = if (reason.isNotBlank()) "$type Declined: $reason" else "$type Declined"
                        val hint = if (type == "VOID" && reason.contains("not found", ignoreCase = true))
                            "\n(Use Refund if batch was already closed.)" else ""
                        Log.w("TX_API", "[$type] Declined. Reason: $reason")
                        Toast.makeText(
                            this@TransactionActivity,
                            message + hint,
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    // ================= REFUND (CARD) =================
                    if (type == "REFUND" && referenceId != null && refundAmount != null) {

                        val refundAmountCents = (refundAmount * 100).toLong()
                        val refundMap = hashMapOf(
                            "referenceId" to UUID.randomUUID().toString(),
                            "originalReferenceId" to referenceId,
                            "amount" to refundAmount,
                            "amountInCents" to refundAmountCents,
                            "type" to "REFUND",
                            "paymentType" to (paymentType ?: ""),
                            "cardBrand" to "",
                            "last4" to "",
                            "entryType" to "",
                            "voided" to false,
                            "settled" to false,
                            "createdAt" to Date(),
                            "refundedBy" to refundedBy
                        )

                        db.collection("Transactions").add(refundMap)
                            .addOnSuccessListener {
                                // Update Order so it shows REFUNDED on order screen and detail
                                db.collection("Transactions").document(referenceId).get()
                                    .addOnSuccessListener { saleDoc ->
                                        if (!saleDoc.exists()) return@addOnSuccessListener
                                        val orderId = saleDoc.getString("orderId") ?: return@addOnSuccessListener
                                        val orderRef = db.collection("Orders").document(orderId)
                                        orderRef.get()
                                            .addOnSuccessListener { orderDoc ->
                                                if (!orderDoc.exists()) return@addOnSuccessListener
                                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                                val newTotalRefunded = currentRefunded + refundAmountCents
                                                val updates = mutableMapOf<String, Any>(
                                                    "totalRefundedInCents" to newTotalRefunded,
                                                    "refundedAt" to Date()
                                                )
                                                if (newTotalRefunded >= totalInCents) {
                                                    updates["status"] = "REFUNDED"
                                                }
                                                orderRef.update(updates)
                                            }
                                    }
                            }
                    }

                    Toast.makeText(
                        this@TransactionActivity,
                        "$type APPROVED",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }
}
