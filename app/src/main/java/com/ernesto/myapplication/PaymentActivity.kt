package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Date
import android.util.Log
import org.json.JSONObject
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

class PaymentActivity : AppCompatActivity() {

    private lateinit var txtAmount: TextView
    private lateinit var radioCredit: RadioButton
    private lateinit var radioDebit: RadioButton

    private var amountInCents: Long = 0
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        txtAmount = findViewById(R.id.txtAmount)
        radioCredit = findViewById(R.id.radioCredit)
        radioDebit = findViewById(R.id.radioDebit)

        val numberButtons = listOf(
            R.id.btn1, R.id.btn2, R.id.btn3,
            R.id.btn4, R.id.btn5, R.id.btn6,
            R.id.btn7, R.id.btn8, R.id.btn9,
            R.id.btn0
        )

        for (id in numberButtons) {
            findViewById<Button>(id).setOnClickListener {
                appendNumber((it as Button).text.toString())
            }
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            amountInCents = 0
            updateDisplay()
        }

        findViewById<Button>(R.id.btnOK).setOnClickListener {

            if (amountInCents == 0L) {
                Toast.makeText(this, "Enter Amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val formattedAmount = String.format("%.2f", amountInCents / 100.0)
            val selectedPaymentType = if (radioDebit.isChecked) "Debit" else "Credit"

            sendSpinTransaction(formattedAmount, selectedPaymentType)
        }

        updateDisplay()
    }

    private fun appendNumber(number: String) {
        if (amountInCents < 99999999) {
            amountInCents = amountInCents * 10 + number.toLong()
            updateDisplay()
        }
    }

    private fun updateDisplay() {
        val dollars = amountInCents / 100.0
        txtAmount.text = String.format("$%.2f", dollars)
    }

    private fun sendSpinTransaction(amount: String, paymentType: String) {

        val referenceId = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

        val json = """
{
  "Amount": "$amount",
  "PaymentType": "$paymentType",
  "ReferenceId": "$referenceId",
  "Prompt": "Card",
  "PrintReceipt": "No",
  "GetReceipt": "No",
  "Tpn": "11881706541A",
  "RegisterId": "134909005",
  "Authkey": "Qt9N7CxhDs"
}
""".trimIndent()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Sale")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@PaymentActivity,
                        "Connection Failed:\n${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""
                Log.e("SALE_RESPONSE", responseText)

                runOnUiThread {

                    if (response.isSuccessful && responseText.contains("Approved")) {

                        var cardBrand = ""
                        var last4 = ""
                        var paymentTypeFromResponse = ""
                        var entryType = ""

                        try {
                            val jsonObject = JSONObject(responseText)

                            paymentTypeFromResponse =
                                jsonObject.optString("PaymentType")

                            val cardData = jsonObject.optJSONObject("CardData")

                            if (cardData != null) {
                                cardBrand = cardData.optString("CardType")
                                last4 = cardData.optString("Last4")
                                entryType = cardData.optString("EntryType")
                            }

                        } catch (e: Exception) {
                            Log.e("JSON_PARSE_ERROR", e.message ?: "Parse error")
                        }

                        Toast.makeText(
                            this@PaymentActivity,
                            "APPROVED\n$cardBrand •••• $last4",
                            Toast.LENGTH_LONG
                        ).show()

                        saveTransactionToFirebase(
                            referenceId,
                            amountInCents,
                            paymentTypeFromResponse,
                            cardBrand,
                            last4,
                            entryType
                        )

                        // Reset amount after success
                        amountInCents = 0
                        updateDisplay()

                    } else {

                        Toast.makeText(
                            this@PaymentActivity,
                            "DECLINED\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    private fun saveTransactionToFirebase(
        referenceId: String,
        amountInCents: Long,
        paymentType: String,
        cardBrand: String,
        last4: String,
        entryType: String
    ) {

        val transactionMap = hashMapOf(
            "referenceId" to referenceId,
            "amount" to amountInCents / 100.0,
            "paymentType" to paymentType,
            "cardBrand" to cardBrand,
            "last4" to last4,
            "entryType" to entryType,
            "voided" to false,     // ✅ required
            "settled" to false,    // ✅ REQUIRED FOR BATCH SYSTEM
            "timestamp" to Date()
        )

        db.collection("Transactions")
            .add(transactionMap)
            .addOnSuccessListener {
                Toast.makeText(this, "Saved to Cloud", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Cloud Save Failed", Toast.LENGTH_SHORT).show()
            }
    }
}
