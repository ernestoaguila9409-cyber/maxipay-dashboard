package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.ernesto.myapplication.data.TransactionPayment
import org.json.JSONObject
import java.io.IOException
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale
import android.util.Log
import android.widget.EditText
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.OrderEngine
import com.ernesto.myapplication.engine.PaymentService
import android.graphics.Typeface
import com.google.firebase.Timestamp
import com.google.firebase.functions.FirebaseFunctions

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val orderEngine = OrderEngine(FirebaseFirestore.getInstance())
    private lateinit var paymentService: PaymentService
    private lateinit var txtHeaderOrderNumber: TextView
    private lateinit var txtHeaderEmployee: TextView
    private lateinit var txtHeaderCustomer: TextView
    private lateinit var txtHeaderTime: TextView
    private lateinit var txtRefundHistory: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView
    private lateinit var btnCheckout: MaterialButton
    private lateinit var bottomActions: View
    private lateinit var btnVoid: MaterialButton
    private lateinit var btnRefund: MaterialButton
    private lateinit var btnReceipt: MaterialButton
    private lateinit var btnTipAdjust: MaterialButton
    private lateinit var txtAddCustomer: TextView
    private lateinit var txtOrderType: TextView
    private lateinit var orderSummaryContainer: LinearLayout
    private lateinit var txtSubtotal: TextView
    private lateinit var txtOriginalTotal: TextView
    private lateinit var refundedSummaryRow: LinearLayout
    private lateinit var txtRefundedAmount: TextView
    private lateinit var txtRemainingTotal: TextView
    private lateinit var taxBreakdownContainer: LinearLayout
    private lateinit var tipRow: LinearLayout
    private lateinit var txtTipLabel: TextView
    private lateinit var txtTipAmount: TextView
    private lateinit var partialRefundContainer: LinearLayout
    private lateinit var txtPartialRefundAmount: TextView
    private lateinit var txtPartialRefundBy: TextView
    private lateinit var txtPartialRefundDate: TextView

    private lateinit var adapter: OrderItemsAdapter
    private val listItems = mutableListOf<OrderListItem>()
    private val itemDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var orderId: String
    private var currentBatchId: String? = null
    private var orderType: String = ""
    private var saleTransactionId: String? = null

    private enum class ReceiptContentType { ORIGINAL, REFUND, VOID }
    private var pendingPrintContentType: ReceiptContentType? = null

    companion object {
        private const val REQUEST_BT_CONNECT = 1001
    }

    private val tableSelectLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val tableId = data.getStringExtra("tableId") ?: ""
                val tableName = data.getStringExtra("tableName") ?: ""
                val sectionId = data.getStringExtra("sectionId") ?: ""
                val sectionName = data.getStringExtra("sectionName") ?: ""
                val guestCount = data.getIntExtra("guestCount", 0)
                val guestNames = data.getStringArrayListExtra("guestNames") ?: arrayListOf()

                switchToDineIn(tableId, tableName, sectionId, sectionName, guestCount, guestNames)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        orderId = intent.getStringExtra("orderId") ?: ""

        if (orderId.isBlank()) {
            Toast.makeText(this, "Invalid order ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        txtHeaderOrderNumber = findViewById(R.id.txtHeaderOrderNumber)
        txtHeaderEmployee = findViewById(R.id.txtHeaderEmployee)
        txtHeaderCustomer = findViewById(R.id.txtHeaderCustomer)
        txtHeaderTime = findViewById(R.id.txtHeaderTime)
        txtRefundHistory = findViewById(R.id.txtRefundHistory)
        recycler = findViewById(R.id.recyclerOrderItems)
        txtEmptyItems = findViewById(R.id.txtEmptyItems)
        btnCheckout = findViewById(R.id.btnCheckout)
        bottomActions = findViewById(R.id.bottomActions)
        btnVoid = findViewById(R.id.btnVoid)
        btnRefund = findViewById(R.id.btnRefund)
        btnReceipt = findViewById(R.id.btnReceipt)
        btnTipAdjust = findViewById(R.id.btnTipAdjust)
        txtAddCustomer = findViewById(R.id.txtAddCustomer)
        paymentService = PaymentService(this)
        txtOrderType = findViewById(R.id.txtOrderType)
        orderSummaryContainer = findViewById(R.id.orderSummaryContainer)
        txtSubtotal = findViewById(R.id.txtSubtotal)
        txtOriginalTotal = findViewById(R.id.txtOriginalTotal)
        refundedSummaryRow = findViewById(R.id.refundedSummaryRow)
        txtRefundedAmount = findViewById(R.id.txtRefundedAmount)
        txtRemainingTotal = findViewById(R.id.txtRemainingTotal)
        taxBreakdownContainer = findViewById(R.id.taxBreakdownContainer)
        tipRow = findViewById(R.id.tipRow)
        txtTipLabel = findViewById(R.id.txtTipLabel)
        txtTipAmount = findViewById(R.id.txtTipAmount)
        partialRefundContainer = findViewById(R.id.partialRefundContainer)
        txtPartialRefundAmount = findViewById(R.id.txtPartialRefundAmount)
        txtPartialRefundBy = findViewById(R.id.txtPartialRefundBy)
        txtPartialRefundDate = findViewById(R.id.txtPartialRefundDate)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = OrderItemsAdapter(listItems) { itemDoc -> onOrderItemClick(itemDoc) }
        recycler.adapter = adapter

        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(0, 1, 0, 1)
            }
        })

        loadHeader()
        loadItems()
    }

    override fun onResume() {
        super.onResume()
        loadHeader()
    }

    // ===============================
    // LOAD HEADER
    // ===============================

    private fun loadHeader() {

        // Reset UI first
        btnCheckout.visibility = View.GONE
        bottomActions.visibility = View.GONE
        btnVoid.visibility = View.GONE
        btnRefund.visibility = View.GONE
        btnReceipt.visibility = View.GONE
        btnTipAdjust.visibility = View.GONE

        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) return@addOnSuccessListener

                val status = doc.getString("status") ?: ""
                val employee = doc.getString("employeeName") ?: "Unknown"
                val customerName = doc.getString("customerName") ?: ""
                val orderNumber = doc.getLong("orderNumber") ?: 0L

                if (orderNumber > 0L) {
                    txtHeaderOrderNumber.text = "Order #$orderNumber"
                    txtHeaderOrderNumber.visibility = View.VISIBLE
                } else {
                    txtHeaderOrderNumber.visibility = View.GONE
                }

                if (status == "VOIDED") {
                    val voidedBy = doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: "—"
                    txtHeaderEmployee.text = "Voided by: $voidedBy"
                    val voidedAt = doc.getTimestamp("voidedAt")
                    if (voidedAt != null) {
                        val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                        txtHeaderTime.text = format.format(voidedAt.toDate())
                    } else {
                        txtHeaderTime.text = ""
                    }
                } else {
                    txtHeaderEmployee.text = "Employee: $employee"
                    val createdAt = doc.getTimestamp("createdAt")
                    if (createdAt != null) {
                        val date = createdAt.toDate()
                        val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                        txtHeaderTime.text = format.format(date)
                    } else {
                        txtHeaderTime.text = ""
                    }
                }

                if (customerName.isNotBlank()) {
                    txtHeaderCustomer.text = "Customer: $customerName"
                    txtHeaderCustomer.visibility = View.VISIBLE
                    txtAddCustomer.visibility = View.GONE
                } else {
                    txtHeaderCustomer.visibility = View.GONE
                    if (status == "OPEN") {
                        txtAddCustomer.visibility = View.VISIBLE
                        txtAddCustomer.setOnClickListener { showAddCustomerDialog() }
                    } else {
                        txtAddCustomer.visibility = View.GONE
                    }
                }

                currentBatchId = doc.getString("batchId")
                orderType = doc.getString("orderType") ?: ""
                saleTransactionId = doc.getString("saleTransactionId") ?: doc.getString("transactionId")

                displayOrderSummary(doc, status)
                updateOrderTypeBadge(status)

                if (status == "OPEN") {
                    btnCheckout.visibility = View.VISIBLE
                    btnCheckout.setOnClickListener {
                        val i = Intent(this, MenuActivity::class.java)
                        i.putExtra("ORDER_ID", orderId)
                        startActivity(i)
                    }
                }

                if (status == "VOIDED") {
                    bottomActions.visibility = View.VISIBLE
                    btnVoid.visibility = View.GONE
                    btnRefund.visibility = View.GONE
                    btnReceipt.visibility = View.VISIBLE
                    btnReceipt.setOnClickListener { showOrderReceiptFlow() }
                    txtRefundHistory.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val totalInCents = doc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val isFullyRefunded = totalRefundedInCents >= totalInCents && totalInCents > 0L

                loadRefundHistory(saleTransactionId) { totalFromRefunds, refundedLineKeys, refundedNameAmountKeys, lineKeyToEmployee, nameAmountToEmployee, lineKeyToDate, nameAmountToDate, wholeOrderRefundEmployee, wholeOrderRefundDate ->
                    val fullyRefunded = isFullyRefunded || (totalFromRefunds >= totalInCents && totalInCents > 0L)
                    txtRefundHistory.visibility = View.GONE

                    val effectiveWholeEmployee = if (fullyRefunded) wholeOrderRefundEmployee else null
                    val effectiveWholeDate = if (fullyRefunded) wholeOrderRefundDate else null
                    adapter.setRefundedKeys(refundedLineKeys, refundedNameAmountKeys, lineKeyToEmployee, nameAmountToEmployee, lineKeyToDate, nameAmountToDate, effectiveWholeEmployee, effectiveWholeDate)

                    if (!fullyRefunded && totalFromRefunds > 0 && wholeOrderRefundEmployee != null) {
                        partialRefundContainer.visibility = View.VISIBLE
                        txtPartialRefundAmount.text = MoneyUtils.centsToDisplay(totalFromRefunds)
                        if (!wholeOrderRefundEmployee.isNullOrBlank() && wholeOrderRefundEmployee != "—") {
                            txtPartialRefundBy.text = "By: $wholeOrderRefundEmployee"
                            txtPartialRefundBy.visibility = View.VISIBLE
                        } else {
                            txtPartialRefundBy.visibility = View.GONE
                        }
                        if (!wholeOrderRefundDate.isNullOrBlank()) {
                            txtPartialRefundDate.text = wholeOrderRefundDate
                            txtPartialRefundDate.visibility = View.VISIBLE
                        } else {
                            txtPartialRefundDate.visibility = View.GONE
                        }
                    } else {
                        partialRefundContainer.visibility = View.GONE
                    }
                    if (status == "REFUNDED" || fullyRefunded) {
                        bottomActions.visibility = View.VISIBLE
                        btnVoid.visibility = View.GONE
                        btnRefund.visibility = View.GONE
                        btnReceipt.visibility = View.VISIBLE
                        btnReceipt.setOnClickListener { showOrderReceiptFlow() }
                        return@loadRefundHistory
                    }
                    if (status == "CLOSED") {
                        bottomActions.visibility = View.VISIBLE
                        if (!fullyRefunded) {
                            btnRefund.visibility = View.VISIBLE
                            btnRefund.setOnClickListener { confirmRefund() }
                        } else {
                            btnRefund.visibility = View.GONE
                        }
                        btnReceipt.visibility = View.VISIBLE
                        btnReceipt.setOnClickListener { showOrderReceiptFlow() }
                        resolveBatchAndShowVoid(saleTransactionId)
                        resolveBatchAndShowTipAdjust(saleTransactionId)
                    }
                }
            }
    }

    private fun showAddCustomerDialog() {
        CustomerDialogHelper.showCustomerDialog(this) { info ->
            val updates = hashMapOf<String, Any>(
                "customerName" to info.name,
                "customerPhone" to info.phone,
                "customerEmail" to info.email,
                "updatedAt" to Date()
            )
            if (info.id != null) {
                updates["customerId"] = info.id
            }

            db.collection("Orders").document(orderId)
                .update(updates as Map<String, Any>)
                .addOnSuccessListener {
                    txtHeaderCustomer.text = "Customer: ${info.name}"
                    txtHeaderCustomer.visibility = View.VISIBLE
                    txtAddCustomer.visibility = View.GONE
                    saveCustomerToFirestoreIfNew(info)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun saveCustomerToFirestoreIfNew(info: CustomerDialogHelper.CustomerInfo) {
        if (info.id != null) return

        CustomerDuplicateChecker.checkExists(db, info.name, info.email, info.phone) { exists ->
            if (exists) {
                resolveCustomerId(info.name, info.email)
            } else {
                val customer = hashMapOf<String, Any>(
                    "name" to info.name,
                    "phone" to info.phone,
                    "email" to info.email
                )
                db.collection("Customers")
                    .add(customer)
                    .addOnSuccessListener { docRef ->
                        db.collection("Orders").document(orderId)
                            .update("customerId", docRef.id)
                    }
            }
        }
    }

    private fun resolveCustomerId(name: String, email: String) {
        db.collection("Customers")
            .whereEqualTo("name", name)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val doc = snap.documents.firstOrNull()
                if (doc != null) {
                    db.collection("Orders").document(orderId)
                        .update("customerId", doc.id)
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun displayOrderSummary(orderDoc: DocumentSnapshot, status: String = "") {
        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val isVoided = status == "VOIDED"

        if (totalInCents <= 0L) {
            orderSummaryContainer.visibility = View.GONE
            recomputeAndRefreshSummary()
            return
        }

        val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
        renderSummary(
            totalInCents,
            taxBreakdown,
            tipAmountInCents,
            discountInCents,
            appliedDiscounts,
            totalRefundedInCents,
            isVoided
        )
    }

    private fun recomputeAndRefreshSummary() {
        orderEngine.recomputeOrderTotals(
            orderId = orderId,
            onSuccess = {
                db.collection("Orders").document(orderId).get()
                    .addOnSuccessListener { doc ->
                        if (doc.exists()) {
                            val totalInCents = doc.getLong("totalInCents") ?: 0L
                            @Suppress("UNCHECKED_CAST")
                            val taxBreakdown = doc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                            val tipAmountInCents = doc.getLong("tipAmountInCents") ?: 0L
                            val discountInCents = doc.getLong("discountInCents") ?: 0L
                            val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                            @Suppress("UNCHECKED_CAST")
                            val appliedDiscounts = doc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
                            if (totalInCents > 0L) {
                                renderSummary(
                                    totalInCents,
                                    taxBreakdown,
                                    tipAmountInCents,
                                    discountInCents,
                                    appliedDiscounts,
                                    totalRefundedInCents
                                )
                            }
                        }
                    }
            },
            onFailure = { }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderSummary(
        totalInCents: Long,
        taxBreakdown: List<Map<String, Any>>,
        tipAmountInCents: Long = 0L,
        discountInCents: Long = 0L,
        appliedDiscounts: List<Map<String, Any>> = emptyList(),
        totalRefundedInCents: Long = 0L,
        isVoided: Boolean = false
    ) {
        var taxTotalCents = 0L
        taxBreakdownContainer.removeAllViews()

        val summaryColor = 0xFF555555.toInt()
        val dividerColor = 0xFFE0E0E0.toInt()

        val grouped = DiscountDisplay.groupByName(appliedDiscounts)
        val hasDiscounts = discountInCents > 0L || grouped.isNotEmpty()

        if (hasDiscounts) {
            addOrderSummarySectionHeader(taxBreakdownContainer, "Discounts", topMarginDp = 4, textColor = summaryColor)
            addOrderSummaryDivider(taxBreakdownContainer, dividerColor)
            if (grouped.isNotEmpty()) {
                for (gd in grouped) {
                    addOrderSummaryAmountRow(
                        taxBreakdownContainer,
                        "• ${DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)}",
                        "-${MoneyUtils.centsToDisplay(gd.totalCents)}",
                        summaryColor
                    )
                }
            } else if (discountInCents > 0L) {
                addOrderSummaryAmountRow(
                    taxBreakdownContainer,
                    "• Discount",
                    "-${MoneyUtils.centsToDisplay(discountInCents)}",
                    summaryColor
                )
            }
        }

        if (taxBreakdown.isNotEmpty()) {
            val headerTop = if (hasDiscounts) 12 else 4
            addOrderSummarySectionHeader(taxBreakdownContainer, "Taxes", topMarginDp = headerTop, textColor = summaryColor)
            addOrderSummaryDivider(taxBreakdownContainer, dividerColor)
        }

        for (entry in taxBreakdown) {
            val name = entry["name"]?.toString() ?: "Tax"
            val amountCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val rate = (entry["rate"] as? Number)?.toDouble()
            val taxType = entry["taxType"]?.toString() ?: ""
            taxTotalCents += amountCents

            val label = if (taxType == "PERCENTAGE" && rate != null && rate > 0) {
                val pctStr = if (rate % 1.0 == 0.0) rate.toInt().toString()
                else String.format(java.util.Locale.US, "%.2f", rate)
                "$name ($pctStr%)"
            } else {
                name
            }

            addOrderSummaryAmountRow(
                taxBreakdownContainer,
                label,
                MoneyUtils.centsToDisplay(amountCents),
                summaryColor
            )
        }

        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents
        txtSubtotal.text = MoneyUtils.centsToDisplay(subtotalCents)

        if (tipAmountInCents > 0L) {
            tipRow.visibility = View.VISIBLE
            if (subtotalCents > 0L) {
                val tipPct = tipAmountInCents.toDouble() / subtotalCents * 100.0
                val pctStr = if (tipPct % 1.0 == 0.0) tipPct.toInt().toString()
                else String.format(java.util.Locale.US, "%.1f", tipPct)
                txtTipLabel.text = "Tip ($pctStr%)"
            } else {
                txtTipLabel.text = "Tip"
            }
            txtTipAmount.text = MoneyUtils.centsToDisplay(tipAmountInCents)
        } else {
            tipRow.visibility = View.GONE
        }

        val originalCents = totalInCents
        txtOriginalTotal.text = MoneyUtils.centsToDisplay(originalCents)

        if (isVoided) {
            refundedSummaryRow.visibility = View.VISIBLE
            val refundedLabel = refundedSummaryRow.getChildAt(0) as? TextView
            refundedLabel?.text = "Voided"
            txtRefundedAmount.text = "-${MoneyUtils.centsToDisplay(originalCents)}"
            txtRemainingTotal.text = MoneyUtils.centsToDisplay(0L)
        } else {
            val refundedCents = totalRefundedInCents
            val remainingCents = (originalCents - refundedCents).coerceAtLeast(0L)
            val refundedLabel = refundedSummaryRow.getChildAt(0) as? TextView
            refundedLabel?.text = "Refunded"
            txtRefundedAmount.text = MoneyUtils.centsToDisplay(refundedCents)
            refundedSummaryRow.visibility = if (refundedCents > 0L) View.VISIBLE else View.GONE
            txtRemainingTotal.text = MoneyUtils.centsToDisplay(remainingCents)
        }
        orderSummaryContainer.visibility = View.VISIBLE
    }

    private fun orderSummaryDip(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun addOrderSummarySectionHeader(
        container: LinearLayout,
        title: String,
        topMarginDp: Int,
        textColor: Int
    ) {
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = orderSummaryDip(topMarginDp)
                bottomMargin = orderSummaryDip(2)
            }
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        })
    }

    private fun addOrderSummaryDivider(container: LinearLayout, lineColor: Int) {
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                orderSummaryDip(1)
            ).apply {
                topMargin = orderSummaryDip(2)
                bottomMargin = orderSummaryDip(6)
            }
            setBackgroundColor(lineColor)
        })
    }

    private fun addOrderSummaryAmountRow(
        container: LinearLayout,
        leftLabel: String,
        rightAmount: String,
        textColor: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = orderSummaryDip(4) }
        }
        row.addView(TextView(this@OrderDetailActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = leftLabel
            textSize = 14f
            setTextColor(textColor)
        })
        row.addView(TextView(this@OrderDetailActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = rightAmount
            textSize = 14f
            setTextColor(textColor)
        })
        container.addView(row)
    }

    private var refundHistoryLines: String = ""

    /**
     * For CLOSED orders: load transaction to check if all-cash (then no Void), then resolve batchId
     * and show VOID button only when batch.closed == false and payment was not all cash.
     */
    private fun resolveBatchAndShowVoid(saleTransactionId: String?) {
        if (saleTransactionId.isNullOrBlank()) {
            btnVoid.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(saleTransactionId).get()
            .addOnSuccessListener { txDoc ->
                if (isTransactionAllCash(txDoc)) {
                    btnVoid.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                    ?: currentBatchId?.takeIf { it.isNotBlank() }
                if (!batchId.isNullOrBlank()) {
                    currentBatchId = batchId
                    loadBatchAndUpdateVoidButton(batchId)
                } else {
                    btnVoid.visibility = View.GONE
                }
            }
            .addOnFailureListener { btnVoid.visibility = View.GONE }
    }

    private fun isTransactionAllCash(txDoc: DocumentSnapshot): Boolean {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        if (payments.isEmpty()) {
            val legacyType = txDoc.getString("paymentType")?.takeIf { it.isNotBlank() } ?: ""
            return legacyType.equals("Cash", true)
        }
        return payments.all { p ->
            (p as? Map<*, *>)?.get("paymentType")?.toString()?.equals("Cash", true) == true
        }
    }

    private fun loadBatchAndUpdateVoidButton(batchId: String) {
        db.collection("Batches").document(batchId).get()
            .addOnSuccessListener { batchDoc ->
                val batchClosed = batchDoc.getBoolean("closed") ?: true
                if (!batchClosed) {
                    btnVoid.visibility = View.VISIBLE
                    btnVoid.setOnClickListener { confirmVoid() }
                } else {
                    btnVoid.visibility = View.GONE
                }
            }
            .addOnFailureListener { btnVoid.visibility = View.GONE }
    }

    // ===============================
    // TIP ADJUST
    // ===============================

    private fun resolveBatchAndShowTipAdjust(saleTransactionId: String?) {
        if (saleTransactionId.isNullOrBlank()) {
            btnTipAdjust.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(saleTransactionId).get()
            .addOnSuccessListener { txDoc ->
                if (!txDoc.exists()) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }
                if (isTransactionAllCash(txDoc)) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }

                val refId = getReferenceIdFromTransaction(txDoc)
                if (refId.isBlank()) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }

                val batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                    ?: currentBatchId?.takeIf { it.isNotBlank() }
                if (batchId.isNullOrBlank()) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }

                db.collection("Batches").document(batchId).get()
                    .addOnSuccessListener { batchDoc ->
                        val batchClosed = batchDoc.getBoolean("closed") ?: true
                        if (batchClosed) {
                            btnTipAdjust.visibility = View.GONE
                            return@addOnSuccessListener
                        }

                        val existingTipCents = txDoc.getLong("tipAmountInCents") ?: 0L
                        btnTipAdjust.text = if (existingTipCents > 0L) "Adjust Tip" else "Add Tip"
                        btnTipAdjust.visibility = View.VISIBLE
                        btnTipAdjust.setOnClickListener {
                            showTipAdjustDialog(saleTransactionId, txDoc, batchId)
                        }
                    }
                    .addOnFailureListener { btnTipAdjust.visibility = View.GONE }
            }
            .addOnFailureListener { btnTipAdjust.visibility = View.GONE }
    }

    private fun showTipAdjustDialog(saleTransactionId: String, txDoc: DocumentSnapshot, batchId: String) {
        val existingTipCents = txDoc.getLong("tipAmountInCents") ?: 0L
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
                executeTipAdjust(saleTransactionId, txDoc, batchId, tipDollars)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeTipAdjust(saleTransactionId: String, txDoc: DocumentSnapshot, batchId: String, newTipDollars: Double) {
        val newTipCents = MoneyUtils.dollarsToCents(newTipDollars)
        val refId = getReferenceIdFromTransaction(txDoc)
        if (refId.isBlank()) {
            Toast.makeText(this, "Cannot adjust tip: missing reference ID", Toast.LENGTH_LONG).show()
            return
        }

        val totalPaidCents = txDoc.getLong("totalPaidInCents") ?: 0L
        val existingTipCents = txDoc.getLong("tipAmountInCents") ?: 0L
        val baseAmountCents = totalPaidCents - existingTipCents
        val baseAmountDollars = baseAmountCents / 100.0

        Toast.makeText(this, "Processing tip adjustment\u2026", Toast.LENGTH_SHORT).show()

        paymentService.tipAdjust(
            originalAmount = baseAmountDollars,
            tipAmount = newTipDollars,
            referenceId = refId,
            onSuccess = { _ ->
                runOnUiThread {
                    finalizeTipAdjustInFirestore(saleTransactionId, batchId, newTipCents, existingTipCents, baseAmountCents)
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
        saleTransactionId: String,
        batchId: String,
        newTipCents: Long,
        oldTipCents: Long,
        baseAmountCents: Long
    ) {
        val txRef = db.collection("Transactions").document(saleTransactionId)
        val batchRef = db.collection("Batches").document(batchId)
        val orderRef = db.collection("Orders").document(orderId)
        val deltaTipCents = newTipCents - oldTipCents

        db.runTransaction { transaction ->
            val txSnap = transaction.get(txRef)
            val batchSnap = transaction.get(batchRef)
            val orderSnap = transaction.get(orderRef)

            val batchClosed = batchSnap.getBoolean("closed") ?: true
            if (batchClosed) throw Exception("Batch is already closed")

            val newTotalPaidCents = baseAmountCents + newTipCents

            transaction.update(txRef, mapOf(
                "tipAmountInCents" to newTipCents,
                "totalPaidInCents" to newTotalPaidCents,
                "tipAdjusted" to true,
                "tipAdjustedAt" to Timestamp.now()
            ))

            val orderTotalCents = orderSnap.getLong("totalInCents") ?: 0L
            val orderTipCents = orderSnap.getLong("tipAmountInCents") ?: 0L
            val newOrderTipCents = newTipCents
            val newOrderTotalCents = orderTotalCents - orderTipCents + newTipCents

            transaction.update(orderRef, mapOf(
                "tipAmountInCents" to newOrderTipCents,
                "totalInCents" to newOrderTotalCents
            ))

            val currentBatchTips = batchSnap.getLong("totalTipsInCents") ?: 0L
            transaction.update(batchRef, mapOf(
                "totalTipsInCents" to currentBatchTips + deltaTipCents,
                "netTotalInCents" to FieldValue.increment(deltaTipCents)
            ))

            null
        }.addOnSuccessListener {
            Toast.makeText(this, "Tip adjusted successfully", Toast.LENGTH_SHORT).show()
            loadHeader()
            loadItems()
        }.addOnFailureListener { e ->
            Log.e("TIP_ADJUST", "Firestore transaction failed", e)
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Tip was approved by processor but failed to save: ${e.message}\nPlease try again or contact support.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun getPaymentTypeFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return (first?.get("paymentType")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("paymentType")?.takeIf { it.isNotBlank() }).orEmpty()
    }

    private fun getReferenceIdFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return first?.get("referenceId")?.toString()?.takeIf { it.isNotBlank() }
            ?: first?.get("terminalReference")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("referenceId")?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun loadRefundHistory(saleTransactionId: String?, onLoaded: (totalRefundedCents: Long, refundedLineKeys: Set<String>, refundedNameAmountKeys: Set<String>, refundedLineKeyToEmployee: Map<String, String>, refundedNameAmountToEmployee: Map<String, String>, refundedLineKeyToDate: Map<String, String>, refundedNameAmountToDate: Map<String, String>, wholeOrderRefundEmployee: String?, wholeOrderRefundDate: String?) -> Unit) {
        refundHistoryLines = ""
        if (saleTransactionId.isNullOrBlank()) {
            onLoaded(0L, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, null)
            return
        }
        val dateFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
        db.collection("Transactions")
            .whereEqualTo("type", "REFUND")
            .whereEqualTo("originalReferenceId", saleTransactionId)
            .get()
            .addOnSuccessListener { snap ->
                var totalCents = 0L
                val refundedLineKeys = mutableSetOf<String>()
                val refundedNameAmountKeys = mutableSetOf<String>()
                val refundedLineKeyToEmployee = mutableMapOf<String, String>()
                val refundedNameAmountToEmployee = mutableMapOf<String, String>()
                val refundedLineKeyToDate = mutableMapOf<String, String>()
                val refundedNameAmountToDate = mutableMapOf<String, String>()
                var wholeOrderRefundEmployee: String? = null
                var wholeOrderRefundDate: String? = null
                for (refundDoc in snap.documents) {
                    val amountCents = refundDoc.getLong("amountInCents")
                        ?: ((refundDoc.getDouble("amount") ?: 0.0) * 100).toLong()
                    totalCents += amountCents
                    val employee = refundDoc.getString("refundedBy")?.trim()?.takeIf { it.isNotBlank() } ?: "—"
                    val createdAt = refundDoc.getTimestamp("createdAt")?.toDate()
                    val dateStr = if (createdAt != null) dateFormat.format(createdAt) else ""
                    val hasLineKey = refundDoc.getString("refundedLineKey")?.isNotBlank() == true
                    val hasItemName = refundDoc.getString("refundedItemName")?.trim()?.isNotBlank() == true

                    if (hasLineKey) {
                        val key = refundDoc.getString("refundedLineKey")!!
                        refundedLineKeys.add(key)
                        refundedLineKeyToEmployee[key] = employee
                        refundedLineKeyToDate[key] = dateStr
                    } else if (hasItemName) {
                        val itemName = refundDoc.getString("refundedItemName")!!.trim()
                        val nameAmountKey = "$itemName|$amountCents"
                        refundedNameAmountKeys.add(nameAmountKey)
                        refundedNameAmountToEmployee[nameAmountKey] = employee
                        refundedNameAmountToDate[nameAmountKey] = dateStr
                    } else {
                        wholeOrderRefundEmployee = employee
                        wholeOrderRefundDate = dateStr
                    }
                }
                onLoaded(totalCents, refundedLineKeys, refundedNameAmountKeys, refundedLineKeyToEmployee, refundedNameAmountToEmployee, refundedLineKeyToDate, refundedNameAmountToDate, wholeOrderRefundEmployee, wholeOrderRefundDate)
            }
            .addOnFailureListener { onLoaded(0L, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, null) }
    }
    // ===============================
    // LOAD ITEMS
    // ===============================

    private fun updateOrderTypeBadge(status: String) {
        if (orderType != "TO_GO" && orderType != "DINE_IN") {
            txtOrderType.visibility = View.GONE
            return
        }

        txtOrderType.visibility = View.VISIBLE
        val label = if (orderType == "TO_GO") "TO-GO" else "DINE IN"
        val bgColor = if (orderType == "TO_GO") "#FF9800" else "#4CAF50"

        txtOrderType.text = if (status == "OPEN") "$label  ✎" else label
        txtOrderType.setTextColor(android.graphics.Color.WHITE)

        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(android.graphics.Color.parseColor(bgColor))
        bg.cornerRadius = 16f
        txtOrderType.background = bg

        if (status == "OPEN") {
            txtOrderType.setOnClickListener { showSwitchOrderTypeDialog() }
        } else {
            txtOrderType.setOnClickListener(null)
            txtOrderType.isClickable = false
        }
    }

    private fun showSwitchOrderTypeDialog() {
        if (orderType == "TO_GO") {
            AlertDialog.Builder(this)
                .setTitle("Switch to Dine In")
                .setMessage("Select a table for this order?")
                .setPositiveButton("Yes") { _, _ ->
                    val intent = Intent(this, TableSelectionActivity::class.java)
                    intent.putExtra("SELECT_TABLE_ONLY", true)
                    tableSelectLauncher.launch(intent)
                }
                .setNegativeButton("No", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Switch to To Go")
                .setMessage("Change this order to To Go?")
                .setPositiveButton("Yes") { _, _ ->
                    switchToToGo()
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun switchToDineIn(
        tableId: String,
        tableName: String,
        sectionId: String,
        sectionName: String,
        guestCount: Int,
        guestNames: List<String>
    ) {
        val updates = mutableMapOf<String, Any>(
            "orderType" to "DINE_IN",
            "updatedAt" to Date()
        )
        if (tableId.isNotBlank()) updates["tableId"] = tableId
        if (tableName.isNotBlank()) updates["tableName"] = tableName
        if (sectionId.isNotBlank()) updates["sectionId"] = sectionId
        if (sectionName.isNotBlank()) updates["sectionName"] = sectionName
        if (guestCount > 0) updates["guestCount"] = guestCount
        if (guestNames.isNotEmpty()) updates["guestNames"] = guestNames

        db.collection("Orders").document(orderId)
            .update(updates)
            .addOnSuccessListener {
                orderType = "DINE_IN"
                updateOrderTypeBadge("OPEN")
                Toast.makeText(this, "Switched to Dine In – $tableName", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun switchToToGo() {
        val updates = mapOf<String, Any>(
            "orderType" to "TO_GO",
            "tableId" to FieldValue.delete(),
            "tableName" to FieldValue.delete(),
            "sectionId" to FieldValue.delete(),
            "sectionName" to FieldValue.delete(),
            "guestCount" to FieldValue.delete(),
            "guestNames" to FieldValue.delete(),
            "updatedAt" to Date()
        )

        db.collection("Orders").document(orderId)
            .update(updates)
            .addOnSuccessListener {
                orderType = "TO_GO"
                updateOrderTypeBadge("OPEN")
                Toast.makeText(this, "Switched to To Go", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ===============================

    private var guestNames: List<String> = emptyList()

    private fun loadItems() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                @Suppress("UNCHECKED_CAST")
                guestNames = (orderDoc.get("guestNames") as? List<String>) ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val orderAppliedDiscounts =
                    (orderDoc.get("appliedDiscounts") as? List<Map<String, Any>>) ?: emptyList()
                adapter.setAppliedDiscounts(orderAppliedDiscounts)

                db.collection("Orders").document(orderId)
                    .collection("items")
                    .get()
                    .addOnSuccessListener { docs ->
                        itemDocs.clear()
                        itemDocs.addAll(docs.documents)

                        listItems.clear()

                        val hasGuests = orderType == "DINE_IN" &&
                            itemDocs.any { (it.getLong("guestNumber") ?: 0L) > 0L }

                        if (hasGuests) {
                            val grouped = itemDocs.groupBy { (it.getLong("guestNumber") ?: 0L).toInt() }
                            for (guestNum in grouped.keys.sorted()) {
                                if (guestNum > 0) {
                                    val name = guestNames.getOrNull(guestNum - 1)?.takeIf { it.isNotBlank() }
                                    listItems.add(OrderListItem.GuestHeader(guestNum, name))
                                }
                                grouped[guestNum]?.forEach { listItems.add(OrderListItem.Item(it)) }
                            }
                        } else {
                            itemDocs.forEach { listItems.add(OrderListItem.Item(it)) }
                        }

                        adapter.notifyDataSetChanged()

                        if (listItems.isEmpty()) {
                            txtEmptyItems.visibility = View.VISIBLE
                            recycler.visibility = View.GONE
                        } else {
                            txtEmptyItems.visibility = View.GONE
                            recycler.visibility = View.VISIBLE
                        }
                    }
            }
    }

    // ===============================
    // VOID
    // ===============================

    private fun confirmVoid() {

        val batchId = currentBatchId ?: return

        db.collection("Batches")
            .document(batchId)
            .get()
            .addOnSuccessListener { batchDoc ->

                val batchClosed = batchDoc.getBoolean("closed") ?: true

                if (batchClosed) {
                    Toast.makeText(this, "Batch already closed. Cannot void.", Toast.LENGTH_LONG).show()
                    btnVoid.visibility = View.GONE
                    return@addOnSuccessListener
                }

                AlertDialog.Builder(this)
                    .setTitle("Void Order")
                    .setMessage("Void this order?")
                    .setPositiveButton("Void") { _, _ -> executeVoid() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }

    private fun executeVoid() {
        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->
                val transactionId = orderDoc.getString("saleTransactionId")
                    ?: orderDoc.getString("transactionId")
                    ?: run {
                        Toast.makeText(this, "No transaction linked to this order.", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->
                        if (!txDoc.exists()) {
                            Toast.makeText(this, "Transaction not found.", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }
                        @Suppress("UNCHECKED_CAST")
                        val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val txType = txDoc.getString("type") ?: "SALE"
                        if (txType == "PRE_AUTH") {
                            Toast.makeText(this, "Pre-authorization cannot be voided. Capture the tab first, then void the capture if needed.", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }
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
                        }.ifEmpty {
                            val firstRef = getReferenceIdFromTransaction(txDoc)
                            val firstType = getPaymentTypeFromTransaction(txDoc).ifBlank { "Credit" }
                            val amountCents = txDoc.getLong("totalPaidInCents") ?: 0L
                            listOf(
                                TransactionPayment(
                                    paymentType = firstType,
                                    amountInCents = amountCents,
                                    referenceId = firstRef,
                                    clientReferenceId = txDoc.getString("clientReferenceId") ?: "",
                                    batchNumber = txDoc.getString("batchNumber") ?: "",
                                    transactionNumber = (txDoc.get("transactionNumber") as? Number)?.toString() ?: ""
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
                                    runVoidSequence(transactionId, cardPayments)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            runVoidSequence(transactionId, cardPayments)
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load transaction: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun runVoidSequence(txDocId: String, cardPayments: List<TransactionPayment>) {
        if (cardPayments.isEmpty()) {
            finalizeVoid(txDocId)
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
            finalizeVoid(txDocId)
            return
        }
        val payment = cardPayments[index]
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        if (refId.isBlank()) {
            Toast.makeText(this, "Cannot void: no ReferenceId for card payment.", Toast.LENGTH_LONG).show()
            return
        }
        callVoidApiForPayment(payment, txDocId, cardPayments, index)
    }

    private fun callVoidApiForPayment(
        payment: TransactionPayment,
        transactionId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        val amount = payment.amountInCents / 100.0
        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", payment.paymentType.ifBlank { "Credit" })
            put("ReferenceId", refId)
            if (payment.authCode.isNotBlank()) put("AuthCode", payment.authCode)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(this@OrderDetailActivity))
            put("RegisterId", TerminalPrefs.getRegisterId(this@OrderDetailActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@OrderDetailActivity))
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
        }
        Log.d("TX_API", "[VOID_REQ] ${json.toString()}")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Void")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@OrderDetailActivity, "Void Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                runOnUiThread {
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Void error ${response.code}: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    val resultCode = JSONObject(responseText)
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode")
                    if (resultCode == "0") {
                        voidCardPaymentsSequentially(transactionId, cardPayments, index + 1)
                    } else {
                        Toast.makeText(this@OrderDetailActivity, "Void Declined", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun finalizeVoid(transactionId: String) {
        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->
                var batchId = orderDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                if (batchId.isNullOrBlank()) {
                    db.collection("Transactions").document(transactionId).get()
                        .addOnSuccessListener { txDoc ->
                            batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                            if (!batchId.isNullOrBlank()) {
                                runFinalizeVoidBatch(transactionId, orderDoc, batchId!!)
                            } else {
                                Toast.makeText(this, "Cannot void: batch not found", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Cannot void: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    return@addOnSuccessListener
                }
                runFinalizeVoidBatch(transactionId, orderDoc, batchId!!)
            }
    }

    private fun runFinalizeVoidBatch(transactionId: String, orderDoc: DocumentSnapshot, batchId: String) {
        val txRef = db.collection("Transactions").document(transactionId)
        txRef.get()
            .addOnSuccessListener { txDoc ->
                if (!txDoc.exists()) return@addOnSuccessListener
                val amount = txDoc.getLong("totalPaidInCents")?.let { it / 100.0 }
                    ?: txDoc.getDouble("totalPaid") ?: txDoc.getDouble("amount") ?: 0.0
                @Suppress("UNCHECKED_CAST")
                val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val updatedPayments = paymentsRaw.map { p ->
                    val mutable = p.toMutableMap()
                    mutable["status"] = "VOIDED"
                    mutable
                }

                db.runBatch { batch ->
                    val orderRef = db.collection("Orders").document(orderId)
                    val batchRef = db.collection("Batches").document(batchId)

                    val voidedBy = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                        ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)
                    batch.update(txRef, mapOf(
                        "voided" to true,
                        "voidedBy" to voidedBy,
                        "payments" to updatedPayments
                    ))
                    batch.update(orderRef, mapOf(
                        "status" to "VOIDED",
                        "voidedAt" to Date(),
                        "voidedBy" to voidedBy
                    ))

                    batch.update(batchRef, mapOf(
                        "totalSales" to FieldValue.increment(-amount),
                        "netTotal" to FieldValue.increment(-amount),
                        "transactionCount" to FieldValue.increment(-1)
                    ))
                }
                    .addOnSuccessListener {
                        ReceiptPromptHelper.promptForReceipt(
                            this, ReceiptPromptHelper.ReceiptType.VOID, orderId, transactionId
                        ) { loadHeader() }
                    }
            }
    }

    private fun confirmRefund() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                val remainingCents = (totalInCents - totalRefundedInCents).coerceAtLeast(0L)
                if (remainingCents <= 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                RefundDialogHelper.showRefundOptionsDialog(this, remainingCents) { amountCents ->
                    executeRefundForAmount(amountCents, finishAfter = true)
                }
            }
    }

    private fun onOrderItemClick(itemDoc: DocumentSnapshot) {
        val lineKey = itemDoc.id
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                val status = orderDoc.getString("status") ?: ""
                if (status != "CLOSED" && status != "REFUNDED") {
                    Toast.makeText(this, "Refund is only available for closed orders.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                if (totalRefundedInCents >= totalInCents && totalInCents > 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val saleTransactionId = orderDoc.getString("saleTransactionId") ?: orderDoc.getString("transactionId")
                if (!saleTransactionId.isNullOrBlank()) {
                    db.collection("Transactions")
                        .whereEqualTo("type", "REFUND")
                        .whereEqualTo("originalReferenceId", saleTransactionId)
                        .get()
                        .addOnSuccessListener { refundSnap ->
                            val refundedLineKeys = refundSnap.documents
                                .mapNotNull { it.getString("refundedLineKey")?.takeIf { k -> k.isNotBlank() } }
                                .toSet()
                            if (lineKey in refundedLineKeys) {
                                Toast.makeText(this, "This item has already been refunded.", Toast.LENGTH_SHORT).show()
                                return@addOnSuccessListener
                            }
                            val lineTotalInCents = itemDoc.getLong("lineTotalInCents") ?: 0L
                            val lineTotalWithTaxInCents = itemDoc.getLong("lineTotalWithTaxInCents")
                            val itemName = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: ""
                            val alreadyRefundedByNameAndAmount = refundSnap.documents.any { ref ->
                                ref.getString("refundedLineKey")?.isNotBlank() != true &&
                                ref.getString("refundedItemName")?.trim() == itemName.trim() &&
                                (ref.getLong("amountInCents") == lineTotalInCents ||
                                 (lineTotalWithTaxInCents != null && ref.getLong("amountInCents") == lineTotalWithTaxInCents))
                            }
                            if (alreadyRefundedByNameAndAmount) {
                                Toast.makeText(this, "This item has already been refunded.", Toast.LENGTH_SHORT).show()
                                return@addOnSuccessListener
                            }
                            val refundedNameAmountKeys = refundSnap.documents
                                .filter { it.getString("refundedLineKey")?.isNotBlank() != true }
                                .mapNotNull { ref ->
                                    val refName = ref.getString("refundedItemName")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                    val refAmount = ref.getLong("amountInCents") ?: return@mapNotNull null
                                    "$refName|$refAmount"
                                }.toSet()
                            proceedWithItemRefundDialog(orderDoc, itemDoc, lineKey, refundedLineKeys, refundedNameAmountKeys)
                        }
                } else {
                    proceedWithItemRefundDialog(orderDoc, itemDoc, lineKey, emptySet(), emptySet())
                }
            }
    }

    private fun proceedWithItemRefundDialog(
        orderDoc: DocumentSnapshot,
        itemDoc: DocumentSnapshot,
        lineKey: String,
        refundedLineKeys: Set<String>,
        refundedNameAmountKeys: Set<String>
    ) {
        val lineTotalInCents = itemDoc.getLong("lineTotalInCents") ?: 0L
        if (lineTotalInCents <= 0L) {
            Toast.makeText(this, "This item has no amount to refund.", Toast.LENGTH_SHORT).show()
            return
        }
        val itemName = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: "Item"

        val storedTotalWithTax = itemDoc.getLong("lineTotalWithTaxInCents")
        val itemRefundBase: Long = if (storedTotalWithTax != null && storedTotalWithTax > 0L) {
            storedTotalWithTax
        } else {
            val orderTotalInCents = orderDoc.getLong("totalInCents") ?: 0L
            val subtotalInCents = itemDocs.sumOf { it.getLong("lineTotalInCents") ?: 0L }
            if (subtotalInCents > 0L && orderTotalInCents > subtotalInCents) {
                Math.round(orderTotalInCents.toDouble() * lineTotalInCents / subtotalInCents)
            } else {
                lineTotalInCents
            }
        }

        val otherUnrefundedItems = itemDocs.filter { doc ->
            if (doc.id == lineKey) return@filter false
            if (doc.id in refundedLineKeys) return@filter false
            val docName = (doc.getString("name") ?: doc.getString("itemName") ?: "").trim()
            val docLineCents = doc.getLong("lineTotalInCents") ?: 0L
            if ("$docName|$docLineCents" in refundedNameAmountKeys) return@filter false
            true
        }

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
        val remainingBalance = totalInCents - totalRefundedInCents

        if (remainingBalance <= 0L) {
            Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
            return
        }

        val refundAmount = if (otherUnrefundedItems.isEmpty()) {
            remainingBalance
        } else {
            minOf(itemRefundBase, remainingBalance)
        }

        val amountStr = String.format(Locale.US, "%.2f", refundAmount / 100.0)
        val includesTax = refundAmount > lineTotalInCents
        val message = if (includesTax) {
            "Refunding:\n$itemName\n\nAmount:\n\$$amountStr (includes tax)"
        } else {
            "Refunding:\n$itemName\n\nAmount:\n\$$amountStr"
        }

        AlertDialog.Builder(this)
            .setTitle("Refund Item")
            .setMessage(message)
            .setPositiveButton("Refund") { _, _ ->
                executeRefundForAmount(refundAmount, finishAfter = false, refundedItemName = itemName, refundedLineKey = lineKey)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeRefundForAmount(amountInCents: Long?, finishAfter: Boolean, refundedItemName: String? = null, refundedLineKey: String? = null) {

        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                if (!orderDoc.exists()) return@addOnSuccessListener

                val status = orderDoc.getString("status") ?: ""
                if (status == "REFUNDED") {
                    Toast.makeText(this, "Order already refunded", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val transactionId = orderDoc.getString("saleTransactionId")
                    ?: orderDoc.getString("transactionId")
                    ?: return@addOnSuccessListener

                val orderTotalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val alreadyRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                val remainingRefundable = orderTotalInCents - alreadyRefundedInCents
                if (remainingRefundable <= 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->

                        val fullAmountInCents = txDoc.getLong("totalPaidInCents") ?: 0L
                        val uncappedAmount = amountInCents ?: fullAmountInCents
                        val refundAmountInCents = minOf(uncappedAmount, remainingRefundable)
                        if (refundAmountInCents <= 0L) return@addOnSuccessListener
                        if (refundAmountInCents > fullAmountInCents) {
                            Toast.makeText(this, "Refund amount cannot exceed transaction amount.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val payments = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val firstPayment = payments.firstOrNull()
                        val paymentType = firstPayment?.get("paymentType")?.toString() ?: "Credit"
                        val referenceId = firstPayment?.get("referenceId")?.toString()
                            ?: firstPayment?.get("terminalReference")?.toString()

                        if (paymentType.equals("Cash", ignoreCase = true)) {
                            finalizeRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName, refundedLineKey)
                            return@addOnSuccessListener
                        }

                        if (!referenceId.isNullOrBlank()) {
                            callRefundApi(
                                referenceId = referenceId,
                                paymentType = paymentType,
                                amount = refundAmountInCents / 100.0,
                                originalTransactionId = transactionId,
                                amountInCents = refundAmountInCents,
                                finishAfter = finishAfter,
                                refundedItemName = refundedItemName,
                                refundedLineKey = refundedLineKey
                            )
                        } else {
                            Toast.makeText(this, "Cannot refund: no reference for this transaction.", Toast.LENGTH_LONG).show()
                        }
                    }
            }
    }
    private fun callRefundApi(
        referenceId: String,
        paymentType: String,
        amount: Double,
        originalTransactionId: String,
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null,
        refundedLineKey: String? = null
    ) {
        // Debit refunds use the same flow as credit (Credit Return); do not send Debit Return (Z8)
        val requestPaymentType = if (paymentType.equals("Debit", true)) "Credit" else paymentType
        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", requestPaymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", TerminalPrefs.getTpn(this@OrderDetailActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@OrderDetailActivity))
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Return")
            .post(body)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "Refund Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Refund error ${response.code}: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    try {
                        val jsonResponse = JSONObject(responseText)
                        val general = jsonResponse.optJSONObject("GeneralResponse")
                        val resultCode = general?.optString("ResultCode") ?: ""

                        if (resultCode == "0") {
                            finalizeRefund(originalTransactionId, amountInCents, finishAfter, refundedItemName, refundedLineKey, paymentType)
                        } else {
                            Toast.makeText(
                                this@OrderDetailActivity,
                                "Refund Declined",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Refund parse error: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }
    // ===============================
    // RECEIPT FLOW
    // ===============================

    private fun showOrderReceiptFlow() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                val status = doc.getString("status") ?: ""
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val isVoided = status == "VOIDED"
                val hasRefunds = totalRefundedInCents > 0L

                if (!hasRefunds && !isVoided) {
                    showReceiptDeliveryDialog(ReceiptContentType.ORIGINAL)
                } else {
                    showReceiptTypeDialog(isVoided, hasRefunds)
                }
            }
    }

    private fun showReceiptTypeDialog(isVoided: Boolean, hasRefunds: Boolean) {
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
                    options[which].contains("Original") -> showReceiptDeliveryDialog(ReceiptContentType.ORIGINAL)
                    options[which].contains("Void") -> showReceiptDeliveryDialog(ReceiptContentType.VOID)
                    options[which].contains("Refund") -> showReceiptDeliveryDialog(ReceiptContentType.REFUND)
                }
            }
            .show()
    }

    private fun showReceiptDeliveryDialog(contentType: ReceiptContentType) {
        val options = arrayOf("\uD83D\uDDA8\uFE0F  Print Receipt", "\u2709\uFE0F  Email Receipt", "Cancel")
        AlertDialog.Builder(this)
            .setTitle("Send Receipt")
            .setItems(options) { _, which ->
                when {
                    options[which].contains("Print") -> handlePrintReceipt(contentType)
                    options[which].contains("Email") -> handleEmailReceipt(contentType)
                }
            }
            .show()
    }

    // ── Email Receipt ────────────────────────────────────────────

    private fun handleEmailReceipt(contentType: ReceiptContentType) {
        val txId = saleTransactionId ?: ""
        when (contentType) {
            ReceiptContentType.ORIGINAL -> showTypedEmailDialog(orderId, "sendReceiptEmail", "")
            ReceiptContentType.REFUND -> showTypedEmailDialog(orderId, "sendRefundReceiptEmail", txId)
            ReceiptContentType.VOID -> showTypedEmailDialog(orderId, "sendVoidReceiptEmail", txId)
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

    private fun handlePrintReceipt(contentType: ReceiptContentType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingPrintContentType = contentType
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_CONNECT
                )
                return
            }
        }
        executePrint(contentType)
    }

    private fun executePrint(contentType: ReceiptContentType) {
        Toast.makeText(this, "Preparing receipt\u2026", Toast.LENGTH_SHORT).show()
        when (contentType) {
            ReceiptContentType.ORIGINAL -> printOriginalReceipt()
            ReceiptContentType.REFUND -> printRefundReceipt()
            ReceiptContentType.VOID -> printVoidReceipt()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun printOriginalReceipt() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    Toast.makeText(this, "Order not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val txId = saleTransactionId ?: ""
                        val rs = ReceiptSettings.load(this)
                        if (txId.isNotBlank()) {
                            db.collection("Transactions").document(txId).get()
                                .addOnSuccessListener { txDoc ->
                                    val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                    EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, payments), rs)
                                }
                                .addOnFailureListener {
                                    EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, emptyList()), rs)
                                }
                        } else {
                            EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, emptyList()), rs)
                        }
                    }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildOriginalSegments(
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        payments: List<Map<String, Any>>
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
        if (rs.showEmail && rs.email.isNotBlank()) {
            segs += EscPosPrinter.Segment(rs.email, bold = rs.boldAddress, fontSize = 0, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("RECEIPT", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("", fontSize = rs.fontSizeOrderInfo, centered = true)

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val oType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val dateStr = java.text.SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (oType.isNotBlank()) {
            val label = when (oType) { "DINE_IN" -> "Dine In"; "TO_GO" -> "To Go"; "BAR_TAB" -> "Bar Tab"; else -> oType }
            segs += EscPosPrinter.Segment("Type: $label", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (rs.showServerName && empName.isNotBlank()) segs += EscPosPrinter.Segment("Server: $empName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (custName.isNotBlank()) segs += EscPosPrinter.Segment("Customer: $custName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        @Suppress("UNCHECKED_CAST")
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()

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
            val lineKey = doc.id
            val itemDiscounts = appliedDiscounts.filter { ad ->
                val lk = ad["lineKey"]?.toString()?.trim().orEmpty()
                lk == lineKey
            }
            val orderDiscounts = appliedDiscounts.filter { ad ->
                val lk = ad["lineKey"]?.toString()?.trim().orEmpty()
                val scope = ad["applyScope"]?.toString()?.trim()?.lowercase() ?: ""
                lk.isEmpty() && (scope == "order" || scope == "manual")
            }
            for (ad in itemDiscounts + orderDiscounts) {
                segs += EscPosPrinter.Segment("  ${DiscountDisplay.formatBulletFromFirestoreMap(ad)}", bold = rs.boldItems, fontSize = rs.fontSizeItems)
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
        if (tipAmountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        segs += EscPosPrinter.Segment(formatLine("TOTAL", MoneyUtils.centsToDisplay(totalInCents), lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

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
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you for dining with us!", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    @Suppress("UNCHECKED_CAST")
    private fun printRefundReceipt() {
        val txId = saleTransactionId ?: ""
        val rs = ReceiptSettings.load(this)
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        if (txId.isBlank()) {
                            EscPosPrinter.print(this, buildSimpleRefundSegments(orderDoc), rs)
                            return@addOnSuccessListener
                        }
                        db.collection("Transactions")
                            .whereEqualTo("type", "REFUND")
                            .whereEqualTo("originalReferenceId", txId)
                            .get()
                            .addOnSuccessListener { refundSnap ->
                                db.collection("Transactions").document(txId).get()
                                    .addOnSuccessListener { txDoc ->
                                        val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                        val segments = buildDetailedRefundSegments(orderDoc, itemsSnap.documents, refundSnap.documents, payments)
                                        EscPosPrinter.print(this, segments, rs)
                                    }
                                    .addOnFailureListener {
                                        val segments = buildDetailedRefundSegments(orderDoc, itemsSnap.documents, refundSnap.documents, emptyList())
                                        EscPosPrinter.print(this, segments, rs)
                                    }
                            }
                            .addOnFailureListener {
                                EscPosPrinter.print(this, buildSimpleRefundSegments(orderDoc), rs)
                            }
                    }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedRefundSegments(
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        refundDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        payments: List<Map<String, Any>> = emptyList()
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

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = java.text.SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
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

        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val l4 = p["last4"]?.toString() ?: ""
                val auth = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || l4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (brand.isNotBlank()) append(brand); if (l4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** $l4") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (auth.isNotBlank()) segs += EscPosPrinter.Segment("Auth: $auth", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)

        return segs
    }

    private fun buildSimpleRefundSegments(orderDoc: com.google.firebase.firestore.DocumentSnapshot): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("REFUND RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = java.text.SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("REFUND TOTAL", "-${MoneyUtils.centsToDisplay(totalRefundedInCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    @Suppress("UNCHECKED_CAST")
    private fun printVoidReceipt() {
        val rs = ReceiptSettings.load(this)
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val txId = saleTransactionId ?: ""
                        val printSegments: (List<Map<String, Any>>) -> Unit = { payments ->
                            val segs = buildDetailedVoidReceiptSegments(orderDoc, itemsSnap.documents, payments)
                            EscPosPrinter.print(this, segs, rs)
                        }
                        if (txId.isNotBlank()) {
                            db.collection("Transactions").document(txId).get()
                                .addOnSuccessListener { txDoc ->
                                    val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                    printSegments(payments)
                                }
                                .addOnFailureListener { printSegments(emptyList()) }
                        } else {
                            printSegments(emptyList())
                        }
                    }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedVoidReceiptSegments(
        orderDoc: DocumentSnapshot,
        items: List<DocumentSnapshot>,
        payments: List<Map<String, Any>>
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

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val voidedBy = orderDoc.getString("voidedBy")?.trim()?.takeIf { it.isNotBlank() }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

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
        if (tipAmountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("VOID TOTAL", "-${MoneyUtils.centsToDisplay(totalInCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val l4 = p["last4"]?.toString() ?: ""
                val auth = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || l4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (brand.isNotBlank()) append(brand); if (l4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** $l4") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (auth.isNotBlank()) segs += EscPosPrinter.Segment("Auth: $auth", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_CONNECT) {
            val ct = pendingPrintContentType
            pendingPrintContentType = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && ct != null) {
                executePrint(ct)
            } else {
                Toast.makeText(this, "Bluetooth permission required for printing", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finalizeRefund(
        originalTransactionId: String,
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null,
        refundedLineKey: String? = null,
        paymentType: String = "Cash"
    ) {

        // 1️⃣ Find open batch
        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { batchSnap ->

                if (batchSnap.isEmpty) {
                    Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val openBatch = batchSnap.documents.first()
                val openBatchId = openBatch.id

                val orderRef = db.collection("Orders").document(orderId)
                orderRef.get()
                    .addOnSuccessListener { orderDoc ->
                        if (!orderDoc.exists()) return@addOnSuccessListener
                        val employeeName = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                            ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)

                        val newRefundTxRef = db.collection("Transactions").document()
                        val batchRef = db.collection("Batches").document(openBatchId)

                        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
                        val refundDocData = buildMap<String, Any> {
                            put("orderId", orderId)
                            put("orderNumber", orderNumber)
                            put("type", "REFUND")
                            put("amount", amountInCents / 100.0)
                            put("amountInCents", amountInCents)
                            put("originalReferenceId", originalTransactionId)
                            put("createdAt", Date())
                            put("batchId", openBatchId)
                            put("voided", false)
                            put("settled", false)
                            put("refundedBy", employeeName)
                            put("paymentType", paymentType)
                            refundedItemName?.takeIf { it.isNotBlank() }?.let { put("refundedItemName", it) }
                            refundedLineKey?.takeIf { it.isNotBlank() }?.let { put("refundedLineKey", it) }
                        }
                        db.collection("Transactions").document(newRefundTxRef.id).set(refundDocData)
                            .addOnSuccessListener {
                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                val newTotalRefunded = currentRefunded + amountInCents
                                val orderUpdates = mutableMapOf<String, Any>(
                                    "totalRefundedInCents" to newTotalRefunded,
                                    "refundedAt" to Date()
                                )
                                if (newTotalRefunded >= totalInCents) {
                                    orderUpdates["status"] = "REFUNDED"
                                }
                                orderRef.update(orderUpdates)
                                    .addOnSuccessListener {
                                        db.collection("Batches").document(openBatchId)
                                            .update(mapOf(
                                                "totalRefundsInCents" to FieldValue.increment(amountInCents),
                                                "netTotalInCents" to FieldValue.increment(-amountInCents),
                                                "transactionCount" to FieldValue.increment(1)
                                            ))
                                            .addOnSuccessListener {
                                                ReceiptPromptHelper.promptForReceipt(
                                                    this, ReceiptPromptHelper.ReceiptType.REFUND, orderId, originalTransactionId
                                                ) {
                                                    if (finishAfter) finish() else { loadHeader(); loadItems() }
                                                }
                                            }
                                    }
                            }
                    }
            }
    }
}