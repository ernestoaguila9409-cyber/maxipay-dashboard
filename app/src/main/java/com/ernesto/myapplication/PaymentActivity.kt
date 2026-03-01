package com.ernesto.myapplication

import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FieldValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

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
                val remaining = snap.getDouble("remainingBalance")
                    ?: snap.getDouble("total") ?: 0.0

                remainingBalance = roundMoney(remaining)

                txtPaymentTotal.text =
                    String.format(Locale.US, "Remaining: $%.2f", remainingBalance)
            }
    }

    private fun processFullPayment(paymentType: String) {
        paymentAmount = remainingBalance
        showWaitingStatus()

        if (paymentType == "Cash") processCashPayment()
        else processCardPayment(paymentType)
    }

    private fun showAmountDialog(paymentType: String) {

        val input = EditText(this)
        input.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        input.hint = "Enter amount (Remaining: $remainingBalance)"

        AlertDialog.Builder(this)
            .setTitle("Enter Amount")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->

                val entered = input.text.toString().toDoubleOrNull()

                if (entered == null ||
                    entered <= 0 ||
                    roundMoney(entered) > remainingBalance
                ) {
                    Toast.makeText(this,
                        "Invalid amount",
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                paymentAmount = roundMoney(entered)

                showWaitingStatus()

                if (paymentType == "Cash") processCashPayment()
                else processCardPayment(paymentType)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWaitingStatus() {
        statusContainer.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        txtStatus.text = "Waiting..."
        txtSubStatus.text = "Processing payment"
        setButtonsEnabled(false)
    }

    private fun showApproved() {
        progressBar.visibility = View.GONE
        txtStatus.text = "APPROVED ✅"
        txtSubStatus.text = "Transaction successful"

        Handler(Looper.getMainLooper()).postDelayed({
            loadRemainingBalance()
            setButtonsEnabled(true)
        }, 1200)
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
                        saveTransaction(paymentType)
                        showApproved()
                    } else {
                        showDeclined("Declined")
                    }
                }
            }
        })
    }

    private fun processCashPayment() {
        saveTransaction("Cash")
        showApproved()
    }

    private fun saveTransaction(paymentType: String) {

        val oid = orderId ?: return

        val transactionMap = hashMapOf(
            "orderId" to oid,
            "amount" to paymentAmount,
            "type" to "SALE",
            "paymentType" to paymentType,
            "timestamp" to Date(),
            "voided" to false,
            "settled" to false
        )

        db.collection("Transactions")
            .add(transactionMap)
            .addOnSuccessListener {

                val orderRef =
                    db.collection("Orders").document(oid)

                orderRef.get().addOnSuccessListener { snap ->

                    val orderTotal =
                        snap.getDouble("total") ?: 0.0
                    val currentPaid =
                        snap.getDouble("totalPaid") ?: 0.0

                    val newPaid =
                        roundMoney(currentPaid + paymentAmount)

                    val remaining =
                        roundMoney(orderTotal - newPaid)
                    applyPaymentToItems(
                        orderRef = orderRef,
                        amount = paymentAmount,
                        paymentType = paymentType
                    )
                    getOrCreateOpenBatch { batchId ->

                        db.runBatch { batch ->

                            val updates =
                                mutableMapOf<String, Any>(
                                    "batchId" to batchId,
                                    "totalPaid" to newPaid,
                                    "remainingBalance" to remaining
                                )

                            if (remaining <= 0.0) {
                                updates["status"] = "CLOSED"
                                updates["closedAt"] = Date()
                            }

                            batch.update(orderRef, updates)

                            val batchRef =
                                db.collection("Batches")
                                    .document(batchId)

                            batch.update(batchRef, mapOf(
                                "totalSales" to FieldValue.increment(paymentAmount),
                                "transactionCount" to FieldValue.increment(1)
                            ))
                        }
                    }
                }
            }
    }
    private fun applyPaymentToItems(
        orderRef: com.google.firebase.firestore.DocumentReference,
        amount: Double,
        paymentType: String
    ) {
        val itemsRef = orderRef.collection("items")

        itemsRef.get().addOnSuccessListener { itemsSnap ->

            var amountToApply = amount

            db.runBatch { batch ->

                for (doc in itemsSnap.documents) {

                    val quantity = doc.getLong("quantity") ?: 0L
                    val paidQty = doc.getLong("paidQuantity") ?: 0L
                    val unitPrice = doc.getDouble("unitPrice") ?: 0.0

                    val remainingQty = quantity - paidQty
                    if (remainingQty <= 0 || amountToApply <= 0 || unitPrice <= 0) continue

                    val unitsToApply = minOf(
                        remainingQty.toInt(),
                        (amountToApply / unitPrice).toInt()
                    )

                    if (unitsToApply <= 0) continue

                    val prevPaidAmount = doc.getDouble("paidAmount") ?: 0.0
                    val addedAmount = roundMoney(unitsToApply * unitPrice)

                    val newPaidQty = paidQty + unitsToApply
                    val newPaidAmount = roundMoney(prevPaidAmount + addedAmount)

                    val paymentEntry = hashMapOf(
                        "type" to paymentType,
                        "quantity" to unitsToApply,
                        "amount" to addedAmount,
                        "timestamp" to Date()
                    )

                    val itemRef = doc.reference

                    batch.update(itemRef, mapOf(
                        "paidQuantity" to newPaidQty,
                        "paidAmount" to newPaidAmount,
                        "payments" to FieldValue.arrayUnion(paymentEntry)
                    ))

                    amountToApply = roundMoney(amountToApply - addedAmount)
                }
            }
        }
    }
    private fun getOrCreateOpenBatch(onReady: (String) -> Unit) {

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->

                if (!snap.isEmpty) {
                    onReady(snap.documents.first().id)
                } else {
                    val newBatch =
                        db.collection("Batches").document()

                    newBatch.set(mapOf(
                        "createdAt" to Date(),
                        "totalSales" to 0.0,
                        "transactionCount" to 0L,
                        "closed" to false
                    )).addOnSuccessListener {
                        onReady(newBatch.id)
                    }
                }
            }
    }

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}