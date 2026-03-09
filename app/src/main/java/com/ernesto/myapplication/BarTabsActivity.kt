package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.PaymentService
import com.ernesto.myapplication.engine.PreAuthResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.Date
import java.util.UUID

class BarTabsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recyclerBarTabs: RecyclerView
    private lateinit var btnNewTab: Button
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var txtLoadingMessage: TextView
    private lateinit var adapter: BarTabsAdapter
    private lateinit var paymentService: PaymentService

    private var listener: ListenerRegistration? = null

    private var currentBatchId: String = ""
    private var employeeName: String = ""

    private var preAuthAmount: Double = 50.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bar_tabs)

        currentBatchId = intent.getStringExtra("batchId") ?: ""
        employeeName = intent.getStringExtra("employeeName") ?: ""

        recyclerBarTabs = findViewById(R.id.recyclerBarTabs)
        btnNewTab = findViewById(R.id.btnNewTab)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        txtLoadingMessage = findViewById(R.id.txtLoadingMessage)

        paymentService = PaymentService(this)
        preAuthAmount = BarTabPrefs.getPreAuthAmount(this)

        adapter = BarTabsAdapter { tab ->
            openBarOrder(tab.orderId, tab.barSeat)
        }

        recyclerBarTabs.layoutManager = LinearLayoutManager(this)
        recyclerBarTabs.adapter = adapter

        btnNewTab.setOnClickListener { startPreAuthFlow() }
    }

    override fun onStart() {
        super.onStart()
        startListening()
    }

    override fun onStop() {
        super.onStop()
        listener?.remove()
        listener = null
    }

    private fun startListening() {
        listener?.remove()

        listener = db.collection("Orders")
            .whereIn("orderType", listOf("BAR", "BAR_TAB"))
            .whereEqualTo("status", "OPEN")
            .orderBy("barSeat", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Toast.makeText(this, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener

                val tabs = snap.documents.mapNotNull { doc ->
                    val barSeat = doc.getLong("barSeat")?.toInt() ?: return@mapNotNull null
                    BarTab(
                        orderId = doc.id,
                        barSeat = barSeat,
                        status = doc.getString("status") ?: "OPEN",
                        totalInCents = doc.getLong("totalInCents") ?: 0L,
                        cardLast4 = doc.getString("cardLast4") ?: "",
                        cardBrand = doc.getString("cardBrand") ?: ""
                    )
                }
                adapter.submit(tabs)
            }
    }

    // ── PreAuth Flow ────────────────────────────────────────────────

    private fun startPreAuthFlow() {
        showLoading("Opening tab…")

        val referenceId = "TAB_${System.currentTimeMillis()}"

        paymentService.preAuth(
            amount = preAuthAmount,
            referenceId = referenceId,
            onSuccess = { result ->
                runOnUiThread { onPreAuthApproved(result) }
            },
            onFailure = { message ->
                runOnUiThread { onPreAuthFailed(message) }
            }
        )
    }

    private fun onPreAuthApproved(result: PreAuthResult) {
        txtLoadingMessage.text = "Card approved – creating tab…"
        createBarTabOrder(result)
    }

    private fun onPreAuthFailed(message: String) {
        hideLoading()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ── Firestore Order Creation ────────────────────────────────────

    private fun createBarTabOrder(preAuth: PreAuthResult) {
        db.collection("Orders")
            .whereIn("orderType", listOf("BAR", "BAR_TAB"))
            .whereEqualTo("status", "OPEN")
            .get()
            .addOnSuccessListener { snap ->
                val usedSeats = snap.documents.mapNotNull { it.getLong("barSeat")?.toInt() }.toSet()
                var nextSeat = 1
                while (usedSeats.contains(nextSeat)) nextSeat++

                val orderMap = hashMapOf<String, Any>(
                    "employeeName" to employeeName,
                    "status" to "OPEN",
                    "createdAt" to Date(),
                    "updatedAt" to Date(),
                    "totalInCents" to 0L,
                    "totalPaidInCents" to 0L,
                    "remainingInCents" to 0L,
                    "orderType" to "BAR_TAB",
                    "barSeat" to nextSeat,
                    "preAuthAmount" to preAuthAmount,
                    "preAuthReferenceId" to preAuth.referenceId,
                    "preAuthAuthCode" to preAuth.authCode,
                    "preAuthTransactionId" to preAuth.transactionId,
                    "cardLast4" to preAuth.cardLast4,
                    "cardBrand" to preAuth.cardBrand
                )

                if (currentBatchId.isNotBlank()) {
                    orderMap["batchId"] = currentBatchId
                }

                db.collection("Orders")
                    .add(orderMap)
                    .addOnSuccessListener { doc ->
                        savePreAuthTransaction(doc.id, preAuth,
                            onCreated = { txDocId ->
                                db.collection("Orders").document(doc.id)
                                    .update("preAuthFirestoreDocId", txDocId)
                                    .addOnSuccessListener {
                                        hideLoading()
                                        openBarOrder(doc.id, nextSeat)
                                    }
                                    .addOnFailureListener {
                                        hideLoading()
                                        openBarOrder(doc.id, nextSeat)
                                    }
                            },
                            onFailure = {
                                hideLoading()
                                Toast.makeText(this, "Failed to save pre-auth transaction", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    .addOnFailureListener { e ->
                        hideLoading()
                        Toast.makeText(
                            this,
                            "PreAuth approved but failed to create tab: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .addOnFailureListener { e ->
                hideLoading()
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun savePreAuthTransaction(
        orderId: String,
        preAuth: PreAuthResult,
        onCreated: (String) -> Unit = {},
        onFailure: () -> Unit = {}
    ) {
        val preAuthAmountCents = kotlin.math.round(preAuthAmount * 100).toLong()

        val paymentEntry = hashMapOf<String, Any>(
            "paymentId" to UUID.randomUUID().toString(),
            "paymentType" to "Credit",
            "amountInCents" to preAuthAmountCents,
            "timestamp" to Date(),
            "authCode" to preAuth.authCode,
            "cardBrand" to preAuth.cardBrand,
            "last4" to preAuth.cardLast4,
            "entryType" to "",
            "referenceId" to preAuth.referenceId
        )

        val txData = hashMapOf<String, Any>(
            "orderId" to orderId,
            "type" to "PRE_AUTH",
            "totalPaidInCents" to preAuthAmountCents,
            "payments" to listOf(paymentEntry),
            "status" to "HOLD",
            "createdAt" to Date(),
            "voided" to false,
            "settled" to false
        )
        if (currentBatchId.isNotBlank()) txData["batchId"] = currentBatchId

        db.collection("Transactions").add(txData)
            .addOnSuccessListener { txRef -> onCreated(txRef.id) }
            .addOnFailureListener { onFailure() }
    }

    // ── Navigation ──────────────────────────────────────────────────

    private fun openBarOrder(orderId: String, barSeat: Int) {
        val intent = Intent(this, MenuActivity::class.java)
        intent.putExtra("ORDER_ID", orderId)
        intent.putExtra("batchId", currentBatchId)
        intent.putExtra("employeeName", employeeName)
        intent.putExtra("orderType", "BAR")
        intent.putExtra("tableName", "Seat $barSeat")
        startActivity(intent)
    }

    // ── Loading UI ──────────────────────────────────────────────────

    private fun showLoading(message: String) {
        btnNewTab.isEnabled = false
        txtLoadingMessage.text = message
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
        btnNewTab.isEnabled = true
    }
}
