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
    private var tipScreenShown = false

    private val tipLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            loadRemainingBalance()
        }

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

        orderId = intent.getStringExtra("ORDER_ID")
        batchId = intent.getStringExtra("BATCH_ID")
        ensureBatchIdThenLoadBalance()

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

        val anyOther = btnMixMode.visibility == View.VISIBLE || btnSplitPayments.visibility == View.VISIBLE
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

                if (maybeLaunchTipScreen()) return@addOnSuccessListener
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

                        if (maybeLaunchTipScreen()) return@addOnSuccessListener
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

        txtOrderSummary.text = parts.joinToString(" • ")
        txtOrderSummary.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun maybeLaunchTipScreen(): Boolean {
        if (tipScreenShown) return false
        if (!TipConfig.isTipsEnabled(this)) return false

        val splitAmount = intent.getDoubleExtra("SPLIT_PAY_AMOUNT", -1.0)
        if (splitAmount > 0) return false

        tipScreenShown = true
        val tipIntent = Intent(this, TipActivity::class.java)
        tipIntent.putExtra("ORDER_ID", orderId)
        tipLauncher.launch(tipIntent)
        return true
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
        txtSubStatus.text = message
        setButtonsEnabled(true)
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

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}