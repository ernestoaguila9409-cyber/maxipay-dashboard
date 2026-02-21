package com.ernesto.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale

class PaymentActivity : AppCompatActivity() {

    private lateinit var txtPaymentTotal: TextView
    private lateinit var btnCredit: Button
    private lateinit var btnDebit: Button
    private lateinit var btnCash: Button

    private var totalAmount = 0.0
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        txtPaymentTotal = findViewById(R.id.txtPaymentTotal)
        btnCredit = findViewById(R.id.btnCredit)
        btnDebit = findViewById(R.id.btnDebit)
        btnCash = findViewById(R.id.btnCash)

        totalAmount = intent.getDoubleExtra("TOTAL_AMOUNT", 0.0)

        txtPaymentTotal.text =
            String.format(Locale.US, "Total: $%.2f", totalAmount)

        btnCredit.setOnClickListener {
            processCardPayment("Credit")
        }

        btnDebit.setOnClickListener {
            processCardPayment("Debit")
        }

        btnCash.setOnClickListener {
            processCashPayment()
        }
    }

    // ===============================
    // CREDIT / DEBIT PROCESSING
    // ===============================
    private fun processCardPayment(paymentType: String) {

        val formattedAmount = String.format(Locale.US, "%.2f", totalAmount)
        val referenceId = UUID.randomUUID().toString()

        val json = """
        {
          "Amount": "$formattedAmount",
          "PaymentType": "$paymentType",
          "ReferenceId": "$referenceId",
          "PrintReceipt": "No",
          "GetReceipt": "No",
          "Tpn": "11881706541A",
          "RegisterId": "134909005",
          "Authkey": "Qt9N7CxhDs"
        }
        """.trimIndent()

        sendApiRequest(json, paymentType, referenceId)
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
                    Toast.makeText(
                        this@PaymentActivity,
                        "Payment Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                Log.d("SPINPOS_RESPONSE", responseText)

                runOnUiThread {

                    if (response.isSuccessful &&
                        responseText.contains("Approved", ignoreCase = true)
                    ) {

                        val jsonObj = org.json.JSONObject(responseText)

                        val authCode = jsonObj.optString("AuthCode")
                        val invoiceNumber = jsonObj.optString("InvoiceNumber")

                        val cardData = jsonObj.optJSONObject("CardData")
                        val cardType = cardData?.optString("CardType") ?: ""
                        val last4 = cardData?.optString("Last4") ?: ""
                        val entryType = cardData?.optString("EntryType") ?: ""

                        saveTransaction(
                            paymentType = paymentType,
                            referenceId = referenceId,
                            cardBrand = cardType,
                            last4 = last4,
                            entryType = entryType,
                            authCode = authCode,
                            invoiceNumber = invoiceNumber
                        )

                        Toast.makeText(
                            this@PaymentActivity,
                            "PAYMENT APPROVED ✅",
                            Toast.LENGTH_LONG
                        ).show()

                        finish()

                    } else {
                        Toast.makeText(
                            this@PaymentActivity,
                            "Payment Declined ❌",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // ===============================
    // CASH PROCESSING
    // ===============================
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

        Toast.makeText(
            this,
            "Cash Payment Completed 💵",
            Toast.LENGTH_LONG
        ).show()

        finish()
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

        db.collection("Transactions").add(transactionMap)
    }
}