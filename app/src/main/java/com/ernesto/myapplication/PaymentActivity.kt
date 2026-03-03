package com.ernesto.myapplication

import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
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
class PaymentActivity : AppCompatActivity() {

    private var isMixMode = false

    private lateinit var btnMixMode: Button
    private lateinit var btnCredit: Button
    private lateinit var btnDebit: Button
    private lateinit var btnCash: Button

    private lateinit var txtPaymentTotal: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var txtSubStatus: TextView
    private lateinit var txtStatus: TextView

    private var orderId: String? = null
    private var remainingBalance = 0.0
    private var paymentAmount = 0.0

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

        statusContainer = findViewById(R.id.statusContainer)
        progressBar = findViewById(R.id.progressBar)
        txtSubStatus = findViewById(R.id.txtSubStatus)
        txtStatus = findViewById(R.id.txtStatus)

        orderId = intent.getStringExtra("ORDER_ID")

        loadRemainingBalance()

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
    }

    private fun loadRemainingBalance() {
        val oid = orderId ?: return

        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { snap ->

                val remainingInCents =
                    snap.getLong("remainingInCents")
                        ?: snap.getLong("totalInCents")
                        ?: 0L

                remainingBalance = remainingInCents / 100.0

                txtPaymentTotal.text =
                    String.format(Locale.US, "Remaining: $%.2f", remainingBalance)
            }
    }

    private fun processFullPayment(paymentType: String) {
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

        val referenceId = UUID.randomUUID().toString()

        val json = JSONObject().apply {
            put("Amount", formattedAmount)
            put("PaymentType", paymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("Tpn", "11881706541A")
            put("RegisterId", "134909005")
            put("Authkey", "Qt9N7CxhDs")
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

                        // ✅ Extract correct fields from YOUR real response

                        val authCode = jsonObj.optString("AuthCode", "")
                        val terminalReference = jsonObj.optString("PNReferenceId", "")

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
                            terminalReference = terminalReference
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
        terminalReference: String
    ) {

        val oid = orderId ?: return
        val amountInCents = (paymentAmount * 100).toLong()

        paymentEngine.processPayment(
            orderId = oid,
            paymentType = paymentType,
            amountInCents = amountInCents,
            authCode = authCode,
            cardBrand = cardBrand,
            last4 = last4,
            entryType = entryType,
            terminalReference = terminalReference,
            onSuccess = { remainingInCents ->

                remainingBalance = remainingInCents / 100.0

                if (remainingInCents <= 0L) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    txtPaymentTotal.text =
                        String.format(Locale.US, "Remaining: $%.2f", remainingBalance)
                    setButtonsEnabled(true)
                }
            },
            onFailure = {
                Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                setButtonsEnabled(true)
            }
        )
    }
    private fun completePayment(paymentType: String) {

        val oid = orderId ?: return
        val amountInCents = (paymentAmount * 100).toLong()

        paymentEngine.processPayment(
            orderId = oid,
            paymentType = paymentType,
            amountInCents = amountInCents,
            onSuccess = { remainingInCents ->

                runOnUiThread {

                    showApproved()

                    remainingBalance = remainingInCents / 100.0

                    if (remainingInCents <= 0L) {
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        txtPaymentTotal.text =
                            String.format(Locale.US, "Remaining: $%.2f", remainingBalance)

                        progressBar.visibility = View.GONE
                        setButtonsEnabled(true)
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

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}