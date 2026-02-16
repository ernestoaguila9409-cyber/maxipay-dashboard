package com.ernesto.myapplication

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionStore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerTransactions)

        val transactions = TransactionStore.getTransactions()

        recyclerView.layoutManager = LinearLayoutManager(this)

        recyclerView.adapter = TransactionAdapter(transactions) { transaction ->
            showTransactionOptions(transaction)
        }
    }

    private fun showTransactionOptions(transaction: Transaction) {

        AlertDialog.Builder(this)
            .setTitle("Transaction Options")
            .setMessage("Choose an action")
            .setPositiveButton("Refund") { _, _ ->
                processRefund(transaction)
            }
            .setNegativeButton("Void") { _, _ ->
                processVoid(transaction)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ===================== VOID =====================

    private fun processVoid(transaction: Transaction) {

        val amountFormatted = String.format("%.2f", transaction.amountInCents / 100.0)

        val json = """
{
  "Amount": "$amountFormatted",
  "PaymentType": "${transaction.paymentType}",
  "ReferenceId": "${transaction.referenceId}",
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
            .url("https://spinpos.net/v2/Payment/Void")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@TransactionActivity,
                        "Void Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (response.isSuccessful && responseText.contains("Approved")) {

                        Toast.makeText(
                            this@TransactionActivity,
                            "VOID APPROVED\nRef: ${transaction.referenceId}",
                            Toast.LENGTH_LONG
                        ).show()

                    } else {

                        Toast.makeText(
                            this@TransactionActivity,
                            "VOID DECLINED\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // ===================== REFUND MENU =====================

    private fun processRefund(transaction: Transaction) {

        val fullAmount = transaction.amountInCents / 100.0

        val options = arrayOf(
            "Full Refund - $%.2f".format(fullAmount),
            "Partial Refund"
        )

        AlertDialog.Builder(this)
            .setTitle("Refund Options")
            .setItems(options) { _, which ->

                when (which) {

                    0 -> sendRefundRequest(transaction, fullAmount)

                    1 -> showPartialRefundDialog(transaction, fullAmount)
                }
            }
            .show()
    }

    // ===================== PARTIAL INPUT =====================

    private fun showPartialRefundDialog(transaction: Transaction, maxAmount: Double) {

        val input = EditText(this)
        input.hint = "Enter refund amount"

        AlertDialog.Builder(this)
            .setTitle("Partial Refund")
            .setMessage("Max: $%.2f".format(maxAmount))
            .setView(input)
            .setPositiveButton("Refund") { _, _ ->

                val enteredAmount = input.text.toString().toDoubleOrNull()

                if (enteredAmount == null || enteredAmount <= 0 || enteredAmount > maxAmount) {

                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()

                } else {

                    sendRefundRequest(transaction, enteredAmount)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===================== REFUND API =====================

    private fun sendRefundRequest(transaction: Transaction, refundAmount: Double) {

        val amountFormatted = String.format("%.2f", refundAmount)

        val json = """
{
  "Amount": "$amountFormatted",
  "PaymentType": "${transaction.paymentType}",
  "ReferenceId": "${transaction.referenceId}",
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
            .url("https://spinpos.net/v2/Payment/Return")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@TransactionActivity,
                        "Refund Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (response.isSuccessful && responseText.contains("Approved")) {

                        Toast.makeText(
                            this@TransactionActivity,
                            "REFUND APPROVED\n$amountFormatted",
                            Toast.LENGTH_LONG
                        ).show()

                    } else {

                        Toast.makeText(
                            this@TransactionActivity,
                            "REFUND DECLINED\n$responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }
}
