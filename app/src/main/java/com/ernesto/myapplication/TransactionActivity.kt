package com.ernesto.myapplication

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.ernesto.myapplication.data.Transaction
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import android.util.Log
import java.util.*
import com.ernesto.myapplication.data.SaleWithRefunds
import com.google.firebase.firestore.FieldValue
class TransactionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TransactionAdapter
    private val transactionList = mutableListOf<SaleWithRefunds>()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)

        recyclerView = findViewById(R.id.recyclerTransactions)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(transactionList) { transaction ->
            showTransactionOptions(transaction)
        }

        recyclerView.adapter = adapter
        loadTransactions()
    }

    // 🔥 LOAD + GROUP SALES WITH REFUNDS (CORRECT VERSION)
    private fun loadTransactions() {

        db.collection("Transactions")
            .addSnapshotListener { snapshots, error ->

                if (error != null) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                if (snapshots == null) return@addSnapshotListener

                transactionList.clear()
                val allTransactions = mutableListOf<Transaction>()

                snapshots.forEach { doc ->

                    val createdAt = doc.getDate("createdAt")
                    val oldTimestamp = doc.getTimestamp("timestamp")?.toDate()

                    val dateMillis = createdAt?.time
                        ?: oldTimestamp?.time
                        ?: 0L

                    val transaction = Transaction(
                        referenceId = doc.getString("referenceId") ?: "",
                        amountInCents = ((doc.getDouble("amount") ?: 0.0) * 100).toLong(),
                        date = dateMillis,
                        paymentType = doc.getString("paymentType") ?: "",
                        cardBrand = doc.getString("cardBrand") ?: "",
                        last4 = doc.getString("last4") ?: "",
                        entryType = doc.getString("entryType") ?: "",
                        voided = doc.getBoolean("voided") ?: false,
                        type = doc.getString("type") ?: "SALE",
                        originalReferenceId = doc.getString("originalReferenceId") ?: ""
                    )

                    allTransactions.add(transaction)
                }

                // Sort newest first
                allTransactions.sortByDescending { it.date }

                val sales = allTransactions.filter { it.type == "SALE" }
                val refunds = allTransactions.filter { it.type == "REFUND" }

                sales.forEach { sale ->

                    val relatedRefunds =
                        if (sale.referenceId.isNullOrEmpty()) {
                            emptyList()
                        } else {
                            refunds.filter {
                                it.originalReferenceId == sale.referenceId
                            }
                        }

                    transactionList.add(
                        SaleWithRefunds(
                            sale = sale,
                            refunds = relatedRefunds
                        )
                    )
                }

                adapter.notifyDataSetChanged()
            }
    }


    private fun showTransactionOptions(transaction: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("Transaction Options")
            .setPositiveButton("Refund") { _, _ -> processRefund(transaction) }
            .setNegativeButton("Void") { _, _ -> processVoid(transaction) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun processVoid(transaction: Transaction) {

        val amount = String.format("%.2f", transaction.amountInCents / 100.0)

        val json = """
        {
          "Amount": "$amount",
          "PaymentType": "${transaction.paymentType}",
          "ReferenceId": "${transaction.referenceId}",
          "PrintReceipt": "No",
          "GetReceipt": "No",
          "Tpn": "11881706541A",
          "RegisterId": "134909005",
          "Authkey": "Qt9N7CxhDs"
        }
        """.trimIndent()

        sendApiRequest(
            url = "https://spinpos.net/v2/Payment/Void",
            json = json,
            type = "VOID",
            referenceId = transaction.referenceId
        )
    }

    private fun processRefund(transaction: Transaction) {

        val fullAmount = transaction.amountInCents / 100.0

        val input = EditText(this)
        input.hint = "Enter refund amount (Max: $fullAmount)"

        AlertDialog.Builder(this)
            .setTitle("Refund")
            .setView(input)
            .setPositiveButton("Refund") { _, _ ->
                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null || entered <= 0 || entered > fullAmount) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                } else {
                    sendRefundRequest(transaction, entered)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendRefundRequest(transaction: Transaction, amount: Double) {

        val formatted = String.format("%.2f", amount)

        val json = """
        {
          "Amount": "$formatted",
          "PaymentType": "${transaction.paymentType}",
          "ReferenceId": "${transaction.referenceId}",
          "PrintReceipt": "No",
          "GetReceipt": "No",
          "Tpn": "11881706541A",
          "RegisterId": "134909005",
          "Authkey": "Qt9N7CxhDs"
        }
        """.trimIndent()

        sendApiRequest(
            url = "https://spinpos.net/v2/Payment/Return",
            json = json,
            type = "REFUND",
            referenceId = transaction.referenceId,
            refundAmount = amount
        )
    }

    private fun sendApiRequest(
        url: String,
        json: String,
        type: String,
        referenceId: String? = null,
        refundAmount: Double? = null,
        paymentType: String? = null
    ) {

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@TransactionActivity,
                        "$type Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (!response.isSuccessful || !responseText.contains("Approved")) {
                        Toast.makeText(
                            this@TransactionActivity,
                            "$type Declined",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    // ================= VOID =================
                    if (type == "VOID" && referenceId != null) {

                        db.collection("Transactions")
                            .whereEqualTo("referenceId", referenceId)
                            .get()
                            .addOnSuccessListener { documents ->

                                for (document in documents) {

                                    val transactionDocId = document.id
                                    val amount = document.getDouble("amount") ?: 0.0

                                    // 1️⃣ mark transaction voided
                                    db.collection("Transactions")
                                        .document(transactionDocId)
                                        .update("voided", true)

                                    // 2️⃣ find order using transactionId
                                    db.collection("Orders")
                                        .whereEqualTo("transactionId", transactionDocId)
                                        .get()
                                        .addOnSuccessListener { orderDocs ->

                                            for (orderDoc in orderDocs) {

                                                val orderRef = orderDoc.reference
                                                val batchId = orderDoc.getString("batchId") ?: continue
                                                val batchRef = db.collection("Batches").document(batchId)

                                                db.runBatch { batch ->

                                                    batch.update(orderRef, mapOf(
                                                        "status" to "VOIDED",
                                                        "voidedAt" to Date()
                                                    ))

                                                    batch.update(batchRef, mapOf(
                                                        "totalSales" to FieldValue.increment(-amount),
                                                        "netTotal" to FieldValue.increment(-amount),
                                                        "transactionCount" to FieldValue.increment(-1)
                                                    ))
                                                }
                                            }
                                        }
                                }
                            }
                    }

                    // ================= REFUND =================
                    if (type == "REFUND" && referenceId != null && refundAmount != null) {

                        val refundMap = hashMapOf(
                            "referenceId" to UUID.randomUUID().toString(),
                            "originalReferenceId" to referenceId,
                            "amount" to refundAmount,
                            "type" to "REFUND",
                            "paymentType" to "",
                            "cardBrand" to "",
                            "last4" to "",
                            "entryType" to "",
                            "voided" to false,
                            "settled" to false,
                            "createdAt" to Date()
                        )

                        db.collection("Transactions").add(refundMap)
                    }

                    Toast.makeText(
                        this@TransactionActivity,
                        "$type APPROVED",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }
}
