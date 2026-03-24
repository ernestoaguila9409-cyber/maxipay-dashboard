package com.ernesto.myapplication

import android.content.Intent
import android.os.*
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale
import com.ernesto.myapplication.engine.DiscountEngine
import com.ernesto.myapplication.engine.PaymentEngine
import android.util.Log
import com.ernesto.myapplication.engine.MoneyUtils

class PaymentActivity : AppCompatActivity() {

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

    private var splitPayAmount = -1.0
    private var splitTotalCount = 0
    private var splitPaymentsDone = 0

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
        val dp = resources.displayMetrics.density

        val totalInCents = orderSnap.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderSnap.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderSnap.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderSnap.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()

        var taxTotalCents = 0L
        for (entry in taxBreakdown) {
            taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
        }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        db.collection("Orders").document(oid)
            .collection("items").get()
            .addOnSuccessListener { itemsSnap ->
                container.removeAllViews()

                for (doc in itemsSnap.documents) {
                    val name = doc.getString("name")
                        ?: doc.getString("itemName")
                        ?: "Item"
                    val qty = (doc.getLong("qty")
                        ?: doc.getLong("quantity")
                        ?: 1L).toInt()
                    val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
                    val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents

                    val itemLabel = if (qty > 1) "${qty}x $name" else name
                    val itemPriceStr = if (basePriceCents > 0L) MoneyUtils.centsToDisplay(lineTotalCents) else ""
                    container.addView(makeSummaryRow(itemLabel, itemPriceStr, 13f, 0xDDFFFFFF.toInt()))

                    val mods = doc.get("modifiers") as? List<*> ?: emptyList<Any>()
                    for (m in mods) {
                        val map = m as? Map<*, *> ?: continue
                        val action = map["action"]?.toString() ?: "ADD"
                        val modName = map["name"]?.toString()
                            ?: map["first"]?.toString()
                            ?: continue
                        val modPrice = (map["price"] as? Number)?.toDouble()
                            ?: (map["second"] as? Number)?.toDouble()
                            ?: 0.0
                        val modCents = kotlin.math.round(modPrice * 100).toLong()

                        val label = if (action == "REMOVE") "  NO $modName"
                        else if (modCents > 0) "  + $modName"
                        else "  + $modName"

                        val priceStr = if (action != "REMOVE" && modCents > 0) MoneyUtils.centsToDisplay(modCents) else ""
                        container.addView(makeSummaryRow(label, priceStr, 11f, 0x99FFFFFF.toInt()))
                    }
                }

                val totalsContainer = findViewById<LinearLayout>(R.id.totalsSummaryContainer)
                totalsContainer?.removeAllViews()

                totalsContainer?.addView(makeSummaryRow("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), 13f, 0xBBFFFFFF.toInt()))

                if (discountInCents > 0L) {
                    totalsContainer?.addView(makeSummaryRow("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", 13f, 0xFF81C784.toInt()))
                }

                for (entry in taxBreakdown) {
                    val taxName = entry["name"]?.toString() ?: "Tax"
                    val taxCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                    totalsContainer?.addView(makeSummaryRow(taxName, MoneyUtils.centsToDisplay(taxCents), 12f, 0xAAFFFFFF.toInt()))
                }

                if (tipAmountInCents > 0L) {
                    totalsContainer?.addView(makeSummaryRow("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), 13f, 0xBBFFFFFF.toInt()))
                }
            }
    }

    private fun makeSummaryRow(left: String, right: String, size: Float, color: Int): LinearLayout {
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
        if (amount <= 0) return
        intent.removeExtra("SPLIT_PAY_AMOUNT")
        val totalCount = intent.getIntExtra("SPLIT_TOTAL_COUNT", 0)
        intent.removeExtra("SPLIT_TOTAL_COUNT")

        splitPayAmount = amount
        splitTotalCount = totalCount.coerceAtLeast(1)
        splitPaymentsDone = 0
        showSplitPayShareDialog(amount, 1)
    }

    private fun showSplitPayShareDialog(amount: Double, shareNumber: Int) {
        val title = if (splitTotalCount > 1) "Pay your share ($shareNumber of $splitTotalCount)" else "Pay your share"
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
                processCardPayment("Credit")
            }
            .setNeutralButton("Debit") { _, _ ->
                paymentAmount = actualAmount
                showWaitingStatus()
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
        if (remainingBalance <= 0 || splitPayAmount <= 0) {
            splitPayAmount = -1.0
            splitTotalCount = 0
            splitPaymentsDone = 0
            return
        }
        splitPaymentsDone++
        val nextShare = splitPaymentsDone + 1
        val amount = minOf(splitPayAmount, roundMoney(remainingBalance))
        if (amount <= 0) return
        val title = if (nextShare <= splitTotalCount) {
            "Pay your share ($nextShare of $splitTotalCount)"
        } else {
            "Pay remaining balance"
        }
        Handler(Looper.getMainLooper()).postDelayed({
            showSplitPayShareDialogWithTitle(amount, title)
        }, 1500)
    }

    private fun launchCashPayment(amount: Double) {
        if (batchId.isNullOrBlank()) {
            Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
            return
        }
        paymentAmount = amount
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
            onSuccess = { remainingInCents ->

                runOnUiThread {
                    showApproved()
                    remainingBalance = MoneyUtils.centsToDouble(remainingInCents)

                    if (remainingInCents <= 0L) {
                        splitPayAmount = -1.0
                        splitTotalCount = 0
                        splitPaymentsDone = 0
                        Handler(Looper.getMainLooper()).postDelayed({ onOrderFullyPaid() }, 2000)
                    } else {
                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)
                        setButtonsEnabled(true)
                        scheduleNextSplitDialogIfNeeded()
                    }
                }
            },
            onFailure = {
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

        paymentEngine.processPayment(
            orderId = oid,
            batchId = bid,
            paymentType = paymentType,
            amountInCents = amountInCents,
            cashTenderedInCents = tenderedCents,
            cashChangeInCents = changeCents,
            onSuccess = { remainingInCents ->

                runOnUiThread {

                    showApproved()

                    remainingBalance = MoneyUtils.centsToDouble(remainingInCents)

                    if (remainingInCents <= 0L) {
                        splitPayAmount = -1.0
                        splitTotalCount = 0
                        splitPaymentsDone = 0
                        onOrderFullyPaid()
                    } else {
                        txtPaymentTotal.text = MoneyUtils.centsToDisplay(remainingInCents)
                        progressBar.visibility = View.GONE
                        setButtonsEnabled(true)
                        scheduleNextSplitDialogIfNeeded()
                    }
                }
            },
            onFailure = {
                runOnUiThread {
                    showDeclined(it.message ?: "Payment failed")
                    setButtonsEnabled(true)
                }
            }
        )
    }
    private fun showApproved() {
        progressBar.visibility = View.GONE
        txtStatus.text = "APPROVED ✅"
        txtStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
        txtSubStatus.text = "Transaction successful"
        fireworksView.launch()
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

        Handler(Looper.getMainLooper()).postDelayed({
            statusContainer.visibility = View.GONE
        }, 2500)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnCredit.isEnabled = enabled
        btnDebit.isEnabled = enabled
        btnCash.isEnabled = enabled
    }

    private fun onOrderFullyPaid() {
        setResult(RESULT_OK)
        val oid = orderId ?: run { finish(); return }
        val intent = Intent(this, ReceiptOptionsActivity::class.java).apply {
            putExtra("ORDER_ID", oid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
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
                }
            }
    }

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}