package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView

    private lateinit var btnCheckout: MaterialButton
    private lateinit var bottomActions: View
    private lateinit var btnVoid: MaterialButton
    private lateinit var btnRefund: MaterialButton

    private lateinit var adapter: OrderItemsAdapter
    private val itemDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var orderId: String
    private var currentBatchId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        orderId = intent.getStringExtra("orderId") ?: ""

        if (orderId.isBlank()) {
            Toast.makeText(this, "Invalid order ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        recycler = findViewById(R.id.recyclerOrderItems)
        txtEmptyItems = findViewById(R.id.txtEmptyItems)

        btnCheckout = findViewById(R.id.btnCheckout)
        bottomActions = findViewById(R.id.bottomActions)
        btnVoid = findViewById(R.id.btnVoid)
        btnRefund = findViewById(R.id.btnRefund)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = OrderItemsAdapter(itemDocs)
        recycler.adapter = adapter

        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(12, 12, 12, 12)
            }
        })

        loadHeader()
        loadItems()
    }

    private fun loadHeader() {
        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) return@addOnSuccessListener

                val status = doc.getString("status") ?: ""
                currentBatchId = doc.getString("batchId")

                btnCheckout.visibility = View.GONE
                bottomActions.visibility = View.GONE
                btnVoid.visibility = View.GONE
                btnRefund.visibility = View.GONE

                if (status == "CLOSED") {
                    bottomActions.visibility = View.VISIBLE
                    btnRefund.visibility = View.VISIBLE
                    btnRefund.setOnClickListener { confirmRefund() }

                    val batchId = currentBatchId
                    if (!batchId.isNullOrBlank()) {
                        db.collection("Batches")
                            .document(batchId)
                            .get()
                            .addOnSuccessListener { batchDoc ->
                                val batchClosed = batchDoc.getBoolean("closed") == true
                                if (!batchClosed) {
                                    btnVoid.visibility = View.VISIBLE
                                    btnVoid.setOnClickListener { confirmVoid() }
                                }
                            }
                    }
                }
            }
    }

    private fun loadItems() {
        db.collection("Orders").document(orderId)
            .collection("items")
            .get()
            .addOnSuccessListener { docs ->
                itemDocs.clear()
                itemDocs.addAll(docs.documents)
                adapter.notifyDataSetChanged()

                if (itemDocs.isEmpty()) {
                    txtEmptyItems.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                } else {
                    txtEmptyItems.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                }
            }
    }

    private fun confirmVoid() {

        val batchId = currentBatchId ?: return

        db.collection("Batches")
            .document(batchId)
            .get()
            .addOnSuccessListener { batchDoc ->

                val batchClosed = batchDoc.getBoolean("closed") ?: true

                if (batchClosed) {
                    Toast.makeText(this, "Batch already closed. Cannot void.", Toast.LENGTH_LONG).show()
                    btnVoid.visibility = View.GONE
                    return@addOnSuccessListener
                }

                AlertDialog.Builder(this)
                    .setTitle("Void Order")
                    .setMessage("Void this order?")
                    .setPositiveButton("Void") { _, _ -> executeVoid() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }

    private fun executeVoid() {
        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                val transactionId = orderDoc.getString("transactionId") ?: return@addOnSuccessListener

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->

                        val referenceId = txDoc.getString("referenceId") ?: return@addOnSuccessListener
                        val paymentType = txDoc.getString("paymentType") ?: "Credit"
                        val amount = txDoc.getDouble("amount") ?: 0.0

                        callVoidApi(referenceId, paymentType, amount, transactionId)
                    }
            }
    }

    private fun callVoidApi(
        referenceId: String,
        paymentType: String,
        amount: Double,
        transactionId: String
    ) {

        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", paymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", "11881706541A")
            put("Authkey", "Qt9N7CxhDs")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Void")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@OrderDetailActivity, "Void Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (!response.isSuccessful) {
                        Toast.makeText(this@OrderDetailActivity,
                            "Void error ${response.code}: $responseText",
                            Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    val jsonObj = JSONObject(responseText)
                    val resultCode = jsonObj
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode")

                    if (resultCode == "0") {
                        finalizeVoid(transactionId)
                    } else {
                        Toast.makeText(this@OrderDetailActivity, "Void Declined", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun finalizeVoid(transactionId: String) {

        db.runBatch { batch ->

            val orderRef = db.collection("Orders").document(orderId)
            val txRef = db.collection("Transactions").document(transactionId)

            batch.update(txRef, "voided", true)

            batch.update(orderRef, mapOf(
                "status" to "VOIDED",
                "voidedAt" to Date()
            ))
        }
            .addOnSuccessListener {
                Toast.makeText(this, "Sale Voided Successfully", Toast.LENGTH_LONG).show()

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    finish()
                }, 500)
            }
    }

    private fun confirmRefund() {
        AlertDialog.Builder(this)
            .setTitle("Refund Order")
            .setMessage("Refund this order?")
            .setPositiveButton("Refund") { _, _ ->
                Toast.makeText(this, "Refund pressed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}