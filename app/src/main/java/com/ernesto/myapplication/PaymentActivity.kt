package com.ernesto.myapplication

import android.content.Intent
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var btnMixMode: Button
    private lateinit var btnSplitPayments: Button
    private lateinit var btnCredit: Button
    private lateinit var btnDebit: Button
    private lateinit var btnCash: Button

    private lateinit var txtPaymentTotal: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var txtSubStatus: TextView
    private lateinit var txtStatus: TextView

    private var orderId: String? = null
    private var batchId: String? = null
    private var remainingBalance = 0.0
    private var paymentAmount = 0.0

    private var splitPayAmount = -1.0
    private var splitTotalCount = 0
    private var splitPaymentsDone = 0

    private val db = FirebaseFirestore.getInstance()
    private lateinit var paymentEngine: PaymentEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        paymentEngine = PaymentEngine(db)

        txtPaymentTotal = findViewById(R.id.txtPaymentTotal)
        btnCredit = findViewById(R.id.btnCredit)
        btnDebit = findViewById(R.id.btnDebit)
        btnCash = findViewById(R.id.btnCash)
        btnMixMode = findViewById(R.id.btnMixMode)
        btnSplitPayments = findViewById(R.id.btnSplitPayments)

        statusContainer = findViewById(R.id.statusContainer)
        progressBar = findViewById(R.id.progressBar)
        txtSubStatus = findViewById(R.id.txtSubStatus)
        txtStatus = findViewById(R.id.txtStatus)

        orderId = intent.getStringExtra("ORDER_ID")
        batchId = intent.getStringExtra("BATCH_ID")
        ensureBatchIdThenLoadBalance()

        updateMixPaymentsVisibility()

        btnMixMode.setOnClickListener {
            isMixMode = !isMixMode
            btnMixMode.text =
                if (isMixMode) "MIX MODE ON"
                else "MIX PAYMENTS"
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
            else processFullPayment("Cash")
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
        btnMixMode.visibility = if (MixPaymentsConfig.isEnabled(this)) View.VISIBLE else View.GONE
        btnCredit.visibility = if (PaymentMethodsConfig.isCreditEnabled(this)) View.VISIBLE else View.GONE
        btnDebit.visibility = if (PaymentMethodsConfig.isDebitEnabled(this)) View.VISIBLE else View.GONE
        btnCash.visibility = if (PaymentMethodsConfig.isCashEnabled(this)) View.VISIBLE else View.GONE
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

                txtPaymentTotal.text =
                    "Remaining: ${MoneyUtils.centsToDisplay(remainingInCents)}"

                showSplitPayShareDialogIfNeeded()
            }
            .addOnFailureListener {
                db.collection("Orders").document(oid).get()
                    .addOnSuccessListener { snap ->
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

                        txtPaymentTotal.text =
                            "Remaining: ${MoneyUtils.centsToDisplay(remainingInCents)}"

                        showSplitPayShareDialogIfNeeded()
                    }
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
                showWaitingStatus()
                completePayment("Cash")
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

        val input = EditText(this)
        input.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        input.hint =
            "Enter amount (Remaining: ${String.format(Locale.US, "%.2f", remainingBalance)})"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Amount")
            .setView(input)
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

                dialog.dismiss()  // 🔥 CLOSES DIALOG BEFORE PROCESSING

                showWaitingStatus()

                if (paymentType == "Cash") {
                    completePayment("Cash")
                } else {
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
        val amountInCents = (paymentAmount * 100).toLong()

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
                    remainingBalance = MoneyUtils.centsToDouble(remainingInCents)

                    if (remainingInCents <= 0L) {
                        splitPayAmount = -1.0
                        splitTotalCount = 0
                        splitPaymentsDone = 0
                        onOrderFullyPaid()
                    } else {
                        txtPaymentTotal.text =
                            "Remaining: ${MoneyUtils.centsToDisplay(remainingInCents)}"
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
        val amountInCents = (paymentAmount * 100).toLong()

        paymentEngine.processPayment(
            orderId = oid,
            batchId = bid,
            paymentType = paymentType,
            amountInCents = amountInCents,
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

                        txtPaymentTotal.text =
                            "Remaining: ${MoneyUtils.centsToDisplay(remainingInCents)}"

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
        txtSubStatus.text = "Transaction successful"
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