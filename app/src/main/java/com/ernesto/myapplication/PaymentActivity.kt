package com.ernesto.myapplication

import android.os.*
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale

class PaymentActivity : AppCompatActivity() {

    private lateinit var statusContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var txtSubStatus: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtPaymentTotal: TextView
    private lateinit var btnCredit: Button
    private lateinit var btnDebit: Button
    private lateinit var btnCash: Button
    private var orderId: String? = null
    private var totalAmount = 0.0
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        txtPaymentTotal = findViewById(R.id.txtPaymentTotal)
        btnCredit = findViewById(R.id.btnCredit)
        btnDebit = findViewById(R.id.btnDebit)
        btnCash = findViewById(R.id.btnCash)

        statusContainer = findViewById(R.id.statusContainer)
        progressBar = findViewById(R.id.progressBar)
        txtSubStatus = findViewById(R.id.txtSubStatus)
        txtStatus = findViewById(R.id.txtStatus)

        totalAmount = intent.getDoubleExtra("TOTAL_AMOUNT", 0.0)

        orderId = intent.getStringExtra("ORDER_ID")
        Toast.makeText(this, "ORDER_ID: $orderId", Toast.LENGTH_LONG).show()
        txtPaymentTotal.text =
            String.format(Locale.US, "Total: $%.2f", totalAmount)

        btnCredit.setOnClickListener {
            showWaitingStatus()
            processCardPayment("Credit")
        }

        btnDebit.setOnClickListener {
            showWaitingStatus()
            processCardPayment("Debit")
        }

        btnCash.setOnClickListener {
            showWaitingStatus()
            processCashPayment()
        }
    }

    // ===============================
    // UI STATES
    // ===============================

    private fun showWaitingStatus() {
        statusContainer.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE

        txtStatus.text = "Waiting for card..."
        txtStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        txtSubStatus.text = "Please present card on terminal"

        setButtonsEnabled(false)
    }

    private fun showApproved() {
        progressBar.visibility = View.GONE

        txtStatus.text = "APPROVED ✅"
        txtStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        txtSubStatus.text = "Transaction successful"

        Handler(Looper.getMainLooper()).postDelayed({
            setResult(RESULT_OK)
            finish()
        }, 1500)
    }

    private fun showDeclined(message: String = "Please try again") {
        progressBar.visibility = View.GONE

        txtStatus.text = "DECLINED ❌"
        txtStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        txtSubStatus.text = message

        setButtonsEnabled(true)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnCredit.isEnabled = enabled
        btnDebit.isEnabled = enabled
        btnCash.isEnabled = enabled
    }

    // ===============================
    // CARD PROCESSING
    // ===============================

    private fun processCardPayment(paymentType: String) {

        val formattedAmount =
            String.format(Locale.US, "%.2f", totalAmount)

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

        sendApiRequest(json.toString(), paymentType, referenceId)
    }

    private fun sendApiRequest(
        json: String,
        paymentType: String,
        referenceId: String
    ) {

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Sale")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showDeclined("Payment Failed")
                    Toast.makeText(
                        this@PaymentActivity,
                        e.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""
                Log.d("SPINPOS_RESPONSE", responseText)

                runOnUiThread {

                    if (!response.isSuccessful) {
                        showDeclined("Server Error")
                        return@runOnUiThread
                    }

                    val jsonObj = JSONObject(responseText)

                    val generalResponse = jsonObj.optJSONObject("GeneralResponse")
                    val message = generalResponse?.optString("Message", "") ?: ""
                    val resultCode = generalResponse?.optString("ResultCode", "") ?: ""

                    if (resultCode == "0" && message.equals("Approved", true)) {

                        val authCode =
                            jsonObj.optString("AuthCode")

                        val invoiceNumber =
                            jsonObj.optString("InvoiceNumber")

                        val cardData =
                            jsonObj.optJSONObject("CardData")

                        val cardType =
                            cardData?.optString("CardType") ?: ""

                        val last4 =
                            cardData?.optString("Last4") ?: ""

                        val entryType =
                            cardData?.optString("EntryType") ?: ""

                        saveTransaction(
                            paymentType,
                            referenceId,
                            cardType,
                            last4,
                            entryType,
                            authCode,
                            invoiceNumber
                        )

                        showApproved()

                    } else {
                        showDeclined("Transaction Declined")
                    }
                }
            }
        })
    }

    // ===============================
    // CASH PROCESSING
    // ===============================
    private fun getOrCreateOpenBatch(onResult: (String) -> Unit) {

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { docs ->

                if (!docs.isEmpty) {
                    // ✅ Reuse existing open batch
                    onResult(docs.documents[0].id)
                    return@addOnSuccessListener
                }

                // ✅ No open batch → create new one
                val newBatchId = "BATCH_${System.currentTimeMillis()}"

                val batchMap = hashMapOf(
                    "batchId" to newBatchId,
                    "createdAt" to Date(),
                    "closed" to false,
                    "closedAt" to null,
                    "totalSales" to 0.0,
                    "count" to 0
                )

                db.collection("Batches")
                    .document(newBatchId)
                    .set(batchMap)
                    .addOnSuccessListener {
                        onResult(newBatchId)
                    }
            }
    }
    private fun processCashPayment() {

        val referenceId = UUID.randomUUID().toString()

        saveTransaction(
            paymentType = "Cash",
            referenceId = referenceId,
            cardBrand = "",
            last4 = "",
            entryType = "Cash",
            authCode = "",
            invoiceNumber = ""
        )

        showApproved()
    }

    // ===============================
    // SAVE TRANSACTION
    // ===============================

    private fun saveTransaction(
        paymentType: String,
        referenceId: String,
        cardBrand: String,
        last4: String,
        entryType: String,
        authCode: String,
        invoiceNumber: String
    ) {

        val transactionMap = hashMapOf(
            "referenceId" to referenceId,
            "amount" to totalAmount,
            "type" to "SALE",
            "paymentType" to paymentType,
            "cardBrand" to cardBrand,
            "last4" to last4,
            "entryType" to entryType,
            "authCode" to authCode,
            "invoiceNumber" to invoiceNumber,
            "voided" to false,
            "settled" to false,
            "timestamp" to Date()
        )

        db.collection("Transactions")
            .add(transactionMap)
            .addOnSuccessListener { transactionDoc ->

                // ✅ CLOSE THE ORDER
                orderId?.let { oid ->

                    getOrCreateOpenBatch { batchId ->

                        db.collection("Orders")
                            .document(oid)
                            .update(
                                mapOf(
                                    "status" to "CLOSED",
                                    "closedAt" to Date(),
                                    "paymentType" to paymentType,
                                    "total" to totalAmount,
                                    "transactionId" to transactionDoc.id,
                                    "batchId" to batchId
                                )
                            )

                        // Optional: update batch totals
                        db.collection("Batches")
                            .document(batchId)
                            .update(
                                "totalSales",
                                com.google.firebase.firestore.FieldValue.increment(totalAmount)
                            )
                    }
                }
            }
            .addOnFailureListener {
                Log.e("FIRESTORE", "Failed to save transaction: ${it.message}")
            }
    }
}