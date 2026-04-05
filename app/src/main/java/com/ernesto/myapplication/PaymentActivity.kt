package com.ernesto.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.InputType
import android.os.*
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale
import com.ernesto.myapplication.engine.DiscountEngine
import com.ernesto.myapplication.engine.PaymentEngine
import com.ernesto.myapplication.engine.SplitReceiptCalculator
import com.ernesto.myapplication.engine.SplitReceiptPayload
import android.util.Log
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils

class PaymentActivity : AppCompatActivity() {

    companion object {
        private const val REQ_BT_SPLIT_RECEIPT = 1003
    }

    private data class SplitShareInfo(
        val guestIndex: Int,
        val guestLabel: String,
        val lineKeys: List<String>,
        val amountCents: Long
    )

    private var isMixMode = false

    private lateinit var btnMixMode: MaterialButton
    private lateinit var btnSplitPayments: MaterialButton
    private lateinit var btnCredit: MaterialButton
    private lateinit var btnDebit: MaterialButton
    private lateinit var btnCash: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private lateinit var txtPaymentTotal: TextView
    private lateinit var txtOrderSummary: TextView
    private lateinit var labelCardPayments: TextView
    private lateinit var labelCashPayments: TextView
    private lateinit var labelOther: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var txtSubStatus: TextView
    private lateinit var txtStatus: TextView
    private lateinit var fireworksView: FireworksView

    private var orderId: String? = null
    private var batchId: String? = null
    private var orderType: String = ""
    private var remainingBalance = 0.0
    private var paymentAmount = 0.0
    private var remainingCents: Long = 0L
    private var businessName: String = ""

    private var splitPayAmount = -1.0
    private var splitTotalCount = 0
    private var splitPaymentsCompleted = 0
    private var splitShareInfos: List<SplitShareInfo>? = null
    private var pendingSplitReceiptPayload: SplitReceiptPayload? = null

    private data class PendingSplitPrint(
        val oid: String,
        val payload: SplitReceiptPayload,
        val remainingInCents: Long
    )

    private var pendingSplitPrint: PendingSplitPrint? = null

    private var splitReceiptChoiceDialog: AlertDialog? = null
    private var activeSplitReceiptPayload: SplitReceiptPayload? = null
    private var activeSplitReceiptRemainingInCents: Long = 0L

    private val db = FirebaseFirestore.getInstance()
    private lateinit var paymentEngine: PaymentEngine
    private lateinit var discountEngine: DiscountEngine
    private lateinit var btnDiscounts: MaterialButton
    private lateinit var appliedDiscountBanner: LinearLayout
    private lateinit var txtAppliedDiscount: TextView
    private lateinit var btnRemoveDiscount: TextView
    private var selectedManualDiscountId: String? = null

    private var lastCashTenderedCents: Long = 0L
    private var lastCashChangeCents: Long = 0L

    private val cashPaymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                lastCashTenderedCents = result.data?.getLongExtra(CashPaymentActivity.EXTRA_TENDERED_CENTS, 0L) ?: 0L
                lastCashChangeCents = result.data?.getLongExtra(CashPaymentActivity.EXTRA_CHANGE_CENTS, 0L) ?: 0L
                showWaitingStatus()
                CustomerDisplayManager.showProcessing(this)
                completePayment("Cash")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        paymentEngine = PaymentEngine(db)
        discountEngine = DiscountEngine(db)

        txtPaymentTotal = findViewById(R.id.txtPaymentTotal)
        txtOrderSummary = findViewById(R.id.txtOrderSummary)
        labelCardPayments = findViewById(R.id.labelCardPayments)
        labelCashPayments = findViewById(R.id.labelCashPayments)
        labelOther = findViewById(R.id.labelOther)
        btnCredit = findViewById(R.id.btnCredit)
        btnDebit = findViewById(R.id.btnDebit)
        btnCash = findViewById(R.id.btnCash)
        btnMixMode = findViewById(R.id.btnMixMode)
        btnSplitPayments = findViewById(R.id.btnSplitPayments)
        btnCancel = findViewById(R.id.btnCancel)

        statusContainer = findViewById(R.id.statusContainer)
        progressBar = findViewById(R.id.progressBar)
        txtSubStatus = findViewById(R.id.txtSubStatus)
        txtStatus = findViewById(R.id.txtStatus)
        fireworksView = findViewById(R.id.fireworksView)

        btnDiscounts = findViewById(R.id.btnDiscounts)
        appliedDiscountBanner = findViewById(R.id.appliedDiscountBanner)
        txtAppliedDiscount = findViewById(R.id.txtAppliedDiscount)
        btnRemoveDiscount = findViewById(R.id.btnRemoveDiscount)

        orderId = intent.getStringExtra("ORDER_ID")
        batchId = intent.getStringExtra("BATCH_ID")
        businessName = ReceiptSettings.load(this).businessName
        ensureBatchIdThenLoadBalance()
        loadManualDiscounts()

        btnDiscounts.setOnClickListener { showDiscountsDialog() }
        btnRemoveDiscount.setOnClickListener { removeAppliedDiscount() }

        updateMixPaymentsVisibility()

        btnCancel.setOnClickListener { finish() }

        btnMixMode.setOnClickListener {
            isMixMode = !isMixMode
            btnMixMode.text = if (isMixMode) "Mix Mode ON" else "Mix Payments"
        }

        btnCredit.setOnClickListener {
            if (isMixMode) showAmountDialog("Credit")
            else processFullPayment("Credit")
        }

        btnDebit.setOnClickListener {
            if (isMixMode) showAmountDialog("Debit")
            else processFullPayment("Debit")
        }

        btnCash.setOnClickListener {
            if (isMixMode) showAmountDialog("Cash")
            else launchCashPayment(remainingBalance)
        }

        btnSplitPayments.setOnClickListener {
            val intent = Intent(this, SplitPaymentActivity::class.java)
            intent.putExtra("ORDER_ID", orderId)
            intent.putExtra("BATCH_ID", batchId)
            intent.putExtra("REMAINING", remainingBalance)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        CustomerDisplayManager.attach(this)
        updateMixPaymentsVisibility()
        loadRemainingBalance()
    }

    private fun updateMixPaymentsVisibility() {
        if (orderType == "TO_GO" || orderType == "DINE_IN" || orderType == "BAR_TAB") {
            btnMixMode.visibility = if (OrderTypePaymentConfig.isMixPaymentsEnabled(this, orderType)) View.VISIBLE else View.GONE
            btnCredit.visibility = if (OrderTypePaymentConfig.isCreditEnabled(this, orderType)) View.VISIBLE else View.GONE
            btnDebit.visibility = if (OrderTypePaymentConfig.isDebitEnabled(this, orderType)) View.VISIBLE else View.GONE
            btnCash.visibility = if (OrderTypePaymentConfig.isCashEnabled(this, orderType)) View.VISIBLE else View.GONE
            btnSplitPayments.visibility = if (OrderTypePaymentConfig.isSplitPaymentsEnabled(this, orderType)) View.VISIBLE else View.GONE
        } else {
            btnMixMode.visibility = if (MixPaymentsConfig.isEnabled(this)) View.VISIBLE else View.GONE
            btnCredit.visibility = if (PaymentMethodsConfig.isCreditEnabled(this)) View.VISIBLE else View.GONE
            btnDebit.visibility = if (PaymentMethodsConfig.isDebitEnabled(this)) View.VISIBLE else View.GONE
            btnCash.visibility = if (PaymentMethodsConfig.isCashEnabled(this)) View.VISIBLE else View.GONE
            btnSplitPayments.visibility = if (PaymentMethodsConfig.isSplitPaymentsEnabled(this)) View.VISIBLE else View.GONE
        }
        updateSectionLabelsVisibility()
    }

    private fun updateSectionLabelsVisibility() {
        val anyCard = btnCredit.visibility == View.VISIBLE || btnDebit.visibility == View.VISIBLE
        labelCardPayments.visibility = if (anyCard) View.VISIBLE else View.GONE

        labelCashPayments.visibility = btnCash.visibility

        val anyOther = btnMixMode.visibility == View.VISIBLE
                || btnSplitPayments.visibility == View.VISIBLE
                || btnDiscounts.visibility == View.VISIBLE
        labelOther.visibility = if (anyOther) View.VISIBLE else View.GONE
    }

    /**
     * If BATCH_ID was not passed in the intent, resolve the current open batch from Firestore
     * so the order is updated with the correct batchId when a transaction is approved.
     */
    private fun ensureBatchIdThenLoadBalance() {
        if (!batchId.isNullOrBlank()) {
            loadRemainingBalance()
            return
        }
        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                batchId = snap.documents.firstOrNull()?.id
                loadRemainingBalance()
            }
            .addOnFailureListener {
                loadRemainingBalance()
            }
    }

    private fun loadRemainingBalance() {
        val oid = orderId ?: return

        db.collection("Orders").document(oid).get(Source.SERVER)
            .addOnSuccessListener { snap ->

                orderType = snap.getString("orderType") ?: ""
                updateMixPaymentsVisibility()
                bindOrderSummary(snap)

                val remainingInCents =
                    snap.getLong("remainingInCents")
                        ?: snap.getLong("totalInCents")
                        ?: 0L

                remainingBalance = MoneyUtils.centsToDouble(remainingInCents)
                remainingCents = remainingInCents

                if (remainingInCents <= 0L) {
                    setResult(RESULT_OK)
                    finish()
                    return@addOnSuccessListener
                }

                txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)

                showSplitPayShareDialogIfNeeded()
            }
            .addOnFailureListener {
                db.collection("Orders").document(oid).get()
                    .addOnSuccessListener { snap ->

                        orderType = snap.getString("orderType") ?: ""
                        updateMixPaymentsVisibility()
                        bindOrderSummary(snap)

                        val remainingInCents =
                            snap.getLong("remainingInCents")
                                ?: snap.getLong("totalInCents")
                                ?: 0L

                        remainingBalance = MoneyUtils.centsToDouble(remainingInCents)
                        remainingCents = remainingInCents

                        if (remainingInCents <= 0L) {
                            setResult(RESULT_OK)
                            finish()
                            return@addOnSuccessListener
                        }

                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)

                        showSplitPayShareDialogIfNeeded()
                    }
            }
    }

    private fun bindOrderSummary(snap: com.google.firebase.firestore.DocumentSnapshot) {
        val parts = mutableListOf<String>()

        val type = snap.getString("orderType") ?: ""
        val tableName = snap.getString("tableName") ?: ""
        val guestCount = (snap.getLong("guestCount") ?: 0L).toInt()
        val orderNumber = snap.getLong("orderNumber") ?: 0L

        when (type) {
            "DINE_IN" -> {
                if (tableName.isNotBlank()) parts.add(tableName)
                if (guestCount > 0) parts.add("$guestCount Guest${if (guestCount > 1) "s" else ""}")
            }
            "TO_GO" -> parts.add("To-Go")
            "BAR", "BAR_TAB" -> parts.add("Bar")
            else -> if (type.isNotBlank()) parts.add(type.replace("_", " "))
        }
        if (orderNumber > 0L) parts.add("Order #$orderNumber")

        txtOrderSummary.text = parts.joinToString(" · ")
        txtOrderSummary.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        loadOrderItems(snap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadOrderItems(orderSnap: com.google.firebase.firestore.DocumentSnapshot) {
        val container = findViewById<LinearLayout>(R.id.orderItemsContainer) ?: return
        container.removeAllViews()
        val oid = orderSnap.id

        val totalInCents = orderSnap.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderSnap.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderSnap.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderSnap.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        val orderAppliedDiscounts =
            orderSnap.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()

        var taxTotalCents = 0L
        for (entry in taxBreakdown) {
            taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
        }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        db.collection("Orders").document(oid)
            .collection("items").get()
            .addOnSuccessListener { itemsSnap ->
                container.removeAllViews()

                val docs = itemsSnap.documents
                for ((index, doc) in docs.withIndex()) {
                    if (index > 0) {
                        container.addView(makeSummarySpacer(8))
                    }

                    val name = doc.getString("name")
                        ?: doc.getString("itemName")
                        ?: "Item"
                    val qty = (doc.getLong("qty")
                        ?: doc.getLong("quantity")
                        ?: 1L).toInt()
                    val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
                    val lineKey = doc.id

                    container.addView(
                        makeSummaryRow(
                            "$name (Qty: $qty)",
                            "",
                            13f,
                            0xEEFFFFFF.toInt(),
                            boldLeft = true
                        )
                    )

                    val mods = doc.get("modifiers") as? List<*> ?: emptyList<Any>()
                    fun addModRows(items: List<*>, indent: String = "") {
                        for (m in items) {
                            val map = m as? Map<*, *> ?: continue
                            val action = map["action"]?.toString() ?: "ADD"
                            val modName = map["name"]?.toString()
                                ?: map["first"]?.toString()
                                ?: continue
                            val label = if (action == "REMOVE") "${indent}\u2022 No $modName" else "${indent}\u2022 $modName"
                            container.addView(makeSummaryRow(label, "", 11f, 0xBBFFFFFF.toInt()))
                            val children = map["children"] as? List<*>
                            if (children != null) addModRows(children, "$indent    \u21B3 ")
                        }
                    }
                    addModRows(mods)

                    val lineDiscounts = orderAppliedDiscounts.filter { ad ->
                        (ad["lineKey"]?.toString()?.trim().orEmpty()) == lineKey
                    }
                    // Order- / manual-scope discounts (empty lineKey) show only under Subtotal, not repeated per line.
                    for (ad in lineDiscounts) {
                        container.addView(
                            makeSummaryRow(
                                DiscountDisplay.formatBulletFromFirestoreMap(ad),
                                "",
                                11f,
                                0xBBFFFFFF.toInt()
                            )
                        )
                    }

                    container.addView(
                        makeSummaryRow(
                            "Line Total: ${MoneyUtils.centsToDisplay(lineTotalCents)}",
                            "",
                            13f,
                            0xFF81C784.toInt(),
                            boldLeft = true
                        )
                    )
                }

                val totalsContainer = findViewById<LinearLayout>(R.id.totalsSummaryContainer)
                totalsContainer?.removeAllViews()

                totalsContainer?.addView(makeSummaryRow("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), 13f, 0xBBFFFFFF.toInt()))

                val groupedDiscounts = DiscountDisplay.groupByName(orderAppliedDiscounts)
                val hasDiscounts = discountInCents > 0L || groupedDiscounts.isNotEmpty()

                if (hasDiscounts) {
                    totalsContainer?.addView(makeTotalsSectionHeader("Discounts", withTopMargin = true))
                    totalsContainer?.addView(makeTotalsSectionDivider())
                    val discColor = 0xAAFFFFFF.toInt()
                    if (groupedDiscounts.isNotEmpty()) {
                        for (gd in groupedDiscounts) {
                            totalsContainer?.addView(
                                makeSummaryRow(
                                    "• ${DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)}",
                                    "-${MoneyUtils.centsToDisplay(gd.totalCents)}",
                                    12f,
                                    discColor
                                )
                            )
                        }
                    } else if (discountInCents > 0L) {
                        totalsContainer?.addView(
                            makeSummaryRow(
                                "• Discount",
                                "-${MoneyUtils.centsToDisplay(discountInCents)}",
                                12f,
                                discColor
                            )
                        )
                    }
                }

                if (taxBreakdown.isNotEmpty()) {
                    totalsContainer?.addView(
                        makeTotalsSectionHeader("Taxes", withTopMargin = hasDiscounts)
                    )
                    totalsContainer?.addView(makeTotalsSectionDivider())
                }
                for (entry in taxBreakdown) {
                    val taxName = entry["name"]?.toString() ?: "Tax"
                    val taxCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                    val tRate = (entry["rate"] as? Number)?.toDouble()
                    val tType = entry["taxType"]?.toString()
                    val tLabel = DiscountDisplay.formatTaxLabel(taxName, tType, tRate)
                    totalsContainer?.addView(makeSummaryRow(tLabel, MoneyUtils.centsToDisplay(taxCents), 12f, 0xAAFFFFFF.toInt()))
                }

                if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(this, tipAmountInCents)) {
                    totalsContainer?.addView(makeSummaryRow("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), 13f, 0xBBFFFFFF.toInt()))
                }

                val customerLines = docs.map { doc ->
                    val n = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
                    val q = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
                    val lt = doc.getLong("lineTotalInCents") ?: 0L
                    fun flattenModNames(items: List<*>, prefix: String = ""): List<String> {
                        val result = mutableListOf<String>()
                        for (m in items) {
                            val map = m as? Map<*, *> ?: continue
                            val action = map["action"]?.toString() ?: "ADD"
                            val modName = map["name"]?.toString() ?: map["first"]?.toString() ?: continue
                            result.add(if (action == "REMOVE") "${prefix}No $modName" else "$prefix$modName")
                            val children = map["children"] as? List<*>
                            if (children != null) result.addAll(flattenModNames(children, "$prefix  \u21B3 "))
                        }
                        return result
                    }
                    val ms = flattenModNames(doc.get("modifiers") as? List<*> ?: emptyList<Any>())
                    CustomerOrderLine(n, q, ms, lt)
                }

                val custDiscountLines = mutableListOf<SummaryLine>()
                if (groupedDiscounts.isNotEmpty()) {
                    for (gd in groupedDiscounts) {
                        custDiscountLines.add(
                            SummaryLine(DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value), gd.totalCents)
                        )
                    }
                } else if (discountInCents > 0L) {
                    custDiscountLines.add(SummaryLine("Discount", discountInCents))
                }

                val custTaxLines = taxBreakdown.map { entry ->
                    val taxName = entry["name"]?.toString() ?: "Tax"
                    val taxCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                    val tRate = (entry["rate"] as? Number)?.toDouble()
                    val tType = entry["taxType"]?.toString()
                    SummaryLine(DiscountDisplay.formatTaxLabel(taxName, tType, tRate), taxCents)
                }

                val custSummary = OrderSummaryInfo(
                    subtotalCents = subtotalCents,
                    discountLines = custDiscountLines,
                    taxLines = custTaxLines,
                    tipCents = tipAmountInCents
                )

                CustomerDisplayManager.updateOrder(
                    this@PaymentActivity,
                    businessName,
                    customerLines,
                    remainingCents,
                    custSummary
                )
            }
    }

    private fun makeSummarySpacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (heightDp * resources.displayMetrics.density).toInt()
            )
        }
    }

    private fun makeSummaryRow(
        left: String,
        right: String,
        size: Float,
        color: Int,
        boldLeft: Boolean = false
    ): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * dp).toInt() }

            addView(TextView(this@PaymentActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = left
                textSize = size
                setTextColor(color)
                if (boldLeft) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (right.isNotBlank()) {
                addView(TextView(this@PaymentActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = right
                    textSize = size
                    setTextColor(color)
                })
            }
        }
    }

    private fun makeTotalsSectionHeader(title: String, withTopMargin: Boolean): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (withTopMargin) (10 * dp).toInt() else 0
                bottomMargin = (2 * dp).toInt()
            }
            text = title
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xBBFFFFFF.toInt())
        }
    }

    private fun makeTotalsSectionDivider(): View {
        val dp = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt()
            ).apply {
                topMargin = (2 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
            }
            setBackgroundColor(0x44FFFFFF)
        }
    }

    private fun makeDivider(verticalMargin: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt()
            ).apply {
                topMargin = verticalMargin
                bottomMargin = verticalMargin
            }
            setBackgroundColor(0x44FFFFFF)
        }
    }

    private fun showSplitPayShareDialogIfNeeded() {
        val amount = intent.getDoubleExtra("SPLIT_PAY_AMOUNT", -1.0)
        val sharesJson = intent.getStringExtra("SPLIT_SHARES_JSON")
        if (amount <= 0 && sharesJson.isNullOrBlank()) return

        intent.removeExtra("SPLIT_MODE")

        if (!sharesJson.isNullOrBlank()) {
            splitShareInfos = parseSplitSharesJson(sharesJson)
            splitTotalCount = splitShareInfos?.size?.coerceAtLeast(1) ?: 1
            splitPayAmount = -1.0
            intent.removeExtra("SPLIT_SHARES_JSON")
            intent.removeExtra("SPLIT_PAY_AMOUNT")
            intent.removeExtra("SPLIT_TOTAL_COUNT")
        } else {
            splitShareInfos = null
            intent.removeExtra("SPLIT_PAY_AMOUNT")
            val totalCount = intent.getIntExtra("SPLIT_TOTAL_COUNT", 0)
            intent.removeExtra("SPLIT_TOTAL_COUNT")
            splitPayAmount = amount
            splitTotalCount = totalCount.coerceAtLeast(1)
        }

        splitPaymentsCompleted = 0
        showSplitPayShareDialog(computeCurrentSplitAmountDollars(), splitPaymentsCompleted + 1)
    }

    private fun parseSplitSharesJson(json: String): List<SplitShareInfo> {
        val arr = JSONArray(json)
        val out = mutableListOf<SplitShareInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val guestIndex = o.getInt("guestIndex")
            val label = o.getString("guestLabel")
            val keysJson = o.getJSONArray("lineKeys")
            val keys = mutableListOf<String>()
            for (j in 0 until keysJson.length()) {
                keys.add(keysJson.getString(j))
            }
            val cents = o.getLong("amountCents")
            out.add(SplitShareInfo(guestIndex, label, keys, cents))
        }
        return out
    }

    private fun isSplitPayMode(): Boolean {
        return !splitShareInfos.isNullOrEmpty() ||
            (splitPayAmount > 0 && splitTotalCount > 0)
    }

    private fun clearSplitState() {
        splitPayAmount = -1.0
        splitTotalCount = 0
        splitPaymentsCompleted = 0
        splitShareInfos = null
    }

    private fun computeCurrentSplitAmountDollars(): Double {
        val rem = roundMoney(remainingBalance)
        val remCents = MoneyUtils.dollarsToCents(rem)
        val infos = splitShareInfos
        if (!infos.isNullOrEmpty()) {
            val idx = splitPaymentsCompleted.coerceIn(0, infos.size - 1)
            val want = infos[idx].amountCents
            return MoneyUtils.centsToDouble(minOf(want, remCents))
        }
        val nextShare = splitPaymentsCompleted + 1
        val isLast = nextShare == splitTotalCount
        return if (isLast) rem else minOf(splitPayAmount, rem)
    }

    private fun showSplitPayShareDialog(amount: Double, shareNumber: Int) {
        val total = splitShareInfos?.size ?: splitTotalCount
        val title = if (total > 1) "Pay your share ($shareNumber of $total)" else "Pay your share"
        showSplitPayShareDialogWithTitle(amount, title)
    }

    private fun showSplitPayShareDialogWithTitle(amount: Double, title: String) {
        val actualAmount = if (remainingBalance > 0 && amount > 0) {
            minOf(amount, roundMoney(remainingBalance))
        } else return
        if (actualAmount <= 0) return

        val formatted = String.format(Locale.US, "%.2f", actualAmount)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Amount: $$formatted")
            .setPositiveButton("Credit") { _, _ ->
                paymentAmount = actualAmount
                showWaitingStatus()
                CustomerDisplayManager.showPaymentWaiting(this, businessName, MoneyUtils.dollarsToCents(paymentAmount))
                processCardPayment("Credit")
            }
            .setNeutralButton("Debit") { _, _ ->
                paymentAmount = actualAmount
                showWaitingStatus()
                CustomerDisplayManager.showPaymentWaiting(this, businessName, MoneyUtils.dollarsToCents(paymentAmount))
                processCardPayment("Debit")
            }
            .setNegativeButton("Cash") { _, _ ->
                paymentAmount = actualAmount
                launchCashPayment(actualAmount)
            }
            .setCancelable(true)
            .show()
    }

    private fun scheduleNextSplitDialogIfNeeded() {
        if (remainingBalance <= 0 || !isSplitPayMode()) {
            clearSplitState()
            return
        }
        val total = splitShareInfos?.size ?: splitTotalCount
        val hasMore = splitPaymentsCompleted < total
        if (!hasMore) {
            clearSplitState()
            return
        }
        val nextShare = splitPaymentsCompleted + 1
        val amount = computeCurrentSplitAmountDollars()
        if (amount <= 0) return
        val title = if (total > 1) {
            "Pay your share ($nextShare of $total)"
        } else {
            "Pay remaining balance"
        }
        Handler(Looper.getMainLooper()).postDelayed({
            showSplitPayShareDialogWithTitle(amount, title)
        }, 1500)
    }

    private fun formatSplitPaymentMethod(paymentType: String, entryType: String?): String {
        if (paymentType.equals("Cash", ignoreCase = true)) return "Cash"
        val entry = receiptLabelForCardEntryType(entryType)
        return if (entry != null) "$paymentType · $entry" else paymentType
    }

    private fun buildSplitReceiptOrNull(
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        itemDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        chargedCents: Long,
        paymentType: String,
        entryType: String?
    ): SplitReceiptPayload? {
        if (!isSplitPayMode()) return null
        val total = splitShareInfos?.size ?: splitTotalCount
        val shareIdx0 = splitPaymentsCompleted
        val splitIndex1Based = shareIdx0 + 1
        val method = formatSplitPaymentMethod(paymentType, entryType)
        val infos = splitShareInfos
        return if (!infos.isNullOrEmpty()) {
            val info = infos[shareIdx0]
            SplitReceiptCalculator.computeByItemsSplit(
                orderDoc,
                itemDocs,
                info.lineKeys.toSet(),
                splitIndex1Based,
                total,
                chargedCents,
                method,
                info.guestLabel
            )
        } else {
            SplitReceiptCalculator.computeEvenSplit(
                orderDoc,
                itemDocs,
                splitIndex1Based,
                total,
                chargedCents,
                method
            )
        }
    }

    private fun executePaymentWithSplitMetadata(
        amountInCents: Long,
        paymentType: String,
        authCode: String = "",
        cardBrand: String = "",
        last4: String = "",
        entryType: String = "",
        referenceId: String = "",
        clientReferenceId: String = "",
        batchNumber: String = "",
        transactionNumber: String = "",
        invoiceNumber: String = "",
        cashTenderedInCents: Long = 0L,
        cashChangeInCents: Long = 0L,
        onSuccess: (Long) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val oid = orderId ?: return
        val bid = batchId?.takeIf { it.isNotBlank() }
        if (bid.isNullOrBlank()) {
            Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
            setButtonsEnabled(true)
            return
        }
        if (!isSplitPayMode()) {
            paymentEngine.processPayment(
                orderId = oid,
                batchId = bid,
                paymentType = paymentType,
                amountInCents = amountInCents,
                authCode = authCode,
                cardBrand = cardBrand,
                last4 = last4,
                entryType = entryType,
                referenceId = referenceId,
                clientReferenceId = clientReferenceId,
                batchNumber = batchNumber,
                transactionNumber = transactionNumber,
                invoiceNumber = invoiceNumber,
                cashTenderedInCents = cashTenderedInCents,
                cashChangeInCents = cashChangeInCents,
                splitReceipt = null,
                onSuccess = onSuccess,
                onFailure = onFailure
            )
            return
        }
        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { orderDoc ->
                db.collection("Orders").document(oid).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val payload = buildSplitReceiptOrNull(
                            orderDoc,
                            itemsSnap.documents,
                            amountInCents,
                            paymentType,
                            entryType
                        )
                        pendingSplitReceiptPayload = payload
                        paymentEngine.processPayment(
                            orderId = oid,
                            batchId = bid,
                            paymentType = paymentType,
                            amountInCents = amountInCents,
                            authCode = authCode,
                            cardBrand = cardBrand,
                            last4 = last4,
                            entryType = entryType,
                            referenceId = referenceId,
                            clientReferenceId = clientReferenceId,
                            batchNumber = batchNumber,
                            transactionNumber = transactionNumber,
                            invoiceNumber = invoiceNumber,
                            cashTenderedInCents = cashTenderedInCents,
                            cashChangeInCents = cashChangeInCents,
                            splitReceipt = payload?.toFirestoreMap(),
                            onSuccess = onSuccess,
                            onFailure = {
                                pendingSplitReceiptPayload = null
                                onFailure(it)
                            }
                        )
                    }
                    .addOnFailureListener {
                        pendingSplitReceiptPayload = null
                        paymentEngine.processPayment(
                            orderId = oid,
                            batchId = bid,
                            paymentType = paymentType,
                            amountInCents = amountInCents,
                            authCode = authCode,
                            cardBrand = cardBrand,
                            last4 = last4,
                            entryType = entryType,
                            referenceId = referenceId,
                            clientReferenceId = clientReferenceId,
                            batchNumber = batchNumber,
                            transactionNumber = transactionNumber,
                            invoiceNumber = invoiceNumber,
                            cashTenderedInCents = cashTenderedInCents,
                            cashChangeInCents = cashChangeInCents,
                            splitReceipt = null,
                            onSuccess = onSuccess,
                            onFailure = onFailure
                        )
                    }
            }
            .addOnFailureListener {
                pendingSplitReceiptPayload = null
                paymentEngine.processPayment(
                    orderId = oid,
                    batchId = bid,
                    paymentType = paymentType,
                    amountInCents = amountInCents,
                    authCode = authCode,
                    cardBrand = cardBrand,
                    last4 = last4,
                    entryType = entryType,
                    referenceId = referenceId,
                    clientReferenceId = clientReferenceId,
                    batchNumber = batchNumber,
                    transactionNumber = transactionNumber,
                    invoiceNumber = invoiceNumber,
                    cashTenderedInCents = cashTenderedInCents,
                    cashChangeInCents = cashChangeInCents,
                    splitReceipt = null,
                    onSuccess = onSuccess,
                    onFailure = onFailure
                )
            }
    }

    private fun dismissSplitReceiptChoiceDialog() {
        splitReceiptChoiceDialog?.dismiss()
        splitReceiptChoiceDialog = null
    }

    private fun showSplitReceiptChoiceAfterPayment(remainingInCents: Long) {
        val payload = pendingSplitReceiptPayload
        pendingSplitReceiptPayload = null
        if (payload == null) {
            advanceSplitAfterReceiptAction(remainingInCents)
            return
        }
        val oid = orderId ?: run {
            advanceSplitAfterReceiptAction(remainingInCents)
            return
        }
        activeSplitReceiptPayload = payload
        activeSplitReceiptRemainingInCents = remainingInCents

        CustomerDisplayManager.showReceiptOptionsOnCustomerDisplay(this) { option ->
            runOnUiThread {
                if (activeSplitReceiptPayload == null) return@runOnUiThread
                applySplitReceiptOption(option, oid)
            }
        }

        val choices = arrayOf("Print Receipt", "Email Receipt", "Text Receipt", "Skip Receipt")
        splitReceiptChoiceDialog = AlertDialog.Builder(this)
            .setTitle("Would you like a receipt?")
            .setItems(choices) { _, which ->
                val opt = when (which) {
                    0 -> ReceiptOption.PRINT
                    1 -> ReceiptOption.EMAIL
                    2 -> ReceiptOption.SMS
                    else -> ReceiptOption.SKIP
                }
                applySplitReceiptOption(opt, oid)
            }
            .setCancelable(false)
            .create()
        splitReceiptChoiceDialog?.show()
    }

    private fun reopenSplitReceiptOffer(oid: String, payload: SplitReceiptPayload, remainingInCents: Long) {
        activeSplitReceiptPayload = payload
        activeSplitReceiptRemainingInCents = remainingInCents
        CustomerDisplayManager.clearEmailInputCallbacks()
        CustomerDisplayManager.showReceiptOptionsOnCustomerDisplay(this) { option ->
            runOnUiThread {
                if (activeSplitReceiptPayload == null) return@runOnUiThread
                applySplitReceiptOption(option, oid)
            }
        }
        val choices = arrayOf("Print Receipt", "Email Receipt", "Text Receipt", "Skip Receipt")
        dismissSplitReceiptChoiceDialog()
        splitReceiptChoiceDialog = AlertDialog.Builder(this)
            .setTitle("Would you like a receipt?")
            .setItems(choices) { _, which ->
                val opt = when (which) {
                    0 -> ReceiptOption.PRINT
                    1 -> ReceiptOption.EMAIL
                    2 -> ReceiptOption.SMS
                    else -> ReceiptOption.SKIP
                }
                applySplitReceiptOption(opt, oid)
            }
            .setCancelable(false)
            .create()
        splitReceiptChoiceDialog?.show()
    }

    private fun applySplitReceiptOption(option: ReceiptOption, oid: String) {
        val payload = activeSplitReceiptPayload ?: return
        val remaining = activeSplitReceiptRemainingInCents
        dismissSplitReceiptChoiceDialog()
        CustomerDisplayManager.clearReceiptOptionCallback()
        CustomerDisplayManager.getPaymentSuccessInfo()?.let {
            CustomerDisplayManager.showPaymentApproved(this, it)
        }
        when (option) {
            ReceiptOption.PRINT -> printSplitReceiptThenContinue(oid, payload, remaining)
            ReceiptOption.EMAIL -> showSplitReceiptEmailEntry(oid, payload, remaining)
            ReceiptOption.SMS -> showSplitReceiptSmsEntry(oid, payload, remaining)
            ReceiptOption.SKIP -> {
                activeSplitReceiptPayload = null
                advanceSplitAfterReceiptAction(remaining)
            }
        }
    }

    private fun showSplitReceiptEmailEntry(oid: String, payload: SplitReceiptPayload, remainingInCents: Long) {
        fun sendAndAdvance(email: String) {
            val e = email.trim()
            if (e.isEmpty()) {
                Toast.makeText(this, "Enter an email address", Toast.LENGTH_SHORT).show()
                return
            }
            CustomerDisplayManager.clearEmailInputCallbacks()
            sendSplitReceiptEmailToAddress(oid, payload, e, remainingInCents)
        }
        if (CustomerDisplayManager.hasCustomerDisplayAttached()) {
            CustomerDisplayManager.showEmailInputOnCustomerDisplay(
                this,
                onSubmit = { em -> runOnUiThread { sendAndAdvance(em) } },
                onCancel = {
                    runOnUiThread {
                        CustomerDisplayManager.clearEmailInputCallbacks()
                        reopenSplitReceiptOffer(oid, payload, remainingInCents)
                    }
                }
            )
            return
        }
        ReceiptEmailKeypadDialog.show(
            this,
            title = "Email receipt",
            message = "Enter the email address for this guest's receipt.",
            hint = "customer@email.com",
            emptyEmailToast = "Enter an email address",
            onSend = { em -> sendAndAdvance(em) },
            onCancel = { reopenSplitReceiptOffer(oid, payload, remainingInCents) }
        )
    }

    private fun sendSplitReceiptEmailToAddress(
        oid: String,
        payload: SplitReceiptPayload,
        email: String,
        remainingInCents: Long
    ) {
        SplitReceiptEmailSender.send(
            this,
            email,
            oid,
            payload,
            onSuccess = {
                activeSplitReceiptPayload = null
                advanceSplitAfterReceiptAction(remainingInCents)
            },
            onFailure = { reopenSplitReceiptOffer(oid, payload, remainingInCents) }
        )
    }

    private fun showSplitReceiptSmsEntry(oid: String, payload: SplitReceiptPayload, remainingInCents: Long) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "+1XXXXXXXXXX"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Text receipt")
            .setMessage("Enter the phone number for this guest's split receipt.")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) {
                    Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show()
                    reopenSplitReceiptOffer(oid, payload, remainingInCents)
                    return@setPositiveButton
                }
                db.collection("Orders").document(oid).get()
                    .addOnSuccessListener { orderDoc ->
                        val body = SplitReceiptRenderer.buildPlainTextBody(this, orderDoc, payload)
                        launchSplitReceiptSmsIntent(raw, body)
                        activeSplitReceiptPayload = null
                        advanceSplitAfterReceiptAction(remainingInCents)
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
                        reopenSplitReceiptOffer(oid, payload, remainingInCents)
                    }
            }
            .setNegativeButton("Cancel") { _, _ ->
                reopenSplitReceiptOffer(oid, payload, remainingInCents)
            }
            .show()
    }

    private fun launchSplitReceiptSmsIntent(phone: String, body: String) {
        var cleaned = phone.replace(Regex("[\\s\\-()]"), "")
        if (cleaned.length == 10 && !cleaned.startsWith("+")) {
            cleaned = "+1$cleaned"
        } else if (cleaned.startsWith("1") && cleaned.length == 11 && !cleaned.startsWith("+")) {
            cleaned = "+$cleaned"
        }
        val uri = Uri.parse("smsto:${Uri.encode(cleaned)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No SMS app available", Toast.LENGTH_LONG).show()
        }
    }

    private fun advanceSplitAfterReceiptAction(remainingInCents: Long) {
        activeSplitReceiptPayload = null
        dismissSplitReceiptChoiceDialog()
        CustomerDisplayManager.clearReceiptOptionCallback()
        CustomerDisplayManager.clearEmailInputCallbacks()
        splitPaymentsCompleted++
        if (remainingInCents > 0L) {
            scheduleNextSplitDialogIfNeeded()
            orderId?.let { oid ->
                db.collection("Orders").document(oid).get()
                    .addOnSuccessListener { snap -> bindOrderSummary(snap) }
            }
        } else {
            clearSplitState()
            onOrderFullyPaid(skipMerchantReceiptScreen = true)
        }
    }

    private fun printSplitReceiptThenContinue(
        oid: String,
        payload: SplitReceiptPayload,
        remainingInCents: Long
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingSplitPrint = PendingSplitPrint(oid, payload, remainingInCents)
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQ_BT_SPLIT_RECEIPT
                )
                return
            }
        }
        runSplitPrintAndContinue(oid, payload, remainingInCents)
    }

    private fun runSplitPrintAndContinue(
        oid: String,
        payload: SplitReceiptPayload,
        remainingInCents: Long
    ) {
        Toast.makeText(this, "Preparing receipt…", Toast.LENGTH_SHORT).show()
        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { orderDoc ->
                val segs = SplitReceiptRenderer.buildEscPosSegments(this, orderDoc, payload)
                EscPosPrinter.print(this, segs, ReceiptSettings.load(this))
                advanceSplitAfterReceiptAction(remainingInCents)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order for print", Toast.LENGTH_SHORT).show()
                advanceSplitAfterReceiptAction(remainingInCents)
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT_SPLIT_RECEIPT) return
        val pending = pendingSplitPrint
        pendingSplitPrint = null
        if (pending == null) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            runSplitPrintAndContinue(pending.oid, pending.payload, pending.remainingInCents)
        } else {
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            advanceSplitAfterReceiptAction(pending.remainingInCents)
        }
    }

    private fun launchCashPayment(amount: Double) {
        if (batchId.isNullOrBlank()) {
            Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
            return
        }
        paymentAmount = amount
        CustomerDisplayManager.showCashPayment(this, businessName, MoneyUtils.dollarsToCents(amount))
        val intent = Intent(this, CashPaymentActivity::class.java)
        intent.putExtra(CashPaymentActivity.EXTRA_AMOUNT_DUE_CENTS, MoneyUtils.dollarsToCents(amount))
        cashPaymentLauncher.launch(intent)
    }

    private fun processFullPayment(paymentType: String) {
        if (batchId.isNullOrBlank()) {
            Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
            return
        }
        paymentAmount = remainingBalance
        showWaitingStatus()

        if (paymentType == "Cash") {
            completePayment(paymentType)
        } else {
            CustomerDisplayManager.showPaymentWaiting(this, businessName, MoneyUtils.dollarsToCents(paymentAmount))
            processCardPayment(paymentType)
        }
    }

    private fun showAmountDialog(paymentType: String) {

        val remainingFormatted = String.format(Locale.US, "%.2f", remainingBalance)

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Enter amount (Remaining: $remainingFormatted)"
        }

        val btnRemaining = Button(this).apply {
            text = "Remaining Balance: $$remainingFormatted"
            isAllCaps = false
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            setPadding(dp8, dp8, dp8, dp8)
            setOnClickListener { input.setText(remainingFormatted) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, 0)
            addView(input)
            addView(btnRemaining, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Amount")
            .setView(container)
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {

            val confirmBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            confirmBtn.setOnClickListener {

                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null ||
                    entered <= 0 ||
                    roundMoney(entered) > remainingBalance
                ) {
                    Toast.makeText(
                        this,
                        "Invalid amount",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                paymentAmount = roundMoney(entered)

                dialog.dismiss()

                if (paymentType == "Cash") {
                    launchCashPayment(paymentAmount)
                } else {
                    showWaitingStatus()
                    CustomerDisplayManager.showPaymentWaiting(this@PaymentActivity, businessName, MoneyUtils.dollarsToCents(paymentAmount))
                    processCardPayment(paymentType)
                }
            }
        }

        dialog.show()
    }

    private fun processCardPayment(paymentType: String) {

        val formattedAmount =
            String.format(Locale.US, "%.2f", paymentAmount)

        val clientReferenceId = UUID.randomUUID().toString()

        val json = JSONObject().apply {
            put("Amount", formattedAmount)
            put("PaymentType", paymentType)
            put("ReferenceId", clientReferenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("Tpn", TerminalPrefs.getTpn(this@PaymentActivity))
            put("RegisterId", TerminalPrefs.getRegisterId(this@PaymentActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@PaymentActivity))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Sale")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showDeclined("Payment Failed") }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""
                Log.d("DEVAVOO_RAW", responseText)

                runOnUiThread {

                    if (!response.isSuccessful) {
                        showDeclined("Server Error")
                        return@runOnUiThread
                    }

                    val jsonObj = JSONObject(responseText)

                    val resultCode = jsonObj
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode", "") ?: ""

                    if (resultCode == "0") {

                        val authCode = jsonObj.optString("AuthCode", "")
                        // Void must use the exact ReferenceId returned by Dejavoo (often UUID format). Do not use PNReferenceId.
                        val referenceId = jsonObj.optString("ReferenceId", "")
                            .ifBlank { jsonObj.optJSONObject("GeneralResponse")?.optString("ReferenceId", "") ?: "" }
                            .ifBlank { jsonObj.optJSONObject("Transaction")?.optString("ReferenceId", "") ?: "" }
                            .ifBlank { clientReferenceId }
                        val batchNumber = jsonObj.optString("BatchNumber", "")
                            .ifBlank { jsonObj.optJSONObject("GeneralResponse")?.optString("BatchNumber", "") ?: "" }
                        val transactionNumber = jsonObj.optString("TransactionNumber", "")
                            .ifBlank { jsonObj.optJSONObject("GeneralResponse")?.optString("TransactionNumber", "") ?: "" }
                        val invoiceNumber = jsonObj.optString("InvoiceNumber", "")

                        Log.d("PAYMENT_SALE", "ReferenceId(saved for Void)=$referenceId BatchNumber=$batchNumber TransactionNumber=$transactionNumber topLevelRef=${jsonObj.optString("ReferenceId", "")} PNRef=${jsonObj.optString("PNReferenceId", "")}")

                        val cardData = jsonObj.optJSONObject("CardData")
                        val cardBrand = cardData?.optString("CardType", "") ?: ""
                        val entryType = cardData?.optString("EntryType", "") ?: ""
                        val last4 = cardData?.optString("Last4", "") ?: ""

                        completeCardPayment(
                            paymentType = paymentType,
                            authCode = authCode,
                            cardBrand = cardBrand,
                            last4 = last4,
                            entryType = entryType,
                            referenceId = referenceId,
                            clientReferenceId = clientReferenceId,
                            batchNumber = batchNumber,
                            transactionNumber = transactionNumber,
                            invoiceNumber = invoiceNumber
                        )

                    } else {
                        showDeclined("Declined")
                    }
                }
            }
        })
    }
    private fun completeCardPayment(
        paymentType: String,
        authCode: String,
        cardBrand: String,
        last4: String,
        entryType: String,
        referenceId: String,
        clientReferenceId: String,
        batchNumber: String,
        transactionNumber: String,
        invoiceNumber: String = ""
    ) {

        val oid = orderId ?: return
        val bid = batchId?.takeIf { it.isNotBlank() }
        if (bid.isNullOrBlank()) {
            Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
            setButtonsEnabled(true)
            return
        }
        val amountInCents = MoneyUtils.dollarsToCents(paymentAmount)

        executePaymentWithSplitMetadata(
            amountInCents = amountInCents,
            paymentType = paymentType,
            authCode = authCode,
            cardBrand = cardBrand,
            last4 = last4,
            entryType = entryType,
            referenceId = referenceId,
            clientReferenceId = clientReferenceId,
            batchNumber = batchNumber,
            transactionNumber = transactionNumber,
            invoiceNumber = invoiceNumber,
            onSuccess = { remainingInCents ->

                runOnUiThread {
                    showApproved(paymentType)
                    remainingBalance = MoneyUtils.centsToDouble(remainingInCents)

                    if (isSplitPayMode()) {
                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)
                        setButtonsEnabled(true)
                        showSplitReceiptChoiceAfterPayment(remainingInCents)
                    } else if (remainingInCents <= 0L) {
                        clearSplitState()
                        Handler(Looper.getMainLooper()).postDelayed({ onOrderFullyPaid() }, 2000)
                    } else {
                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)
                        setButtonsEnabled(true)
                    }
                }
            },
            onFailure = {
                pendingSplitReceiptPayload = null
                runOnUiThread {
                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    setButtonsEnabled(true)
                }
            }
        )
    }

    private fun completePayment(paymentType: String) {

        val oid = orderId ?: return
        val bid = batchId?.takeIf { it.isNotBlank() }
        if (bid.isNullOrBlank()) {
            Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
            setButtonsEnabled(true)
            return
        }
        val amountInCents = MoneyUtils.dollarsToCents(paymentAmount)

        val tenderedCents = if (paymentType == "Cash") lastCashTenderedCents else 0L
        val changeCents = if (paymentType == "Cash") lastCashChangeCents else 0L

        executePaymentWithSplitMetadata(
            amountInCents = amountInCents,
            paymentType = paymentType,
            cashTenderedInCents = tenderedCents,
            cashChangeInCents = changeCents,
            onSuccess = { remainingInCents ->

                CashDrawerManager.openCashDrawerIfCash(this, paymentType)

                runOnUiThread {

                    showApproved(paymentType)

                    remainingBalance = MoneyUtils.centsToDouble(remainingInCents)

                    if (isSplitPayMode()) {
                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)
                        progressBar.visibility = View.GONE
                        setButtonsEnabled(true)
                        showSplitReceiptChoiceAfterPayment(remainingInCents)
                    } else if (remainingInCents <= 0L) {
                        clearSplitState()
                        onOrderFullyPaid()
                    } else {
                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)
                        progressBar.visibility = View.GONE
                        setButtonsEnabled(true)
                    }
                }
            },
            onFailure = {
                pendingSplitReceiptPayload = null
                runOnUiThread {
                    showDeclined(it.message ?: "Payment failed")
                    setButtonsEnabled(true)
                }
            }
        )
    }
    private fun showApproved(paymentType: String) {
        progressBar.visibility = View.GONE
        txtStatus.text = "APPROVED ✅"
        txtStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
        txtSubStatus.text = "Transaction successful"
        fireworksView.launch()
        val chargedCents = MoneyUtils.dollarsToCents(paymentAmount)
        val info = PaymentSuccessInfo(
            isCash = paymentType == "Cash",
            amountChargedCents = chargedCents,
            tenderedCents = if (paymentType == "Cash") lastCashTenderedCents else 0L,
            changeCents = if (paymentType == "Cash") lastCashChangeCents else 0L
        )
        CustomerDisplayManager.setPaymentSuccessInfo(info)
        CustomerDisplayManager.showPaymentApproved(this, info)
    }
    private fun showWaitingStatus() {
        statusContainer.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        txtStatus.text = "Waiting..."
        txtSubStatus.text = "Processing payment"
        setButtonsEnabled(false)
    }

    private fun showDeclined(message: String) {
        progressBar.visibility = View.GONE
        txtStatus.text = "DECLINED ❌"
        txtStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
        txtSubStatus.text = message
        setButtonsEnabled(true)
        CustomerDisplayManager.showDeclinedThenOrder(this, message, 2500L)

        Handler(Looper.getMainLooper()).postDelayed({
            statusContainer.visibility = View.GONE
        }, 2500)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnCredit.isEnabled = enabled
        btnDebit.isEnabled = enabled
        btnCash.isEnabled = enabled
    }

    private fun onOrderFullyPaid(skipMerchantReceiptScreen: Boolean = false) {
        setResult(RESULT_OK)
        val oid = orderId ?: run { finish(); return }
        if (skipMerchantReceiptScreen) {
            CustomerDisplayManager.clearPaymentSuccessInfo()
            CustomerDisplayManager.clearReceiptOptionCallback()
            CustomerDisplayManager.clearEmailInputCallbacks()
            CustomerDisplayManager.setIdle(this, ReceiptSettings.load(this).businessName)
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        val intent = Intent(this, ReceiptOptionsActivity::class.java).apply {
            putExtra("ORDER_ID", oid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        dismissSplitReceiptChoiceDialog()
        CustomerDisplayManager.clearReceiptOptionCallback()
        CustomerDisplayManager.clearEmailInputCallbacks()
        super.onDestroy()
    }

    private var availableManualDiscounts: List<DiscountItem> = emptyList()

    private fun loadManualDiscounts() {
        discountEngine.loadDiscounts {
            availableManualDiscounts = discountEngine.getAvailableManualDiscounts()
            btnDiscounts.visibility = if (availableManualDiscounts.isNotEmpty()) View.VISIBLE else View.GONE
            updateOtherSectionVisibility()
        }
    }

    private fun updateOtherSectionVisibility() {
        val anyOther = btnMixMode.visibility == View.VISIBLE
                || btnSplitPayments.visibility == View.VISIBLE
                || btnDiscounts.visibility == View.VISIBLE
        labelOther.visibility = if (anyOther) View.VISIBLE else View.GONE
    }

    private fun showDiscountsDialog() {
        if (availableManualDiscounts.isEmpty()) {
            Toast.makeText(this, "No discounts available", Toast.LENGTH_SHORT).show()
            return
        }

        val names = availableManualDiscounts.map { d ->
            val valueStr = if (d.type == "PERCENTAGE") {
                String.format(Locale.US, "%.1f%% off", d.value)
            } else {
                String.format(Locale.US, "$%.2f off", d.value)
            }
            "${d.name}  —  $valueStr"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select a Discount")
            .setItems(names) { _, which ->
                applyManualDiscount(availableManualDiscounts[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyManualDiscount(discount: DiscountItem) {
        val oid = orderId ?: return

        if (selectedManualDiscountId == discount.id) {
            Toast.makeText(this, "Discount already applied", Toast.LENGTH_SHORT).show()
            return
        }
        selectedManualDiscountId = discount.id

        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { doc ->
                val currentTotal = doc.getLong("totalInCents") ?: 0L
                val currentDiscount = doc.getLong("discountInCents") ?: 0L
                val subtotalBeforeDiscount = currentTotal + currentDiscount

                val discountCents = when (discount.type) {
                    "PERCENTAGE" -> ((subtotalBeforeDiscount * discount.value) / 100.0).toLong()
                    "FIXED" -> (discount.value * 100).toLong().coerceAtMost(subtotalBeforeDiscount)
                    else -> 0L
                }

                val newTotal = (subtotalBeforeDiscount - discountCents).coerceAtLeast(0L)
                val totalPaid = doc.getLong("totalPaidInCents") ?: 0L
                val newRemaining = (newTotal - totalPaid).coerceAtLeast(0L)

                val discountInfo = listOf(
                    mapOf(
                        "discountId" to discount.id,
                        "discountName" to discount.name,
                        "type" to discount.type,
                        "value" to discount.value,
                        "applyScope" to "manual",
                        "amountInCents" to discountCents,
                        "lineKey" to ""
                    )
                )

                db.collection("Orders").document(oid).update(
                    mapOf(
                        "totalInCents" to newTotal,
                        "remainingInCents" to newRemaining,
                        "discountInCents" to discountCents,
                        "appliedDiscounts" to discountInfo,
                        "updatedAt" to Date()
                    )
                ).addOnSuccessListener {
                    remainingBalance = MoneyUtils.centsToDouble(newRemaining)
                    txtPaymentTotal.text = MoneyUtils.centsToDisplay(newRemaining)

                    val valueStr = if (discount.type == "PERCENTAGE") {
                        String.format(Locale.US, "%.1f%%", discount.value)
                    } else {
                        MoneyUtils.centsToDisplay((discount.value * 100).toLong())
                    }
                    txtAppliedDiscount.text = "${discount.name} ($valueStr): -${MoneyUtils.centsToDisplay(discountCents)}"
                    appliedDiscountBanner.visibility = View.VISIBLE
                    btnDiscounts.visibility = View.GONE
                    updateOtherSectionVisibility()

                    Toast.makeText(this, "${discount.name} applied", Toast.LENGTH_SHORT).show()
                    loadRemainingBalance()
                }.addOnFailureListener { e ->
                    selectedManualDiscountId = null
                    Toast.makeText(this, "Failed to apply discount: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun removeAppliedDiscount() {
        val oid = orderId ?: return
        val discountId = selectedManualDiscountId ?: return

        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { doc ->
                val currentTotal = doc.getLong("totalInCents") ?: 0L
                val currentDiscount = doc.getLong("discountInCents") ?: 0L
                val restoredTotal = currentTotal + currentDiscount
                val totalPaid = doc.getLong("totalPaidInCents") ?: 0L
                val newRemaining = (restoredTotal - totalPaid).coerceAtLeast(0L)

                db.collection("Orders").document(oid).update(
                    mapOf(
                        "totalInCents" to restoredTotal,
                        "remainingInCents" to newRemaining,
                        "discountInCents" to 0L,
                        "appliedDiscounts" to emptyList<Map<String, Any>>(),
                        "updatedAt" to Date()
                    )
                ).addOnSuccessListener {
                    selectedManualDiscountId = null
                    remainingBalance = MoneyUtils.centsToDouble(newRemaining)
                    txtPaymentTotal.text = MoneyUtils.centsToDisplay(newRemaining)
                    appliedDiscountBanner.visibility = View.GONE
                    btnDiscounts.visibility = if (availableManualDiscounts.isNotEmpty()) View.VISIBLE else View.GONE
                    updateOtherSectionVisibility()
                    Toast.makeText(this, "Discount removed", Toast.LENGTH_SHORT).show()
                    loadRemainingBalance()
                }
            }
    }

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}