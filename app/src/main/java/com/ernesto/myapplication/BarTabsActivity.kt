package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.PaymentService
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date
import java.util.UUID

class BarTabsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recyclerBarTabs: RecyclerView
    private lateinit var txtEmptyState: TextView
    private lateinit var barMultiSeatActions: View
    private lateinit var btnOpenTabSelected: MaterialButton
    private lateinit var adapter: BarTabsAdapter

    private var orderListener: ListenerRegistration? = null

    private var currentBatchId: String = ""
    private var employeeName: String = ""

    private val barSeats = mutableListOf<BarSeatInfo>()
    /** Open bar-tab order info keyed by bar seat [Tables] document id. */
    private val openOrdersByTableId = mutableMapOf<String, OpenBarOrder>()

    private var selectionMode = false
    private val selectedTableIds = linkedSetOf<String>()

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
        val cardBrand: String,
        val orderNumber: Long,
        val seatCount: Int,
        val displayTableName: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bar_tabs)

        currentBatchId = intent.getStringExtra("batchId") ?: ""
        employeeName = intent.getStringExtra("employeeName") ?: ""

        recyclerBarTabs = findViewById(R.id.recyclerBarTabs)
        txtEmptyState = findViewById(R.id.txtEmptyState)
        barMultiSeatActions = findViewById(R.id.barMultiSeatActions)
        btnOpenTabSelected = findViewById(R.id.btnOpenTabSelected)

        findViewById<MaterialButton>(R.id.btnCancelSelection).setOnClickListener {
            exitSelectionMode()
        }
        btnOpenTabSelected.setOnClickListener {
            val seats = selectedSeatsAsBarSeats()
            if (seats.isNotEmpty()) {
                showSeatOrderDialog(seats)
            }
        }

        adapter = BarTabsAdapter(
            onSeatClick = { seat -> handleSeatClick(seat) },
            onSeatLongPress = { seat -> handleSeatLongPress(seat) },
        )

        recyclerBarTabs.layoutManager = LinearLayoutManager(this)
        recyclerBarTabs.adapter = adapter

        loadBarSeats()
    }

    private fun handleSeatLongPress(seat: BarSeat) {
        if (seat.isOccupied) return
        selectionMode = true
        selectedTableIds.clear()
        selectedTableIds.add(seat.tableId)
        barMultiSeatActions.visibility = View.VISIBLE
        updateOpenTabButtonLabel()
        refreshList()
    }

    private fun handleSeatClick(seat: BarSeat) {
        if (seat.isOccupied && seat.orderId != null) {
            exitSelectionMode()
            openBarOrder(seat.orderId, seat.tabDisplayName ?: seat.seatName)
            return
        }
        if (selectionMode) {
            if (selectedTableIds.contains(seat.tableId)) {
                selectedTableIds.remove(seat.tableId)
            } else {
                selectedTableIds.add(seat.tableId)
            }
            if (selectedTableIds.isEmpty()) {
                exitSelectionMode()
            } else {
                updateOpenTabButtonLabel()
                refreshList()
            }
            return
        }
        showSeatOrderDialog(listOf(seat))
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedTableIds.clear()
        barMultiSeatActions.visibility = View.GONE
        refreshList()
    }

    private fun updateOpenTabButtonLabel() {
        val n = selectedTableIds.size
        btnOpenTabSelected.text = if (n >= 2) {
            "Open tab ($n seats)"
        } else {
            "Open tab"
        }
    }

    private fun selectedSeatsAsBarSeats(): List<BarSeat> {
        val byId = barSeats.associateBy { it.tableId }
        return selectedTableIds.mapNotNull { tid ->
            val info = byId[tid] ?: return@mapNotNull null
            BarSeat(
                tableId = info.tableId,
                seatName = info.name,
                maxSeats = info.maxSeats,
                isOccupied = false,
            )
        }
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

                openOrdersByTableId.clear()
                for (doc in snap.documents) {
                    var tableIds = BarTabSeatHelper.seatTableIdsFromOrder(doc)
                    if (tableIds.isEmpty()) {
                        val legacyName = doc.getString("seatName") ?: continue
                        val match = barSeats.filter { it.name == legacyName }.map { it.tableId }
                        if (match.isEmpty()) continue
                        tableIds = match
                    }
                    val displayName = doc.getString("tableName")
                        ?: doc.getString("seatName")
                        ?: tableIds.mapNotNull { tid -> barSeats.find { it.tableId == tid }?.name }
                            .joinToString(", ")
                    val seatCount = tableIds.size.coerceAtLeast(1)
                    val obo = OpenBarOrder(
                        orderId = doc.id,
                        customerName = doc.getString("customerName"),
                        totalInCents = doc.getLong("totalInCents") ?: 0L,
                        cardLast4 = doc.getString("cardLast4") ?: "",
                        cardBrand = doc.getString("cardBrand") ?: "",
                        orderNumber = doc.getLong("orderNumber") ?: 0L,
                        seatCount = seatCount,
                        displayTableName = displayName,
                    )
                    for (tid in tableIds) {
                        openOrdersByTableId[tid] = obo
                    }
                }
                refreshList()
            }
    }

    private fun refreshList() {
        val seatList = barSeats.map { seatInfo ->
            val order = openOrdersByTableId[seatInfo.tableId]
            BarSeat(
                tableId = seatInfo.tableId,
                seatName = seatInfo.name,
                maxSeats = seatInfo.maxSeats,
                isOccupied = order != null,
                orderId = order?.orderId,
                customerName = order?.customerName,
                totalInCents = order?.totalInCents ?: 0L,
                cardLast4 = order?.cardLast4 ?: "",
                cardBrand = order?.cardBrand ?: "",
                orderNumber = order?.orderNumber ?: 0L,
                isMultiSeatTab = order != null && order.seatCount > 1,
                isSelected = selectionMode && selectedTableIds.contains(seatInfo.tableId),
                tabDisplayName = order?.displayTableName,
            )
        }
        adapter.submit(seatList)
    }

    private data class SavedCustomer(
        val id: String,
        val name: String,
        val phone: String,
        val email: String
    ) {
        override fun toString(): String = name
    }

    private fun showSeatOrderDialog(seats: List<BarSeat>) {
        if (seats.isEmpty()) return
        val formRoot = LayoutInflater.from(this)
            .inflate(R.layout.dialog_bar_seat_order, null) as LinearLayout

        val density = resources.displayMetrics.density
        val brandColor = ContextCompat.getColor(this, R.color.brand_primary)
        val textColor = ContextCompat.getColor(this, R.color.pos_primary_text)

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = (12 * density).toInt()
            val v = (10 * density).toInt()
            setPadding(h, v, h, v)
            setBackgroundColor(ContextCompat.getColor(this@BarTabsActivity, R.color.white))
            elevation = 4 * density
        }

        val btnSkip = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Skip"
            setTextColor(brandColor)
            strokeWidth = 0
        }
        val btnCancel = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Cancel"
            setTextColor(textColor)
            strokeWidth = 0
        }
        val btnStartTab = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Start Tab"
            setTextColor(brandColor)
            strokeWidth = 0
        }

        actionBar.addView(btnSkip)
        actionBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        actionBar.addView(btnCancel)
        actionBar.addView(btnStartTab)

        val dialogView = BarSeatOrderKeypad.wrapFormWithKeypads(this, formRoot, actionBar)

        val etSearchCustomer = dialogView.findViewById<AutoCompleteTextView>(R.id.etSearchCustomer)
        val etCustomerName = dialogView.findViewById<EditText>(R.id.etCustomerName)
        val etCustomerPhone = dialogView.findViewById<EditText>(R.id.etCustomerPhone)
        val etCustomerEmail = dialogView.findViewById<EditText>(R.id.etCustomerEmail)

        val selectionHighlight = ContextCompat.getColor(this, R.color.brand_primary_highlight)
        listOf(etSearchCustomer, etCustomerName, etCustomerPhone, etCustomerEmail).forEach {
            it.highlightColor = selectionHighlight
        }

        val titleNames = seats.joinToString(", ") { it.seatName }

        var selectedCustomerId: String? = null

        val allCustomers = mutableListOf<SavedCustomer>()
        val customerAdapter = object : ArrayAdapter<SavedCustomer>(
            this,
            android.R.layout.simple_list_item_1,
            allCustomers
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val customer = getItem(position) ?: return view
                val display = buildString {
                    append(customer.name)
                    if (customer.phone.isNotBlank()) append("  •  ${customer.phone}")
                }
                view.text = display
                view.setTextColor(ContextCompat.getColor(this@BarTabsActivity, R.color.pos_primary_text))
                view.textSize = 16f
                view.setPadding(48, 24, 48, 24)
                return view
            }
        }
        etSearchCustomer.setAdapter(customerAdapter)

        etSearchCustomer.setOnItemClickListener { _, _, position, _ ->
            val selected = customerAdapter.getItem(position) ?: return@setOnItemClickListener
            selectedCustomerId = selected.id
            etCustomerName.setText(selected.name)
            etCustomerPhone.setText(selected.phone)
            etCustomerEmail.setText(selected.email)
            etSearchCustomer.setText("")
            etSearchCustomer.clearFocus()
            etCustomerName.requestFocus()
        }

        db.collection("Customers")
            .get()
            .addOnSuccessListener { snap ->
                allCustomers.clear()
                for (doc in snap.documents) {
                    val firstName = (doc.getString("firstName") ?: "").trim()
                    val lastName = (doc.getString("lastName") ?: "").trim()
                    val nameField = (doc.getString("name") ?: "").trim()
                    val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                        "$firstName $lastName".trim()
                    } else {
                        nameField
                    }
                    if (fullName.isBlank()) continue

                    allCustomers.add(
                        SavedCustomer(
                            id = doc.id,
                            name = fullName,
                            phone = doc.getString("phone") ?: "",
                            email = doc.getString("email") ?: ""
                        )
                    )
                }
                allCustomers.sortBy { it.name.lowercase() }
                customerAdapter.notifyDataSetChanged()
            }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (seats.size > 1) "Open tab ($titleNames)" else seats.first().seatName)
            .setView(dialogView)
            .create()

        btnSkip.setOnClickListener {
            dialog.dismiss()
            showOpenBarTabDialog(seats, null, "", "", "")
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        btnStartTab.setOnClickListener {
            dialog.dismiss()
            val name = etCustomerName.text.toString().trim()
            val phone = etCustomerPhone.text.toString().trim()
            val email = etCustomerEmail.text.toString().trim()
            showOpenBarTabDialog(seats, selectedCustomerId, name, phone, email)
        }

        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.BOTTOM)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(dialogView.windowToken, 0)
        }
        dialog.show()
    }

    private fun showOpenBarTabDialog(
        seats: List<BarSeat>,
        customerId: String?,
        customerName: String,
        customerPhone: String,
        customerEmail: String
    ) {
        if (seats.isEmpty()) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_open_bar_tab, null)

        val txtCustomerName = dialogView.findViewById<TextView>(R.id.txtCustomerName)
        val txtSeatName = dialogView.findViewById<TextView>(R.id.txtSeatName)
        val btnPreauthCard = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreauthCard)
        val btnCashTab = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCashTab)

        txtCustomerName.text = if (customerName.isNotBlank()) customerName else "No name"
        txtSeatName.text = seats.joinToString(", ") { it.seatName }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Open Bar Tab")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        btnPreauthCard.setOnClickListener {
            dialog.dismiss()
            createBarTabOrderWithPreauth(seats, customerId, customerName, customerPhone, customerEmail)
        }

        btnCashTab.setOnClickListener {
            dialog.dismiss()
            createBarTabOrderCash(seats, customerId, customerName, customerPhone, customerEmail)
        }

        dialog.show()
    }

    private lateinit var paymentService: PaymentService
    private var preAuthDialog: AlertDialog? = null

    private fun createBarTabOrderWithPreauth(
        seats: List<BarSeat>,
        customerId: String?,
        customerName: String,
        customerPhone: String,
        customerEmail: String
    ) {
        if (seats.isEmpty()) return
        showPreAuthLoading("Creating tab…")
        if (customerId != null) {
            buildAndSaveBarTabOrder(seats, customerId, customerName, customerPhone, customerEmail, usePreauth = true)
        } else if (customerName.isNotBlank()) {
            resolveCustomerIdAndCreateOrder(seats, customerName, customerPhone, customerEmail, usePreauth = true)
        } else {
            buildAndSaveBarTabOrder(seats, null, customerName, customerPhone, customerEmail, usePreauth = true)
        }
    }

    private fun createBarTabOrderCash(
        seats: List<BarSeat>,
        customerId: String?,
        customerName: String,
        customerPhone: String,
        customerEmail: String
    ) {
        if (seats.isEmpty()) return
        showPreAuthLoading("Creating tab…")
        if (customerId != null) {
            buildAndSaveBarTabOrder(seats, customerId, customerName, customerPhone, customerEmail, usePreauth = false)
        } else if (customerName.isNotBlank()) {
            resolveCustomerIdAndCreateOrder(seats, customerName, customerPhone, customerEmail, usePreauth = false)
        } else {
            buildAndSaveBarTabOrder(seats, null, customerName, customerPhone, customerEmail, usePreauth = false)
        }
    }

    private fun resolveCustomerIdAndCreateOrder(
        seats: List<BarSeat>,
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
                val resolvedId = snap.documents.firstOrNull()?.id
                if (resolvedId != null) {
                    buildAndSaveBarTabOrder(seats, resolvedId, customerName, customerPhone, customerEmail, usePreauth)
                } else {
                    db.collection("Customers")
                        .get()
                        .addOnSuccessListener { allSnap ->
                            var foundId: String? = null
                            for (doc in allSnap.documents) {
                                val firstName = (doc.getString("firstName") ?: "").trim()
                                val lastName = (doc.getString("lastName") ?: "").trim()
                                val fullName = "$firstName $lastName".trim()
                                if (fullName.equals(customerName, ignoreCase = true)) {
                                    foundId = doc.id
                                    break
                                }
                            }
                            buildAndSaveBarTabOrder(seats, foundId, customerName, customerPhone, customerEmail, usePreauth)
                        }
                        .addOnFailureListener {
                            buildAndSaveBarTabOrder(seats, null, customerName, customerPhone, customerEmail, usePreauth)
                        }
                }
            }
            .addOnFailureListener {
                buildAndSaveBarTabOrder(seats, null, customerName, customerPhone, customerEmail, usePreauth)
            }
    }

    private fun buildAndSaveBarTabOrder(
        seats: List<BarSeat>,
        customerId: String?,
        customerName: String,
        customerPhone: String,
        customerEmail: String,
        usePreauth: Boolean
    ) {
        if (seats.isEmpty()) return
        val combinedName = seats.joinToString(", ") { it.seatName }
        val seatIds = seats.map { it.tableId }
        OrderNumberGenerator.nextOrderNumber(
            onSuccess = { orderNumber ->
                runOnUiThread {
                    val orderRef = db.collection("Orders").document()
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
                        "seatIds" to seatIds,
                        "seatName" to combinedName,
                        "tableName" to combinedName,
                        "tableId" to seats.first().tableId,
                        "area" to "Bar",
                        "guestCount" to seats.size
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

                    db.runTransaction { t ->
                        t.set(orderRef, orderMap)
                        for (tid in seatIds) {
                            t.update(
                                db.collection("Tables").document(tid),
                                mapOf("currentOrderId" to orderRef.id)
                            )
                        }
                    }.addOnSuccessListener {
                        exitSelectionMode()
                        if (usePreauth) {
                            runPreAuth(orderRef.id, combinedName)
                        } else {
                            hidePreAuthLoading()
                            openBarOrder(orderRef.id, combinedName)
                        }
                    }.addOnFailureListener { e ->
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

                val batchIdForTxn = currentBatchId
                db.runTransaction { firestoreTxn ->
                    if (batchIdForTxn.isNotBlank()) {
                        val batchRef = db.collection("Batches").document(batchIdForTxn)
                        val batchSnap = firestoreTxn.get(batchRef)
                        val counter = batchSnap.getLong("transactionCounter") ?: 0L
                        val next = counter + 1
                        firestoreTxn.update(batchRef, "transactionCounter", next)
                        txData["appTransactionNumber"] = next
                    }
                    firestoreTxn.set(txRef, txData)
                    firestoreTxn.update(db.collection("Orders").document(orderId), orderUpdates)
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
                val orderRef = db.collection("Orders").document(orderId)
                orderRef.get().addOnSuccessListener { snap ->
                    val batch = db.batch()
                    batch.delete(orderRef)
                    for (tid in BarTabSeatHelper.seatTableIdsFromOrder(snap)) {
                        batch.update(
                            db.collection("Tables").document(tid),
                            mapOf("currentOrderId" to FieldValue.delete())
                        )
                    }
                    batch.commit()
                }.addOnFailureListener {
                    orderRef.delete()
                }
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
