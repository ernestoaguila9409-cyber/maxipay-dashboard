package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.PaymentService
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date
import java.util.UUID

class BarTabsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recyclerBarTabs: RecyclerView
    private lateinit var txtEmptyState: TextView
    private lateinit var adapter: BarTabsAdapter

    private var orderListener: ListenerRegistration? = null

    private var currentBatchId: String = ""
    private var employeeName: String = ""

    private val barSeats = mutableListOf<BarSeatInfo>()
    private val openOrders = mutableMapOf<String, OpenBarOrder>()

    private data class BarSeatInfo(
        val tableId: String,
        val name: String,
        val maxSeats: Int
    )

    private data class OpenBarOrder(
        val orderId: String,
        val customerName: String?,
        val totalInCents: Long,
        val cardLast4: String,
        val cardBrand: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bar_tabs)

        currentBatchId = intent.getStringExtra("batchId") ?: ""
        employeeName = intent.getStringExtra("employeeName") ?: ""

        recyclerBarTabs = findViewById(R.id.recyclerBarTabs)
        txtEmptyState = findViewById(R.id.txtEmptyState)

        adapter = BarTabsAdapter { seat ->
            if (seat.isOccupied && seat.orderId != null) {
                openBarOrder(seat.orderId, seat.seatName)
            } else {
                showSeatOrderDialog(seat)
            }
        }

        recyclerBarTabs.layoutManager = LinearLayoutManager(this)
        recyclerBarTabs.adapter = adapter

        loadBarSeats()
    }

    override fun onStart() {
        super.onStart()
        startListeningToOrders()
    }

    override fun onStop() {
        super.onStop()
        orderListener?.remove()
        orderListener = null
    }

    private fun loadBarSeats() {
        db.collection("Tables")
            .whereEqualTo("active", true)
            .whereEqualTo("areaType", "BAR_SEAT")
            .get()
            .addOnSuccessListener { snap ->
                barSeats.clear()
                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: "Bar Seat"
                    val seats = doc.getLong("seats")?.toInt() ?: 1
                    barSeats.add(BarSeatInfo(
                        tableId = doc.id,
                        name = name,
                        maxSeats = seats
                    ))
                }
                barSeats.sortBy {
                    val match = Regex("(\\d+)").find(it.name)
                    match?.value?.toIntOrNull() ?: Int.MAX_VALUE
                }

                if (barSeats.isEmpty()) {
                    txtEmptyState.visibility = View.VISIBLE
                    recyclerBarTabs.visibility = View.GONE
                } else {
                    txtEmptyState.visibility = View.GONE
                    recyclerBarTabs.visibility = View.VISIBLE
                }

                refreshList()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load bar seats: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun startListeningToOrders() {
        orderListener?.remove()

        orderListener = db.collection("Orders")
            .whereEqualTo("orderType", "BAR_TAB")
            .whereEqualTo("status", "OPEN")
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                if (snap == null) return@addSnapshotListener

                openOrders.clear()
                for (doc in snap.documents) {
                    val seatName = doc.getString("seatName") ?: continue
                    openOrders[seatName] = OpenBarOrder(
                        orderId = doc.id,
                        customerName = doc.getString("customerName"),
                        totalInCents = doc.getLong("totalInCents") ?: 0L,
                        cardLast4 = doc.getString("cardLast4") ?: "",
                        cardBrand = doc.getString("cardBrand") ?: ""
                    )
                }
                refreshList()
            }
    }

    private fun refreshList() {
        val seatList = barSeats.map { seatInfo ->
            val order = openOrders[seatInfo.name]
            BarSeat(
                tableId = seatInfo.tableId,
                seatName = seatInfo.name,
                maxSeats = seatInfo.maxSeats,
                isOccupied = order != null,
                orderId = order?.orderId,
                customerName = order?.customerName,
                totalInCents = order?.totalInCents ?: 0L,
                cardLast4 = order?.cardLast4 ?: "",
                cardBrand = order?.cardBrand ?: ""
            )
        }
        adapter.submit(seatList)
    }

    private fun showSeatOrderDialog(seat: BarSeat) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_bar_seat_order, null)

        val txtSeatName = dialogView.findViewById<TextView>(R.id.txtSeatName)
        val etCustomerName = dialogView.findViewById<EditText>(R.id.etCustomerName)
        val etCustomerPhone = dialogView.findViewById<EditText>(R.id.etCustomerPhone)
        val etCustomerEmail = dialogView.findViewById<EditText>(R.id.etCustomerEmail)

        txtSeatName.text = seat.seatName

        AlertDialog.Builder(this)
            .setTitle(seat.seatName)
            .setView(dialogView)
            .setPositiveButton("Start Tab") { _, _ ->
                val name = etCustomerName.text.toString().trim()
                val phone = etCustomerPhone.text.toString().trim()
                val email = etCustomerEmail.text.toString().trim()
                showOpenBarTabDialog(seat, name, phone, email)
            }
            .setNeutralButton("Skip") { _, _ ->
                showOpenBarTabDialog(seat, "", "", "")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOpenBarTabDialog(
        seat: BarSeat,
        customerName: String,
        customerPhone: String,
        customerEmail: String
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_open_bar_tab, null)

        val txtCustomerName = dialogView.findViewById<TextView>(R.id.txtCustomerName)
        val txtSeatName = dialogView.findViewById<TextView>(R.id.txtSeatName)
        val btnPreauthCard = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreauthCard)
        val btnCashTab = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCashTab)

        txtCustomerName.text = if (customerName.isNotBlank()) customerName else "No name"
        txtSeatName.text = seat.seatName

        val dialog = AlertDialog.Builder(this)
            .setTitle("Open Bar Tab")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        btnPreauthCard.setOnClickListener {
            dialog.dismiss()
            createBarTabOrderWithPreauth(seat, customerName, customerPhone, customerEmail)
        }

        btnCashTab.setOnClickListener {
            dialog.dismiss()
            createBarTabOrderCash(seat, customerName, customerPhone, customerEmail)
        }

        dialog.show()
    }

    private lateinit var paymentService: PaymentService
    private var preAuthDialog: AlertDialog? = null

    private fun createBarTabOrderWithPreauth(
        seat: BarSeat,
        customerName: String,
        customerPhone: String,
        customerEmail: String
    ) {
        showPreAuthLoading("Creating tab…")
        if (customerName.isNotBlank()) {
            resolveCustomerIdAndCreateOrder(seat, customerName, customerPhone, customerEmail, usePreauth = true)
        } else {
            buildAndSaveBarTabOrder(seat, null, customerName, customerPhone, customerEmail, usePreauth = true)
        }
    }

    private fun createBarTabOrderCash(
        seat: BarSeat,
        customerName: String,
        customerPhone: String,
        customerEmail: String
    ) {
        showPreAuthLoading("Creating tab…")
        if (customerName.isNotBlank()) {
            resolveCustomerIdAndCreateOrder(seat, customerName, customerPhone, customerEmail, usePreauth = false)
        } else {
            buildAndSaveBarTabOrder(seat, null, customerName, customerPhone, customerEmail, usePreauth = false)
        }
    }

    private fun resolveCustomerIdAndCreateOrder(
        seat: BarSeat,
        customerName: String,
        customerPhone: String,
        customerEmail: String,
        usePreauth: Boolean
    ) {
        db.collection("Customers")
            .whereEqualTo("name", customerName)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val customerId = snap.documents.firstOrNull()?.id
                buildAndSaveBarTabOrder(seat, customerId, customerName, customerPhone, customerEmail, usePreauth)
            }
            .addOnFailureListener {
                buildAndSaveBarTabOrder(seat, null, customerName, customerPhone, customerEmail, usePreauth)
            }
    }

    private fun buildAndSaveBarTabOrder(
        seat: BarSeat,
        customerId: String?,
        customerName: String,
        customerPhone: String,
        customerEmail: String,
        usePreauth: Boolean
    ) {
        OrderNumberGenerator.nextOrderNumber(
            onSuccess = { orderNumber ->
                runOnUiThread {
                    val orderMap = hashMapOf<String, Any>(
                        "orderNumber" to orderNumber,
                        "employeeName" to employeeName,
                        "status" to "OPEN",
                        "createdAt" to Date(),
                        "updatedAt" to Date(),
                        "totalInCents" to 0L,
                        "totalPaidInCents" to 0L,
                        "remainingInCents" to 0L,
                        "orderType" to "BAR_TAB",
                        "seatName" to seat.seatName,
                        "area" to "Bar",
                        "guestCount" to 1
                    )

                    if (!customerId.isNullOrBlank()) orderMap["customerId"] = customerId
                    if (customerName.isNotBlank()) orderMap["customerName"] = customerName
                    if (customerPhone.isNotBlank()) orderMap["customerPhone"] = customerPhone
                    if (customerEmail.isNotBlank()) orderMap["customerEmail"] = customerEmail
                    if (currentBatchId.isNotBlank()) orderMap["batchId"] = currentBatchId

                    if (usePreauth) {
                        // Payment fields set after preauth succeeds
                    } else {
                        orderMap["paymentMethod"] = "CASH"
                        orderMap["paymentStatus"] = "OPEN"
                    }

                    db.collection("Orders")
                        .add(orderMap)
                        .addOnSuccessListener { doc ->
                            if (usePreauth) {
                                runPreAuth(doc.id, seat.seatName)
                            } else {
                                hidePreAuthLoading()
                                openBarOrder(doc.id, seat.seatName)
                            }
                        }
                        .addOnFailureListener { e ->
                            hidePreAuthLoading()
                            Toast.makeText(
                                this,
                                "Failed to create tab: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    hidePreAuthLoading()
                    Toast.makeText(
                        this,
                        "Failed to generate order number: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun runPreAuth(orderId: String, seatName: String) {
        if (!::paymentService.isInitialized) {
            paymentService = PaymentService(this)
        }

        val preAuthAmount = BarTabPrefs.getPreAuthAmount(this)
        val referenceId = UUID.randomUUID().toString()

        runOnUiThread { showPreAuthLoading("Pre-authorizing card…") }

        paymentService.preAuth(
            amount = preAuthAmount,
            referenceId = referenceId,
            onSuccess = { result ->
                val txRef = db.collection("Transactions").document()
                val txData = hashMapOf<String, Any>(
                    "orderId" to orderId,
                    "type" to "PRE_AUTH",
                    "totalPaidInCents" to (preAuthAmount * 100).toLong(),
                    "payments" to listOf(
                        hashMapOf(
                            "paymentId" to UUID.randomUUID().toString(),
                            "paymentType" to "Credit",
                            "amountInCents" to (preAuthAmount * 100).toLong(),
                            "timestamp" to Date(),
                            "authCode" to result.authCode,
                            "cardBrand" to result.cardBrand,
                            "last4" to result.cardLast4,
                            "referenceId" to result.referenceId
                        )
                    ),
                    "status" to "PENDING",
                    "createdAt" to Date(),
                    "voided" to false,
                    "settled" to false
                )
                if (currentBatchId.isNotBlank()) txData["batchId"] = currentBatchId

                val preAuthAmount = BarTabPrefs.getPreAuthAmount(this)
                val orderUpdates = hashMapOf<String, Any>(
                    "paymentMethod" to "CARD",
                    "paymentStatus" to "PREAUTHORIZED",
                    "preAuthAmount" to preAuthAmount,
                    "preAuthReferenceId" to result.referenceId,
                    "preAuthAuthCode" to result.authCode,
                    "cardLast4" to result.cardLast4,
                    "cardBrand" to result.cardBrand,
                    "preAuthFirestoreDocId" to txRef.id,
                    "updatedAt" to Date()
                )

                db.runBatch { batch ->
                    batch.set(txRef, txData)
                    batch.update(db.collection("Orders").document(orderId), orderUpdates)
                }.addOnSuccessListener {
                    runOnUiThread {
                        hidePreAuthLoading()
                        openBarOrder(orderId, seatName)
                    }
                }.addOnFailureListener { e ->
                    runOnUiThread {
                        hidePreAuthLoading()
                        Toast.makeText(
                            this,
                            "Card authorized but failed to save: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        openBarOrder(orderId, seatName)
                    }
                }
            },
            onFailure = { msg ->
                runOnUiThread {
                    hidePreAuthLoading()
                    showPreAuthFailedDialog(orderId, seatName, msg)
                }
            }
        )
    }

    private fun showPreAuthFailedDialog(orderId: String, seatName: String, errorMsg: String) {
        AlertDialog.Builder(this)
            .setTitle("Pre-Authorization Failed")
            .setMessage("$errorMsg\n\nWould you like to retry, continue without pre-auth, or cancel?")
            .setPositiveButton("Retry") { _, _ ->
                runPreAuth(orderId, seatName)
            }
            .setNeutralButton("Continue") { _, _ ->
                db.collection("Orders").document(orderId)
                    .update(
                        mapOf(
                            "paymentMethod" to "CASH",
                            "paymentStatus" to "OPEN",
                            "updatedAt" to Date()
                        )
                    )
                    .addOnSuccessListener { openBarOrder(orderId, seatName) }
                    .addOnFailureListener { openBarOrder(orderId, seatName) }
            }
            .setNegativeButton("Cancel") { _, _ ->
                db.collection("Orders").document(orderId).delete()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPreAuthLoading(message: String) {
        hidePreAuthLoading()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
        }
        layout.addView(ProgressBar(this))
        layout.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(32, 0, 0, 0)
        })

        preAuthDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
        preAuthDialog?.show()
    }

    private fun hidePreAuthLoading() {
        preAuthDialog?.dismiss()
        preAuthDialog = null
    }

    private fun openBarOrder(orderId: String, seatName: String) {
        val intent = Intent(this, MenuActivity::class.java)
        intent.putExtra("ORDER_ID", orderId)
        intent.putExtra("batchId", currentBatchId)
        intent.putExtra("employeeName", employeeName)
        intent.putExtra("orderType", "BAR_TAB")
        intent.putExtra("tableName", seatName)
        startActivity(intent)
    }
}
