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
import com.ernesto.myapplication.data.TransactionStore
import android.content.Intent
import com.ernesto.myapplication.data.Transaction

class PaymentActivity : AppCompatActivity() {

    private lateinit var txtAmount: TextView
    private lateinit var radioCredit: RadioButton
    private lateinit var radioDebit: RadioButton

    private var amountInCents: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        txtAmount = findViewById(R.id.txtAmount)
        radioCredit = findViewById(R.id.radioCredit)
        radioDebit = findViewById(R.id.radioDebit)

        val btnTransactions = findViewById<Button>(R.id.btnTransactions)

        btnTransactions.setOnClickListener {
            val intent = Intent(this, TransactionActivity::class.java)
            startActivity(intent)
        }

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

            val paymentType = if (radioDebit.isChecked) {
                "Debit"
            } else {
                "Credit"
            }

            sendSpinTransaction(formattedAmount, paymentType)
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

        // 🔥 Generate ONE reference ID
        val referenceId = java.util.UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

        val json = """
{
  "Amount": "$amount",
  "PaymentType": "$paymentType",
  "ReferenceId": "$referenceId",
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

                runOnUiThread {

                    if (response.isSuccessful && responseText.contains("Approved")) {

                        Toast.makeText(
                            this@PaymentActivity,
                            "APPROVED\nReference: $referenceId",
                            Toast.LENGTH_LONG
                        ).show()

                        val transaction = Transaction(
                            referenceId = referenceId,
                            amountInCents = amountInCents,
                            date = System.currentTimeMillis(),
                            paymentType = paymentType
                        )

                        TransactionStore.addTransaction(transaction)

                    } else {

                        Toast.makeText(
                            this@PaymentActivity,
                            "DECLINED\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }}
            }
        })
    }
}