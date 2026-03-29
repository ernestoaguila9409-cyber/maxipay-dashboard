package com.ernesto.myapplication

import android.app.DatePickerDialog
import android.content.Intent
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
import android.text.InputType
import com.ernesto.myapplication.data.SaleWithRefunds
import com.google.firebase.firestore.FieldValue
import com.google.firebase.functions.FirebaseFunctions
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.PaymentService
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat

class TransactionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnFilter: ImageButton
    private lateinit var adapter: TransactionAdapter
    private val transactionList = mutableListOf<SaleWithRefunds>()
    private val allSalesWithRefunds = mutableListOf<SaleWithRefunds>()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var paymentService: PaymentService
    private var currentEmployeeName: String = ""

    companion object {
        private const val REQUEST_BT_CONNECT = 1001
    }

    private enum class ReceiptContentType { ORIGINAL, REFUND, VOID }

    private var pendingPrintTransaction: Transaction? = null
    private var pendingPrintContentType: ReceiptContentType? = null

    private var typeFilter: String = "ALL" // ALL, VOID, REFUND, CASH, CREDIT_DEBIT
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null
    private var last4Filter: String? = null // last 4 digits of card to filter by, null = any
    private var filterBatchId: String? = null // when set, show only transactions in this (open) batch
    private var currentTransactionView: Boolean = false
    private var currentTransactionNoBatch: Boolean = false // true when "Current Transaction" but no open batch → show empty
    private var showUnsettledAndTodayRefunds: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        currentTransactionView = intent.getBooleanExtra("CURRENT_TRANSACTION", false)
        showUnsettledAndTodayRefunds = intent.getBooleanExtra("SHOW_UNSETTLED_AND_TODAY_REFUNDS", false)
        filterBatchId = intent.getStringExtra("BATCH_ID")?.takeIf { it.isNotBlank() }
        currentTransactionNoBatch = currentTransactionView && filterBatchId == null && !showUnsettledAndTodayRefunds

        recyclerView = findViewById(R.id.recyclerTransactions)
        val txtTitle = findViewById<TextView>(R.id.txtTitle)
        txtTitle.text = if (currentTransactionView) "Closed Transaction" else "Transactions"
        btnFilter = findViewById(R.id.btnFilter)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(transactionList) { transaction ->
            showTransactionOptions(transaction)
        }

        recyclerView.adapter = adapter
        paymentService = PaymentService(this)
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
                        if ((docType == "SALE" || docType == "CAPTURE") && settled) return@forEach
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

                    if (type == "PRE_AUTH" && !doc.contains("totalPaidInCents")) {
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
                            authCode = p["authCode"]?.toString() ?: "",
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
                        orderId = doc.getString("orderId") ?: "",
                        orderNumber = doc.getLong("orderNumber") ?: 0L,
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
                        voidedBy = doc.getString("voidedBy") ?: "",
                        settled = doc.getBoolean("settled") ?: false,
                        batchId = doc.getString("batchId") ?: "",
                        type = type,
                        originalReferenceId = doc.getString("originalReferenceId") ?: "",
                        isMixed = isMixed,
                        payments = payments,
                        tipAmountInCents = doc.getLong("tipAmountInCents") ?: 0L,
                        tipAdjusted = doc.getBoolean("tipAdjusted") ?: false
                    )

                    allTransactions.add(transaction)
                }

                // Sort newest first
                allTransactions.sortByDescending { it.date }

                val sales = if (currentTransactionView) {
                    allTransactions.filter { it.type == "SALE" || it.type == "CAPTURE" }
                } else {
                    allTransactions.filter { it.type == "SALE" || it.type == "PRE_AUTH" || it.type == "CAPTURE" }
                }
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

                resolveOrderNumbers(allSalesWithRefunds) {
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
    }

    private fun resolveOrderNumbers(list: List<SaleWithRefunds>, onDone: () -> Unit) {
        val needsLookup = list
            .map { it.sale }
            .filter { it.orderNumber == 0L && it.orderId.isNotBlank() }
        if (needsLookup.isEmpty()) { onDone(); return }

        val uniqueOrderIds = needsLookup.map { it.orderId }.distinct()
        val resolved = mutableMapOf<String, Long>()
        var remaining = uniqueOrderIds.size

        for (oid in uniqueOrderIds) {
            db.collection("Orders").document(oid).get()
                .addOnSuccessListener { doc ->
                    val num = doc.getLong("orderNumber") ?: 0L
                    if (num > 0L) resolved[oid] = num
                    if (--remaining <= 0) {
                        for (swr in list) {
                            if (swr.sale.orderNumber == 0L) {
                                resolved[swr.sale.orderId]?.let { swr.sale.orderNumber = it }
                            }
                        }
                        onDone()
                    }
                }
                .addOnFailureListener { if (--remaining <= 0) onDone() }
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
        if (transaction.type == "PRE_AUTH") {
            Toast.makeText(this, "Pre-authorization cannot be voided. Capture the tab first, then void the capture if needed.", Toast.LENGTH_LONG).show()
            return
        }
        if (transaction.voided) {
            val msg = if (transaction.voidedBy.isNotBlank())
                "This transaction has been voided by ${transaction.voidedBy}."
            else
                "This transaction has been voided."
            AlertDialog.Builder(this)
                .setTitle("Voided Transaction")
                .setMessage(msg)
                .setPositiveButton("\uD83E\uDDFE  Receipt") { _, _ -> showReceiptFlow(transaction) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val swr = allSalesWithRefunds.find { it.sale.referenceId == transaction.referenceId }
        val alreadyRefundedCents = swr?.refunds?.sumOf { kotlin.math.abs(it.amountInCents) } ?: 0L
        val remainingCents = transaction.amountInCents - alreadyRefundedCents
        val fullyRefunded = remainingCents <= 0L

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

        if (fullyRefunded) {
            val options = arrayOf("See Order", "\uD83E\uDDFE  Receipt", "Cancel")
            AlertDialog.Builder(this)
                .setTitle("Transaction Options")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "See Order" -> navigateToOrder(transaction.orderId)
                        "\uD83E\uDDFE  Receipt" -> showReceiptFlow(transaction)
                    }
                }
                .show()
            return
        }

        if (transaction.settled) {
            val options = arrayOf("See Order", "Refund", "\uD83E\uDDFE  Receipt", "Cancel")
            AlertDialog.Builder(this)
                .setTitle("Transaction Options")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "See Order" -> navigateToOrder(transaction.orderId)
                        "Refund" -> if (allCash) processCashRefund(transaction, remainingCents) else processRefund(transaction, remainingCents)
                        "\uD83E\uDDFE  Receipt" -> showReceiptFlow(transaction)
                    }
                }
                .show()
            return
        }
        if (debitOnly) {
            val options = arrayOf("See Order", "Refund", "\uD83E\uDDFE  Receipt", "Cancel")
            AlertDialog.Builder(this)
                .setTitle("Transaction Options")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "See Order" -> navigateToOrder(transaction.orderId)
                        "Refund" -> processRefund(transaction, remainingCents)
                        "\uD83E\uDDFE  Receipt" -> showReceiptFlow(transaction)
                    }
                }
                .show()
            return
        }
        if (allCash) {
            val options = arrayOf("See Order", "Refund", "\uD83E\uDDFE  Receipt", "Cancel")
            AlertDialog.Builder(this)
                .setTitle("Cash Transaction")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "See Order" -> navigateToOrder(transaction.orderId)
                        "Refund" -> processCashRefund(transaction, remainingCents)
                        "\uD83E\uDDFE  Receipt" -> showReceiptFlow(transaction)
                    }
                }
                .show()
            return
        }
        val tipLabel = if (transaction.tipAmountInCents > 0L) "Adjust Tip" else "Add Tip"
        val options = arrayOf("See Order", "Refund", "Void", tipLabel, "\uD83E\uDDFE  Receipt", "Cancel")
        AlertDialog.Builder(this)
            .setTitle("Transaction Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "See Order" -> navigateToOrder(transaction.orderId)
                    "Refund" -> processRefund(transaction, remainingCents)
                    "Void" -> processVoid(transaction)
                    tipLabel -> showTipAdjustDialog(transaction)
                    "\uD83E\uDDFE  Receipt" -> showReceiptFlow(transaction)
                }
            }
            .show()
    }

    private fun navigateToOrder(orderId: String) {
        if (orderId.isBlank()) {
            Toast.makeText(this, "No order linked to this transaction", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, OrderDetailActivity::class.java)
        intent.putExtra("orderId", orderId)
        startActivity(intent)
    }

    // ── Receipt Flow ─────────────────────────────────────────────

    private fun showReceiptFlow(transaction: Transaction) {
        if (transaction.orderId.isBlank()) {
            Toast.makeText(this, "No order linked to this transaction.", Toast.LENGTH_SHORT).show()
            return
        }
        val swr = allSalesWithRefunds.find { it.sale.referenceId == transaction.referenceId }
        val hasRefunds = swr?.refunds?.isNotEmpty() == true
        val isVoided = transaction.voided

        if (!hasRefunds && !isVoided) {
            showReceiptDeliveryDialog(transaction, ReceiptContentType.ORIGINAL)
        } else {
            showReceiptTypeDialog(transaction, isVoided, hasRefunds)
        }
    }

    private fun showReceiptTypeDialog(transaction: Transaction, isVoided: Boolean, hasRefunds: Boolean) {
        val options = mutableListOf("\uD83D\uDCC4  Original Transaction")
        if (isVoided) {
            options.add("\uD83D\uDEAB  Void")
        } else if (hasRefunds) {
            options.add("\u21A9\uFE0F  Refund")
        }
        options.add("Cancel")

        AlertDialog.Builder(this)
            .setTitle("Select Receipt Type")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    options[which].contains("Original") -> showReceiptDeliveryDialog(transaction, ReceiptContentType.ORIGINAL)
                    options[which].contains("Void") -> showReceiptDeliveryDialog(transaction, ReceiptContentType.VOID)
                    options[which].contains("Refund") -> showReceiptDeliveryDialog(transaction, ReceiptContentType.REFUND)
                }
            }
            .show()
    }

    private fun showReceiptDeliveryDialog(transaction: Transaction, contentType: ReceiptContentType) {
        val options = arrayOf("\uD83D\uDDA8\uFE0F  Print Receipt", "\u2709\uFE0F  Email Receipt", "Cancel")
        AlertDialog.Builder(this)
            .setTitle("Send Receipt")
            .setItems(options) { _, which ->
                when {
                    options[which].contains("Print") -> handlePrintReceipt(transaction, contentType)
                    options[which].contains("Email") -> handleEmailReceipt(transaction, contentType)
                }
            }
            .show()
    }

    // ── Email Receipt (typed) ────────────────────────────────────

    private fun handleEmailReceipt(transaction: Transaction, contentType: ReceiptContentType) {
        val orderId = transaction.orderId
        if (orderId.isBlank()) {
            Toast.makeText(this, "No order linked to this transaction.", Toast.LENGTH_SHORT).show()
            return
        }
        when (contentType) {
            ReceiptContentType.ORIGINAL -> showTypedEmailDialog(orderId, "sendReceiptEmail", "")
            ReceiptContentType.REFUND -> showTypedEmailDialog(orderId, "sendRefundReceiptEmail", transaction.referenceId)
            ReceiptContentType.VOID -> showTypedEmailDialog(orderId, "sendVoidReceiptEmail", transaction.referenceId)
        }
    }

    private fun showTypedEmailDialog(orderId: String, cloudFunction: String, transactionId: String) {
        val input = EditText(this).apply {
            hint = "Enter email address"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("\u2709\uFE0F  Email Receipt")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Please enter an email", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendTypedReceiptEmail(email, orderId, cloudFunction, transactionId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTypedReceiptEmail(email: String, orderId: String, cloudFunction: String, transactionId: String) {
        val data = hashMapOf<String, Any>(
            "email" to email,
            "orderId" to orderId
        )
        if (transactionId.isNotBlank()) data["transactionId"] = transactionId

        FirebaseFunctions.getInstance()
            .getHttpsCallable(cloudFunction)
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Toast.makeText(this, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to send receipt", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send receipt. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    // ── Print Receipt ────────────────────────────────────────────

    private fun handlePrintReceipt(transaction: Transaction, contentType: ReceiptContentType) {
        if (transaction.orderId.isBlank()) {
            Toast.makeText(this, "No order linked to this transaction.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingPrintTransaction = transaction
                pendingPrintContentType = contentType
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_CONNECT
                )
                return
            }
        }
        executePrint(transaction, contentType)
    }

    private fun executePrint(transaction: Transaction, contentType: ReceiptContentType) {
        Toast.makeText(this, "Preparing receipt\u2026", Toast.LENGTH_SHORT).show()
        when (contentType) {
            ReceiptContentType.ORIGINAL -> printOriginalReceipt(transaction.orderId, transaction.referenceId)
            ReceiptContentType.REFUND -> printRefundVoidReceipt(transaction, "REFUND")
            ReceiptContentType.VOID -> printRefundVoidReceipt(transaction, "VOID")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun printOriginalReceipt(orderId: String, saleTransactionId: String) {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    Toast.makeText(this, "Order not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val rs = ReceiptSettings.load(this)
                        val txId = orderDoc.getString("saleTransactionId") ?: saleTransactionId
                        if (txId.isNotBlank()) {
                            db.collection("Transactions").document(txId).get()
                                .addOnSuccessListener { txDoc ->
                                    val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                    val txStatus = txDoc?.getString("status")
                                    val txVoided = txDoc?.getBoolean("voided") ?: false
                                    EscPosPrinter.print(
                                        this,
                                        buildOriginalSegments(orderDoc, itemsSnap.documents, payments, txStatus, txVoided),
                                        rs
                                    )
                                }
                                .addOnFailureListener {
                                    EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, emptyList()), rs)
                                }
                        } else {
                            EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, emptyList()), rs)
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load order items", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildOriginalSegments(
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        payments: List<Map<String, Any>>,
        transactionStatus: String? = null,
        transactionVoided: Boolean = false
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("RECEIPT", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("", fontSize = rs.fontSizeOrderInfo, centered = true)

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (orderType.isNotBlank()) {
            val label = when (orderType) { "DINE_IN" -> "Dine In"; "TO_GO" -> "To Go"; "BAR_TAB" -> "Bar Tab"; else -> orderType }
            segs += EscPosPrinter.Segment("Type: $label", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (rs.showServerName && empName.isNotBlank()) segs += EscPosPrinter.Segment("Server: $empName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (custName.isNotBlank()) segs += EscPosPrinter.Segment("Customer: $custName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (doc in items) {
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents
            val itemLabel = if (qty > 1) "${qty}x $name" else name
            if (basePriceCents > 0L) {
                segs += EscPosPrinter.Segment(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
            } else {
                segs += EscPosPrinter.Segment(itemLabel, bold = rs.boldItems, fontSize = rs.fontSizeItems)
            }
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            for (mod in mods) {
                val modName = mod["name"]?.toString() ?: continue
                val modAction = mod["action"]?.toString() ?: "ADD"
                val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                val modCents = kotlin.math.round(modPrice * 100).toLong()
                when {
                    modAction == "REMOVE" -> segs += EscPosPrinter.Segment("  NO $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    modCents > 0 -> segs += EscPosPrinter.Segment(formatLine("  + $modName", MoneyUtils.centsToDisplay(modCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    else -> segs += EscPosPrinter.Segment("  + $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                }
            }
        }
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("")

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) { taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        segs += EscPosPrinter.Segment(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)

        @Suppress("UNCHECKED_CAST")
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val groupedDiscounts = DiscountDisplay.groupByName(appliedDiscounts)
        if (groupedDiscounts.isNotEmpty()) {
            for (gd in groupedDiscounts) {
                val label = DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                segs += EscPosPrinter.Segment(formatLine(label, "-${MoneyUtils.centsToDisplay(gd.totalCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
        } else if (discountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }

        for (entry in taxBreakdown) {
            val tName = entry["name"]?.toString() ?: "Tax"
            val aCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val tRate = (entry["rate"] as? Number)?.toDouble()
            val tType = entry["taxType"]?.toString()
            val tLabel = DiscountDisplay.formatTaxLabel(tName, tType, tRate)
            segs += EscPosPrinter.Segment(formatLine(tLabel, MoneyUtils.centsToDisplay(aCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(this, tipAmountInCents)) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        segs += EscPosPrinter.Segment(formatLine("TOTAL", MoneyUtils.centsToDisplay(totalInCents), lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        segs.addAll(
            buildCreditTipReceiptFollowUpSegments(
                this,
                rs,
                subtotalCents,
                taxTotalCents,
                totalInCents,
                tipAmountInCents,
                payments,
                transactionStatus,
                transactionVoided
            )
        )

        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Paid with Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val l4 = p["last4"]?.toString() ?: ""
                val auth = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || l4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (brand.isNotBlank()) append(brand); if (l4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** $l4") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (auth.isNotBlank()) segs += EscPosPrinter.Segment("Auth: $auth", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                receiptLabelForCardEntryType(p["entryType"]?.toString())?.let { method ->
                    segs += EscPosPrinter.Segment(
                        "Payment method: $method",
                        bold = rs.boldFooter,
                        fontSize = rs.fontSizeFooter,
                        centered = true
                    )
                }
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you for dining with us!", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    private fun printRefundVoidReceipt(transaction: Transaction, label: String) {
        if (transaction.orderId.isNotBlank()) {
            if (label == "REFUND") {
                printDetailedRefundReceipt(transaction)
            } else {
                printDetailedVoidReceipt(transaction)
            }
        } else {
            printSimpleReceipt(transaction, label)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun printDetailedRefundReceipt(transaction: Transaction) {
        val orderId = transaction.orderId
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    printSimpleReceipt(transaction, "REFUND")
                    return@addOnSuccessListener
                }
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        db.collection("Transactions")
                            .whereEqualTo("type", "REFUND")
                            .whereEqualTo("originalReferenceId", transaction.referenceId)
                            .get()
                            .addOnSuccessListener { refundSnap ->
                                val segments = buildDetailedRefundSegments(
                                    transaction, orderDoc, itemsSnap.documents, refundSnap.documents
                                )
                                EscPosPrinter.print(this, segments, ReceiptSettings.load(this))
                            }
                            .addOnFailureListener { printSimpleReceipt(transaction, "REFUND") }
                    }
                    .addOnFailureListener { printSimpleReceipt(transaction, "REFUND") }
            }
            .addOnFailureListener { printSimpleReceipt(transaction, "REFUND") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedRefundSegments(
        transaction: Transaction,
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        refundDocs: List<com.google.firebase.firestore.DocumentSnapshot>
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("REFUND RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: transaction.orderNumber
        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = if (transaction.date > 0L)
            SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date(transaction.date))
        else
            SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)

        val refundedByName = refundDocs.firstOrNull()?.getString("refundedBy")?.trim()?.takeIf { it.isNotBlank() }
        if (refundedByName != null) {
            segs += EscPosPrinter.Segment("Refunded by: $refundedByName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        segs += EscPosPrinter.Segment("")

        val itemById = items.associateBy { it.id }
        val itemByName = items.groupBy { (it.getString("name") ?: it.getString("itemName") ?: "").trim() }

        data class RefundedItem(val name: String, val qty: Int, val amountCents: Long, val baseCents: Long, val taxBreakdown: List<Map<String, Any>>)
        val refundedItems = mutableListOf<RefundedItem>()
        var totalRefundCents = 0L

        for (refDoc in refundDocs) {
            val refAmountCents = refDoc.getLong("amountInCents")
                ?: ((refDoc.getDouble("amount") ?: 0.0) * 100).toLong()
            totalRefundCents += refAmountCents
            val lineKey = refDoc.getString("refundedLineKey")?.takeIf { it.isNotBlank() }
            val itemName = refDoc.getString("refundedItemName")?.trim()?.takeIf { it.isNotBlank() }

            val matchedItem = if (lineKey != null) {
                itemById[lineKey]
            } else if (itemName != null) {
                itemByName[itemName]?.firstOrNull()
            } else null

            if (matchedItem != null) {
                val name = matchedItem.getString("name") ?: matchedItem.getString("itemName") ?: "Item"
                val qty = (matchedItem.getLong("qty") ?: matchedItem.getLong("quantity") ?: 1L).toInt()
                val storedTaxBreakdown = matchedItem.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                val lineTotalInCents = matchedItem.getLong("lineTotalInCents") ?: refAmountCents
                refundedItems.add(RefundedItem(name, qty, refAmountCents, lineTotalInCents, storedTaxBreakdown))
            } else if (itemName != null) {
                refundedItems.add(RefundedItem(itemName, 1, refAmountCents, refAmountCents, emptyList()))
            } else {
                for (item in items) {
                    val name = item.getString("name") ?: item.getString("itemName") ?: "Item"
                    val qty = (item.getLong("qty") ?: item.getLong("quantity") ?: 1L).toInt()
                    val lineCents = item.getLong("lineTotalInCents") ?: 0L
                    val storedTaxBreakdown = item.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                    refundedItems.add(RefundedItem(name, qty, lineCents, lineCents, storedTaxBreakdown))
                }
            }
        }

        segs += EscPosPrinter.Segment("Refunded Items:", bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (ri in refundedItems) {
            val label = if (ri.qty > 1) "${ri.name} x${ri.qty}" else ri.name
            segs += EscPosPrinter.Segment(
                formatLine(label, MoneyUtils.centsToDisplay(ri.baseCents), lwi),
                bold = rs.boldItems, fontSize = rs.fontSizeItems
            )
        }
        segs += EscPosPrinter.Segment("")

        val taxGroupMap = mutableMapOf<String, Triple<String, Double, Long>>()
        for (ri in refundedItems) {
            for (tax in ri.taxBreakdown) {
                val taxName = tax["name"]?.toString() ?: continue
                val taxRate = (tax["rate"] as? Number)?.toDouble() ?: 0.0
                val taxAmount = (tax["amountInCents"] as? Number)?.toLong() ?: 0L
                val existing = taxGroupMap[taxName]
                if (existing != null) {
                    taxGroupMap[taxName] = Triple(taxName, existing.second, existing.third + taxAmount)
                } else {
                    taxGroupMap[taxName] = Triple(taxName, taxRate, taxAmount)
                }
            }
        }

        if (taxGroupMap.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val orderTaxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
            val orderSubtotalCents = items.sumOf { it.getLong("lineTotalInCents") ?: 0L }
            val refundedBaseCents = refundedItems.sumOf { it.baseCents }
            for (tax in orderTaxBreakdown) {
                val taxName = tax["name"]?.toString() ?: continue
                val taxRate = (tax["rate"] as? Number)?.toDouble() ?: 0.0
                val orderTaxAmount = (tax["amountInCents"] as? Number)?.toLong() ?: 0L
                val prorated = if (taxRate > 0) {
                    Math.round(refundedBaseCents * taxRate / 100.0)
                } else if (orderSubtotalCents > 0) {
                    Math.round(orderTaxAmount.toDouble() * refundedBaseCents / orderSubtotalCents)
                } else {
                    orderTaxAmount
                }
                if (prorated > 0L) taxGroupMap[taxName] = Triple(taxName, taxRate, prorated)
            }
        }

        if (taxGroupMap.isNotEmpty()) {
            segs += EscPosPrinter.Segment("Taxes Refunded:", bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            segs += EscPosPrinter.Segment("-".repeat(lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            for ((_, group) in taxGroupMap) {
                val (name, rate, totalAmount) = group
                val pctStr = if (rate > 0) {
                    val pct = rate
                    if (pct % 1.0 == 0.0) "${pct.toInt()}%" else String.format(Locale.US, "%.2f%%", pct)
                } else ""
                val taxLabel = if (pctStr.isNotBlank()) "$name ($pctStr)" else name
                segs += EscPosPrinter.Segment(
                    formatLine(taxLabel, MoneyUtils.centsToDisplay(totalAmount), lwt),
                    bold = rs.boldTotals, fontSize = rs.fontSizeTotals
                )
            }
            segs += EscPosPrinter.Segment("")
        }

        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("TOTAL REFUND", "-${MoneyUtils.centsToDisplay(totalRefundCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        val paymentInfo = when {
            transaction.payments.isNotEmpty() -> {
                val p = transaction.payments.first()
                if (p.paymentType.equals("Cash", true)) "Cash"
                else buildString {
                    if (p.cardBrand.isNotBlank()) append(p.cardBrand)
                    if (p.last4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** ${p.last4}") }
                }.ifBlank { p.paymentType }
            }
            transaction.paymentType.isNotBlank() -> {
                if (transaction.paymentType.equals("Cash", true)) "Cash"
                else buildString {
                    if (transaction.cardBrand.isNotBlank()) append(transaction.cardBrand)
                    if (transaction.last4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** ${transaction.last4}") }
                }.ifBlank { transaction.paymentType }
            }
            else -> ""
        }
        if (paymentInfo.isNotBlank()) {
            segs += EscPosPrinter.Segment(paymentInfo, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        }
        val pFirst = transaction.payments.firstOrNull()
        val notCash = pFirst?.let { !it.paymentType.equals("Cash", true) }
            ?: !transaction.paymentType.equals("Cash", true)
        if (notCash) {
            val entryRaw = pFirst?.entryType?.takeIf { it.isNotBlank() } ?: transaction.entryType
            receiptLabelForCardEntryType(entryRaw)?.let { method ->
                segs += EscPosPrinter.Segment(
                    "Payment method: $method",
                    bold = rs.boldFooter,
                    fontSize = rs.fontSizeFooter,
                    centered = true
                )
            }
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)

        return segs
    }

    @Suppress("UNCHECKED_CAST")
    private fun printDetailedVoidReceipt(transaction: Transaction) {
        val orderId = transaction.orderId
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    printSimpleReceipt(transaction, "VOID")
                    return@addOnSuccessListener
                }
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val segments = buildDetailedVoidSegments(
                            transaction, orderDoc, itemsSnap.documents
                        )
                        EscPosPrinter.print(this, segments, ReceiptSettings.load(this))
                    }
                    .addOnFailureListener { printSimpleReceipt(transaction, "VOID") }
            }
            .addOnFailureListener { printSimpleReceipt(transaction, "VOID") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedVoidSegments(
        transaction: Transaction,
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("VOID RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: transaction.orderNumber
        val orderType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val voidedBy = orderDoc.getString("voidedBy")?.trim()?.takeIf { it.isNotBlank() }
        val dateStr = if (transaction.date > 0L)
            SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date(transaction.date))
        else
            SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (orderType.isNotBlank()) {
            val label = when (orderType) { "DINE_IN" -> "Dine In"; "TO_GO" -> "To Go"; "BAR_TAB" -> "Bar Tab"; else -> orderType }
            segs += EscPosPrinter.Segment("Type: $label", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (rs.showServerName && empName.isNotBlank()) segs += EscPosPrinter.Segment("Server: $empName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (custName.isNotBlank()) segs += EscPosPrinter.Segment("Customer: $custName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (voidedBy != null) segs += EscPosPrinter.Segment("Voided by: $voidedBy", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (doc in items) {
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents
            val itemLabel = if (qty > 1) "${qty}x $name" else name
            if (basePriceCents > 0L) {
                segs += EscPosPrinter.Segment(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
            } else {
                segs += EscPosPrinter.Segment(itemLabel, bold = rs.boldItems, fontSize = rs.fontSizeItems)
            }
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            for (mod in mods) {
                val modName = mod["name"]?.toString() ?: continue
                val modAction = mod["action"]?.toString() ?: "ADD"
                val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                val modCents = kotlin.math.round(modPrice * 100).toLong()
                when {
                    modAction == "REMOVE" -> segs += EscPosPrinter.Segment("  NO $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    modCents > 0 -> segs += EscPosPrinter.Segment(formatLine("  + $modName", MoneyUtils.centsToDisplay(modCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    else -> segs += EscPosPrinter.Segment("  + $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                }
            }
        }
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("")

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) { taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        segs += EscPosPrinter.Segment(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)

        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val groupedDiscounts = DiscountDisplay.groupByName(appliedDiscounts)
        if (groupedDiscounts.isNotEmpty()) {
            for (gd in groupedDiscounts) {
                val discLabel = DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                segs += EscPosPrinter.Segment(formatLine(discLabel, "-${MoneyUtils.centsToDisplay(gd.totalCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
        } else if (discountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }

        for (entry in taxBreakdown) {
            val tName = entry["name"]?.toString() ?: "Tax"
            val aCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val tRate = (entry["rate"] as? Number)?.toDouble()
            val tType = entry["taxType"]?.toString()
            val tLabel = DiscountDisplay.formatTaxLabel(tName, tType, tRate)
            segs += EscPosPrinter.Segment(formatLine(tLabel, MoneyUtils.centsToDisplay(aCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(this, tipAmountInCents)) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("VOID TOTAL", "-${MoneyUtils.centsToDisplay(totalInCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        for (p in transaction.payments) {
            val pType = p.paymentType
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Paid with Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                if (p.cardBrand.isNotBlank() || p.last4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (p.cardBrand.isNotBlank()) append(p.cardBrand); if (p.last4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** ${p.last4}") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (p.authCode.isNotBlank()) segs += EscPosPrinter.Segment("Auth: ${p.authCode}", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                receiptLabelForCardEntryType(p.entryType)?.let { method ->
                    segs += EscPosPrinter.Segment(
                        "Payment method: $method",
                        bold = rs.boldFooter,
                        fontSize = rs.fontSizeFooter,
                        centered = true
                    )
                }
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    private fun printSimpleReceipt(transaction: Transaction, label: String) {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("$label RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        if (transaction.orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #${transaction.orderNumber}", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = if (transaction.date > 0L)
            SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date(transaction.date))
        else
            SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        val amountCents = if (label == "REFUND") {
            val swr = allSalesWithRefunds.find { it.sale.referenceId == transaction.referenceId }
            val latestRefund = swr?.refunds?.maxByOrNull { it.date }
            latestRefund?.amountInCents?.let { kotlin.math.abs(it) } ?: transaction.amountInCents
        } else {
            transaction.amountInCents
        }

        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("$label TOTAL", "-${MoneyUtils.centsToDisplay(amountCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        val paymentInfo = when {
            transaction.payments.isNotEmpty() -> {
                val p = transaction.payments.first()
                if (p.paymentType.equals("Cash", true)) "Cash"
                else buildString {
                    if (p.cardBrand.isNotBlank()) append(p.cardBrand)
                    if (p.last4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** ${p.last4}") }
                }.ifBlank { p.paymentType }
            }
            transaction.paymentType.isNotBlank() -> {
                if (transaction.paymentType.equals("Cash", true)) "Cash"
                else buildString {
                    if (transaction.cardBrand.isNotBlank()) append(transaction.cardBrand)
                    if (transaction.last4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** ${transaction.last4}") }
                }.ifBlank { transaction.paymentType }
            }
            else -> ""
        }
        if (paymentInfo.isNotBlank()) {
            segs += EscPosPrinter.Segment(paymentInfo, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        }
        val pFirstSimple = transaction.payments.firstOrNull()
        val notCashSimple = pFirstSimple?.let { !it.paymentType.equals("Cash", true) }
            ?: !transaction.paymentType.equals("Cash", true)
        if (notCashSimple) {
            val entryRaw = pFirstSimple?.entryType?.takeIf { it.isNotBlank() } ?: transaction.entryType
            receiptLabelForCardEntryType(entryRaw)?.let { method ->
                segs += EscPosPrinter.Segment(
                    "Payment method: $method",
                    bold = rs.boldFooter,
                    fontSize = rs.fontSizeFooter,
                    centered = true
                )
            }
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)

        EscPosPrinter.print(this, segs, rs)
    }

    // ===============================
    // TIP ADJUST
    // ===============================

    private fun showTipAdjustDialog(transaction: Transaction) {
        if (transaction.settled) {
            Toast.makeText(this, "Batch already closed. Cannot adjust tip.", Toast.LENGTH_LONG).show()
            return
        }

        val refId = transaction.payments.firstOrNull()?.referenceId?.takeIf { it.isNotBlank() }
            ?: transaction.gatewayReferenceId.takeIf { it.isNotBlank() }
        if (refId.isNullOrBlank()) {
            Toast.makeText(this, "Cannot adjust tip: missing reference ID", Toast.LENGTH_LONG).show()
            return
        }

        val existingTipCents = transaction.tipAmountInCents
        val title = if (existingTipCents > 0L) "Adjust Tip" else "Add Tip"

        val input = EditText(this).apply {
            hint = "Tip amount (e.g. 5.00)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
            if (existingTipCents > 0L) {
                setText(String.format(Locale.US, "%.2f", existingTipCents / 100.0))
                selectAll()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(if (existingTipCents > 0L) "Current tip: ${MoneyUtils.centsToDisplay(existingTipCents)}" else null)
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val tipStr = input.text.toString().trim()
                val tipDollars = tipStr.toDoubleOrNull()
                if (tipDollars == null || tipDollars < 0) {
                    Toast.makeText(this, "Please enter a valid tip amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executeTipAdjust(transaction, refId, tipDollars)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeTipAdjust(transaction: Transaction, refId: String, newTipDollars: Double) {
        val newTipCents = MoneyUtils.dollarsToCents(newTipDollars)
        val existingTipCents = transaction.tipAmountInCents
        val baseAmountCents = transaction.amountInCents - existingTipCents
        val baseAmountDollars = baseAmountCents / 100.0

        Toast.makeText(this, "Processing tip adjustment\u2026", Toast.LENGTH_SHORT).show()

        paymentService.tipAdjust(
            originalAmount = baseAmountDollars,
            tipAmount = newTipDollars,
            referenceId = refId,
            onSuccess = { _ ->
                runOnUiThread {
                    finalizeTipAdjustInFirestore(transaction, newTipCents, existingTipCents, baseAmountCents)
                }
            },
            onFailure = { errorMsg ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Tip Adjust Failed")
                        .setMessage(errorMsg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    private fun finalizeTipAdjustInFirestore(
        transaction: Transaction,
        newTipCents: Long,
        oldTipCents: Long,
        baseAmountCents: Long
    ) {
        val txDocId = transaction.referenceId
        val batchId = transaction.batchId
        val orderId = transaction.orderId
        val deltaTipCents = newTipCents - oldTipCents

        val txRef = db.collection("Transactions").document(txDocId)
        val batchRef = if (batchId.isNotBlank()) db.collection("Batches").document(batchId) else null
        val orderRef = if (orderId.isNotBlank()) db.collection("Orders").document(orderId) else null

        db.runTransaction { firestoreTx ->
            val txSnap = firestoreTx.get(txRef)

            if (batchRef != null) {
                val batchSnap = firestoreTx.get(batchRef)
                val batchClosed = batchSnap.getBoolean("closed") ?: true
                if (batchClosed) throw Exception("Batch is already closed")

                val currentBatchTips = batchSnap.getLong("totalTipsInCents") ?: 0L
                firestoreTx.update(batchRef, mapOf(
                    "totalTipsInCents" to currentBatchTips + deltaTipCents,
                    "netTotalInCents" to FieldValue.increment(deltaTipCents)
                ))
            }

            val newTotalPaidCents = baseAmountCents + newTipCents
            firestoreTx.update(txRef, mapOf(
                "tipAmountInCents" to newTipCents,
                "totalPaidInCents" to newTotalPaidCents,
                "tipAdjusted" to true,
                "tipAdjustedAt" to Timestamp.now()
            ))

            if (orderRef != null) {
                val orderSnap = firestoreTx.get(orderRef)
                val orderTotalCents = orderSnap.getLong("totalInCents") ?: 0L
                val orderTipCents = orderSnap.getLong("tipAmountInCents") ?: 0L
                val newOrderTotalCents = orderTotalCents - orderTipCents + newTipCents

                firestoreTx.update(orderRef, mapOf(
                    "tipAmountInCents" to newTipCents,
                    "totalInCents" to newOrderTotalCents
                ))
            }

            null
        }.addOnSuccessListener {
            Toast.makeText(this, "Tip adjusted successfully", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            Log.e("TIP_ADJUST", "Firestore transaction failed", e)
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Tip was approved by processor but failed to save: ${e.message}\nPlease try again or contact support.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun processVoid(transaction: Transaction) {
        if (transaction.settled) {
            Toast.makeText(this, "Batch already closed. Cannot void. Use Refund instead.", Toast.LENGTH_LONG).show()
            return
        }
        val payments = transaction.payments.ifEmpty {
            // Fallback: single payment from legacy fields (no authCode for legacy)
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
            doFinalVoidUpdate(txDocId) { orderId ->
                runOnUiThread {
                    if (orderId.isNotBlank()) {
                        ReceiptPromptHelper.promptForReceipt(this, ReceiptPromptHelper.ReceiptType.VOID, orderId, txDocId)
                    } else {
                        Toast.makeText(this, "VOID APPROVED", Toast.LENGTH_LONG).show()
                    }
                }
            }
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
            doFinalVoidUpdate(txDocId) { orderId ->
                runOnUiThread {
                    if (orderId.isNotBlank()) {
                        ReceiptPromptHelper.promptForReceipt(this, ReceiptPromptHelper.ReceiptType.VOID, orderId, txDocId)
                    } else {
                        Toast.makeText(this, "VOID APPROVED", Toast.LENGTH_LONG).show()
                    }
                }
            }
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
            if (payment.authCode.isNotBlank()) put("AuthCode", payment.authCode)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(this@TransactionActivity))
            put("RegisterId", TerminalPrefs.getRegisterId(this@TransactionActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@TransactionActivity))
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
        }.toString()
        Log.d("TX_API", "[VOID_REQ] $json")

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

    private fun doFinalVoidUpdate(txDocId: String, onComplete: ((orderId: String) -> Unit)? = null) {
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

                txRef.update(mapOf(
                    "voided" to true,
                    "voidedBy" to currentEmployeeName,
                    "payments" to updatedPayments
                ))
                    .addOnSuccessListener {
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
                        if (orderId.isNotBlank()) {
                            db.collection("Orders").document(orderId).update(
                                mapOf(
                                    "status" to "VOIDED",
                                    "voidedAt" to Date(),
                                    "voidedBy" to currentEmployeeName
                                )
                            ).addOnSuccessListener {
                                onComplete?.invoke(orderId)
                            }.addOnFailureListener { e ->
                                Log.e("TX_API", "Failed to update Order to VOIDED", e)
                                onComplete?.invoke(orderId)
                            }
                        } else {
                            onComplete?.invoke("")
                        }
                    }
            }
    }

    private fun processRefund(transaction: Transaction, remainingCents: Long) {
        RefundDialogHelper.showRefundOptionsDialog(this, remainingCents) { amountCents ->
            sendRefundRequest(transaction, amountCents / 100.0)
        }
    }

    private fun processCashRefund(transaction: Transaction, remainingCents: Long) {
        RefundDialogHelper.showRefundOptionsDialog(this, remainingCents) { amountCents ->
            createLocalRefund(transaction, amountCents / 100.0)
        }
    }

    private fun createLocalRefund(original: Transaction, amount: Double) {
        val refundAmountCents = (amount * 100).toLong()

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { batchSnap ->
                val openBatchId = if (!batchSnap.isEmpty) batchSnap.documents.first().id else ""

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
                    "refundedBy" to currentEmployeeName,
                    "batchId" to openBatchId,
                    "orderId" to original.orderId,
                    "orderNumber" to original.orderNumber
                )

                db.collection("Transactions")
                    .add(refundMap)
                    .addOnSuccessListener {
                        if (openBatchId.isNotBlank()) {
                            db.collection("Batches").document(openBatchId)
                                .update(mapOf(
                                    "totalRefundsInCents" to FieldValue.increment(refundAmountCents),
                                    "netTotalInCents" to FieldValue.increment(-refundAmountCents),
                                    "transactionCount" to FieldValue.increment(1)
                                ))
                        }

                        db.collection("Transactions").document(original.referenceId).get()
                            .addOnSuccessListener { saleDoc ->
                                val orderId = saleDoc.getString("orderId")
                                if (!saleDoc.exists() || orderId.isNullOrBlank()) {
                                    Toast.makeText(this, "Cash refund saved", Toast.LENGTH_LONG).show()
                                    return@addOnSuccessListener
                                }
                                val orderRef = db.collection("Orders").document(orderId)
                                orderRef.get()
                                    .addOnSuccessListener { orderDoc ->
                                        if (!orderDoc.exists()) {
                                            ReceiptPromptHelper.promptForReceipt(this, ReceiptPromptHelper.ReceiptType.REFUND, orderId, original.referenceId)
                                            return@addOnSuccessListener
                                        }
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
                                            .addOnSuccessListener {
                                                ReceiptPromptHelper.promptForReceipt(this, ReceiptPromptHelper.ReceiptType.REFUND, orderId, original.referenceId)
                                            }
                                    }
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Cash refund saved", Toast.LENGTH_LONG).show()
                            }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to save refund: ${it.message}", Toast.LENGTH_LONG).show()
                    }
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
            put("Tpn", TerminalPrefs.getTpn(this@TransactionActivity))
            put("RegisterId", TerminalPrefs.getRegisterId(this@TransactionActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@TransactionActivity))
        }.toString()

        sendApiRequest(
            url = "https://spinpos.net/v2/Payment/Return",
            json = json,
            type = "REFUND",
            referenceId = transaction.referenceId,
            refundAmount = amount,
            paymentType = transaction.paymentType,
            refundedBy = currentEmployeeName,
            orderId = transaction.orderId,
            orderNumber = transaction.orderNumber
        )
    }

    private fun sendApiRequest(
        url: String,
        json: String,
        type: String,
        referenceId: String? = null,
        refundAmount: Double? = null,
        paymentType: String? = null,
        refundedBy: String = "",
        orderId: String = "",
        orderNumber: Long = 0L
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

                        db.collection("Batches")
                            .whereEqualTo("closed", false)
                            .limit(1)
                            .get()
                            .addOnSuccessListener batchLookup@{ batchSnap ->
                                val openBatchId = if (!batchSnap.isEmpty) batchSnap.documents.first().id else ""

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
                                    "refundedBy" to refundedBy,
                                    "batchId" to openBatchId,
                                    "orderId" to orderId,
                                    "orderNumber" to orderNumber
                                )

                                db.collection("Transactions").add(refundMap)
                                    .addOnSuccessListener {
                                        if (openBatchId.isNotBlank()) {
                                            db.collection("Batches").document(openBatchId)
                                                .update(mapOf(
                                                    "totalRefundsInCents" to FieldValue.increment(refundAmountCents),
                                                    "netTotalInCents" to FieldValue.increment(-refundAmountCents),
                                                    "transactionCount" to FieldValue.increment(1)
                                                ))
                                        }

                                        db.collection("Transactions").document(referenceId).get()
                                            .addOnSuccessListener { saleDoc ->
                                                val saleOrderId = saleDoc.getString("orderId")
                                                if (!saleDoc.exists() || saleOrderId.isNullOrBlank()) {
                                                    Toast.makeText(this@TransactionActivity, "REFUND APPROVED", Toast.LENGTH_LONG).show()
                                                    return@addOnSuccessListener
                                                }
                                                val orderRef = db.collection("Orders").document(saleOrderId)
                                                orderRef.get()
                                                    .addOnSuccessListener { orderDoc ->
                                                        if (!orderDoc.exists()) {
                                                            ReceiptPromptHelper.promptForReceipt(this@TransactionActivity, ReceiptPromptHelper.ReceiptType.REFUND, saleOrderId, referenceId)
                                                            return@addOnSuccessListener
                                                        }
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
                                                            .addOnSuccessListener {
                                                                ReceiptPromptHelper.promptForReceipt(this@TransactionActivity, ReceiptPromptHelper.ReceiptType.REFUND, saleOrderId, referenceId)
                                                            }
                                                    }
                                            }
                                            .addOnFailureListener {
                                                Toast.makeText(this@TransactionActivity, "REFUND APPROVED", Toast.LENGTH_LONG).show()
                                            }
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(this@TransactionActivity, "REFUND APPROVED but failed to save: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                        return@runOnUiThread
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

    private fun showEmailReceiptForTransaction(transactionDocId: String) {
        db.collection("Transactions").document(transactionDocId).get()
            .addOnSuccessListener { doc ->
                val orderId = doc.getString("orderId")
                if (orderId.isNullOrBlank()) {
                    Toast.makeText(this, "No order linked to this transaction.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                showEmailReceiptDialog(orderId)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load transaction: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showEmailReceiptDialog(orderId: String) {
        val input = EditText(this).apply {
            hint = "Enter email address"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Email Receipt")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Please enter an email", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendReceiptEmail(email, orderId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendReceiptEmail(email: String, orderId: String) {
        val data = hashMapOf("email" to email, "orderId" to orderId)

        FirebaseFunctions.getInstance()
            .getHttpsCallable("sendReceiptEmail")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Toast.makeText(this, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to send receipt", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send receipt. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_CONNECT) {
            val tx = pendingPrintTransaction
            val ct = pendingPrintContentType
            pendingPrintTransaction = null
            pendingPrintContentType = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && tx != null && ct != null) {
                executePrint(tx, ct)
            } else {
                Toast.makeText(this, "Bluetooth permission required for printing", Toast.LENGTH_LONG).show()
            }
        }
    }
}
