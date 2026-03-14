package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.ernesto.myapplication.data.TransactionPayment
import org.json.JSONObject
import java.io.IOException
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale
import android.util.Log
import android.widget.EditText
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.OrderEngine
import com.google.firebase.functions.FirebaseFunctions

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val orderEngine = OrderEngine(FirebaseFirestore.getInstance())
    private lateinit var txtHeaderOrderNumber: TextView
    private lateinit var txtHeaderEmployee: TextView
    private lateinit var txtHeaderCustomer: TextView
    private lateinit var txtHeaderTime: TextView
    private lateinit var txtRefundHistory: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmptyItems: TextView
    private lateinit var btnCheckout: MaterialButton
    private lateinit var bottomActions: View
    private lateinit var btnVoid: MaterialButton
    private lateinit var btnRefund: MaterialButton
    private lateinit var btnEmailReceipt: MaterialButton
    private lateinit var txtAddCustomer: TextView
    private lateinit var txtOrderType: TextView
    private lateinit var orderSummaryContainer: LinearLayout
    private lateinit var txtSubtotal: TextView
    private lateinit var txtOrderTotal: TextView
    private lateinit var taxBreakdownContainer: LinearLayout
    private lateinit var tipRow: LinearLayout
    private lateinit var txtTipAmount: TextView

    private lateinit var adapter: OrderItemsAdapter
    private val listItems = mutableListOf<OrderListItem>()
    private val itemDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var orderId: String
    private var currentBatchId: String? = null
    private var orderType: String = ""

    private val tableSelectLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val tableId = data.getStringExtra("tableId") ?: ""
                val tableName = data.getStringExtra("tableName") ?: ""
                val guestCount = data.getIntExtra("guestCount", 0)
                val guestNames = data.getStringArrayListExtra("guestNames") ?: arrayListOf()

                switchToDineIn(tableId, tableName, guestCount, guestNames)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        orderId = intent.getStringExtra("orderId") ?: ""

        if (orderId.isBlank()) {
            Toast.makeText(this, "Invalid order ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        txtHeaderOrderNumber = findViewById(R.id.txtHeaderOrderNumber)
        txtHeaderEmployee = findViewById(R.id.txtHeaderEmployee)
        txtHeaderCustomer = findViewById(R.id.txtHeaderCustomer)
        txtHeaderTime = findViewById(R.id.txtHeaderTime)
        txtRefundHistory = findViewById(R.id.txtRefundHistory)
        recycler = findViewById(R.id.recyclerOrderItems)
        txtEmptyItems = findViewById(R.id.txtEmptyItems)
        btnCheckout = findViewById(R.id.btnCheckout)
        bottomActions = findViewById(R.id.bottomActions)
        btnVoid = findViewById(R.id.btnVoid)
        btnRefund = findViewById(R.id.btnRefund)
        btnEmailReceipt = findViewById(R.id.btnEmailReceipt)
        txtAddCustomer = findViewById(R.id.txtAddCustomer)
        txtOrderType = findViewById(R.id.txtOrderType)
        orderSummaryContainer = findViewById(R.id.orderSummaryContainer)
        txtSubtotal = findViewById(R.id.txtSubtotal)
        txtOrderTotal = findViewById(R.id.txtOrderTotal)
        taxBreakdownContainer = findViewById(R.id.taxBreakdownContainer)
        tipRow = findViewById(R.id.tipRow)
        txtTipAmount = findViewById(R.id.txtTipAmount)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = OrderItemsAdapter(listItems) { itemDoc -> onOrderItemClick(itemDoc) }
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

    override fun onResume() {
        super.onResume()
        loadHeader()
    }

    // ===============================
    // LOAD HEADER
    // ===============================

    private fun loadHeader() {

        // Reset UI first
        btnCheckout.visibility = View.GONE
        bottomActions.visibility = View.GONE
        btnVoid.visibility = View.GONE
        btnRefund.visibility = View.GONE
        btnEmailReceipt.visibility = View.GONE

        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) return@addOnSuccessListener

                val status = doc.getString("status") ?: ""
                val employee = doc.getString("employeeName") ?: "Unknown"
                val customerName = doc.getString("customerName") ?: ""
                val orderNumber = doc.getLong("orderNumber") ?: 0L

                if (orderNumber > 0L) {
                    txtHeaderOrderNumber.text = "Order #$orderNumber"
                    txtHeaderOrderNumber.visibility = View.VISIBLE
                } else {
                    txtHeaderOrderNumber.visibility = View.GONE
                }

                if (status == "VOIDED") {
                    val voidedBy = doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: "—"
                    txtHeaderEmployee.text = "Voided by: $voidedBy"
                    val voidedAt = doc.getTimestamp("voidedAt")
                    if (voidedAt != null) {
                        val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                        txtHeaderTime.text = format.format(voidedAt.toDate())
                    } else {
                        txtHeaderTime.text = ""
                    }
                } else {
                    txtHeaderEmployee.text = "Employee: $employee"
                    val createdAt = doc.getTimestamp("createdAt")
                    if (createdAt != null) {
                        val date = createdAt.toDate()
                        val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
                        txtHeaderTime.text = format.format(date)
                    } else {
                        txtHeaderTime.text = ""
                    }
                }

                if (customerName.isNotBlank()) {
                    txtHeaderCustomer.text = "Customer: $customerName"
                    txtHeaderCustomer.visibility = View.VISIBLE
                    txtAddCustomer.visibility = View.GONE
                } else {
                    txtHeaderCustomer.visibility = View.GONE
                    if (status == "OPEN") {
                        txtAddCustomer.visibility = View.VISIBLE
                        txtAddCustomer.setOnClickListener { showAddCustomerDialog() }
                    } else {
                        txtAddCustomer.visibility = View.GONE
                    }
                }

                currentBatchId = doc.getString("batchId")
                orderType = doc.getString("orderType") ?: ""

                displayOrderSummary(doc)
                updateOrderTypeBadge(status)

                if (status == "OPEN") {
                    btnCheckout.visibility = View.VISIBLE
                    btnCheckout.setOnClickListener {
                        val i = Intent(this, MenuActivity::class.java)
                        i.putExtra("ORDER_ID", orderId)
                        startActivity(i)
                    }
                }

                if (status == "VOIDED") {
                    bottomActions.visibility = View.VISIBLE
                    btnVoid.visibility = View.GONE
                    btnRefund.visibility = View.GONE
                    btnEmailReceipt.visibility = View.VISIBLE
                    btnEmailReceipt.setOnClickListener { showEmailReceiptDialog() }
                    txtRefundHistory.visibility = View.GONE
                    return@addOnSuccessListener
                }

                val saleTransactionId = doc.getString("saleTransactionId") ?: doc.getString("transactionId")
                val totalInCents = doc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val isFullyRefunded = totalRefundedInCents >= totalInCents && totalInCents > 0L

                loadRefundHistory(saleTransactionId) { totalFromRefunds, refundedLineKeys, refundedNameAmountKeys, lineKeyToEmployee, nameAmountToEmployee, lineKeyToDate, nameAmountToDate ->
                    val fullyRefunded = isFullyRefunded || (totalFromRefunds >= totalInCents && totalInCents > 0L)
                    txtRefundHistory.visibility = View.GONE
                    adapter.setRefundedKeys(refundedLineKeys, refundedNameAmountKeys, lineKeyToEmployee, nameAmountToEmployee, lineKeyToDate, nameAmountToDate)
                    if (status == "REFUNDED" || fullyRefunded) {
                        bottomActions.visibility = View.VISIBLE
                        btnVoid.visibility = View.GONE
                        btnRefund.visibility = View.GONE
                        btnEmailReceipt.visibility = View.VISIBLE
                        btnEmailReceipt.setOnClickListener { showEmailReceiptDialog() }
                        return@loadRefundHistory
                    }
                    if (status == "CLOSED") {
                        bottomActions.visibility = View.VISIBLE
                        if (!fullyRefunded) {
                            btnRefund.visibility = View.VISIBLE
                            btnRefund.setOnClickListener { confirmRefund() }
                        } else {
                            btnRefund.visibility = View.GONE
                        }
                        btnEmailReceipt.visibility = View.VISIBLE
                        btnEmailReceipt.setOnClickListener { showEmailReceiptDialog() }
                        resolveBatchAndShowVoid(saleTransactionId)
                    }
                }
            }
    }

    private fun showAddCustomerDialog() {
        CustomerDialogHelper.showCustomerDialog(this) { info ->
            val updates = hashMapOf<String, Any>(
                "customerName" to info.name,
                "customerPhone" to info.phone,
                "customerEmail" to info.email,
                "updatedAt" to Date()
            )
            if (info.id != null) {
                updates["customerId"] = info.id
            }

            db.collection("Orders").document(orderId)
                .update(updates as Map<String, Any>)
                .addOnSuccessListener {
                    txtHeaderCustomer.text = "Customer: ${info.name}"
                    txtHeaderCustomer.visibility = View.VISIBLE
                    txtAddCustomer.visibility = View.GONE
                    saveCustomerToFirestoreIfNew(info)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun saveCustomerToFirestoreIfNew(info: CustomerDialogHelper.CustomerInfo) {
        if (info.id != null) return

        CustomerDuplicateChecker.checkExists(db, info.name, info.email, info.phone) { exists ->
            if (exists) {
                resolveCustomerId(info.name, info.email)
            } else {
                val customer = hashMapOf<String, Any>(
                    "name" to info.name,
                    "phone" to info.phone,
                    "email" to info.email
                )
                db.collection("Customers")
                    .add(customer)
                    .addOnSuccessListener { docRef ->
                        db.collection("Orders").document(orderId)
                            .update("customerId", docRef.id)
                    }
            }
        }
    }

    private fun resolveCustomerId(name: String, email: String) {
        db.collection("Customers")
            .whereEqualTo("name", name)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val doc = snap.documents.firstOrNull()
                if (doc != null) {
                    db.collection("Orders").document(orderId)
                        .update("customerId", doc.id)
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun displayOrderSummary(orderDoc: DocumentSnapshot) {
        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L

        if (totalInCents <= 0L) {
            orderSummaryContainer.visibility = View.GONE
            recomputeAndRefreshSummary()
            return
        }

        renderSummary(totalInCents, taxBreakdown, tipAmountInCents)
    }

    private fun recomputeAndRefreshSummary() {
        orderEngine.recomputeOrderTotals(
            orderId = orderId,
            onSuccess = {
                db.collection("Orders").document(orderId).get()
                    .addOnSuccessListener { doc ->
                        if (doc.exists()) {
                            val totalInCents = doc.getLong("totalInCents") ?: 0L
                            @Suppress("UNCHECKED_CAST")
                            val taxBreakdown = doc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                            val tipAmountInCents = doc.getLong("tipAmountInCents") ?: 0L
                            if (totalInCents > 0L) {
                                renderSummary(totalInCents, taxBreakdown, tipAmountInCents)
                            }
                        }
                    }
            },
            onFailure = { }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderSummary(totalInCents: Long, taxBreakdown: List<Map<String, Any>>, tipAmountInCents: Long = 0L) {
        var taxTotalCents = 0L
        taxBreakdownContainer.removeAllViews()

        for (entry in taxBreakdown) {
            val name = entry["name"]?.toString() ?: "Tax"
            val amountCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            taxTotalCents += amountCents

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }
            val labelView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = name
                textSize = 14f
                setTextColor(0xFF555555.toInt())
            }
            val amountView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = MoneyUtils.centsToDisplay(amountCents)
                textSize = 14f
                setTextColor(0xFF555555.toInt())
            }
            row.addView(labelView)
            row.addView(amountView)
            taxBreakdownContainer.addView(row)
        }

        val subtotalCents = totalInCents - taxTotalCents - tipAmountInCents
        txtSubtotal.text = MoneyUtils.centsToDisplay(subtotalCents)

        if (tipAmountInCents > 0L) {
            tipRow.visibility = View.VISIBLE
            txtTipAmount.text = MoneyUtils.centsToDisplay(tipAmountInCents)
        } else {
            tipRow.visibility = View.GONE
        }

        txtOrderTotal.text = MoneyUtils.centsToDisplay(totalInCents)
        orderSummaryContainer.visibility = View.VISIBLE
    }

    private var refundHistoryLines: String = ""

    /**
     * For CLOSED orders: load transaction to check if all-cash (then no Void), then resolve batchId
     * and show VOID button only when batch.closed == false and payment was not all cash.
     */
    private fun resolveBatchAndShowVoid(saleTransactionId: String?) {
        if (saleTransactionId.isNullOrBlank()) {
            btnVoid.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(saleTransactionId).get()
            .addOnSuccessListener { txDoc ->
                if (isTransactionAllCash(txDoc)) {
                    btnVoid.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                    ?: currentBatchId?.takeIf { it.isNotBlank() }
                if (!batchId.isNullOrBlank()) {
                    currentBatchId = batchId
                    loadBatchAndUpdateVoidButton(batchId)
                } else {
                    btnVoid.visibility = View.GONE
                }
            }
            .addOnFailureListener { btnVoid.visibility = View.GONE }
    }

    private fun isTransactionAllCash(txDoc: DocumentSnapshot): Boolean {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        if (payments.isEmpty()) {
            val legacyType = txDoc.getString("paymentType")?.takeIf { it.isNotBlank() } ?: ""
            return legacyType.equals("Cash", true)
        }
        return payments.all { p ->
            (p as? Map<*, *>)?.get("paymentType")?.toString()?.equals("Cash", true) == true
        }
    }

    private fun loadBatchAndUpdateVoidButton(batchId: String) {
        db.collection("Batches").document(batchId).get()
            .addOnSuccessListener { batchDoc ->
                val batchClosed = batchDoc.getBoolean("closed") ?: true
                if (!batchClosed) {
                    btnVoid.visibility = View.VISIBLE
                    btnVoid.setOnClickListener { confirmVoid() }
                } else {
                    btnVoid.visibility = View.GONE
                }
            }
            .addOnFailureListener { btnVoid.visibility = View.GONE }
    }

    private fun getPaymentTypeFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return (first?.get("paymentType")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("paymentType")?.takeIf { it.isNotBlank() }).orEmpty()
    }

    private fun getReferenceIdFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return first?.get("referenceId")?.toString()?.takeIf { it.isNotBlank() }
            ?: first?.get("terminalReference")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("referenceId")?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun loadRefundHistory(saleTransactionId: String?, onLoaded: (totalRefundedCents: Long, refundedLineKeys: Set<String>, refundedNameAmountKeys: Set<String>, refundedLineKeyToEmployee: Map<String, String>, refundedNameAmountToEmployee: Map<String, String>, refundedLineKeyToDate: Map<String, String>, refundedNameAmountToDate: Map<String, String>) -> Unit) {
        refundHistoryLines = ""
        if (saleTransactionId.isNullOrBlank()) {
            onLoaded(0L, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
            return
        }
        val dateFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
        db.collection("Transactions")
            .whereEqualTo("type", "REFUND")
            .whereEqualTo("originalReferenceId", saleTransactionId)
            .get()
            .addOnSuccessListener { snap ->
                var totalCents = 0L
                val refundedLineKeys = mutableSetOf<String>()
                val refundedNameAmountKeys = mutableSetOf<String>()
                val refundedLineKeyToEmployee = mutableMapOf<String, String>()
                val refundedNameAmountToEmployee = mutableMapOf<String, String>()
                val refundedLineKeyToDate = mutableMapOf<String, String>()
                val refundedNameAmountToDate = mutableMapOf<String, String>()
                for (refundDoc in snap.documents) {
                    val amountCents = refundDoc.getLong("amountInCents")
                        ?: ((refundDoc.getDouble("amount") ?: 0.0) * 100).toLong()
                    totalCents += amountCents
                    val employee = refundDoc.getString("refundedBy")?.trim()?.takeIf { it.isNotBlank() } ?: "—"
                    val createdAt = refundDoc.getTimestamp("createdAt")?.toDate()
                    val dateStr = if (createdAt != null) dateFormat.format(createdAt) else ""
                    refundDoc.getString("refundedLineKey")?.takeIf { it.isNotBlank() }?.let { key ->
                        refundedLineKeys.add(key)
                        refundedLineKeyToEmployee[key] = employee
                        refundedLineKeyToDate[key] = dateStr
                    }
                    val itemName = refundDoc.getString("refundedItemName")?.trim()?.takeIf { it.isNotBlank() }
                    if (itemName != null && refundDoc.getString("refundedLineKey")?.isNotBlank() != true) {
                        val nameAmountKey = "$itemName|$amountCents"
                        refundedNameAmountKeys.add(nameAmountKey)
                        refundedNameAmountToEmployee[nameAmountKey] = employee
                        refundedNameAmountToDate[nameAmountKey] = dateStr
                    }
                }
                onLoaded(totalCents, refundedLineKeys, refundedNameAmountKeys, refundedLineKeyToEmployee, refundedNameAmountToEmployee, refundedLineKeyToDate, refundedNameAmountToDate)
            }
            .addOnFailureListener { onLoaded(0L, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap()) }
    }
    // ===============================
    // LOAD ITEMS
    // ===============================

    private fun updateOrderTypeBadge(status: String) {
        if (orderType != "TO_GO" && orderType != "DINE_IN") {
            txtOrderType.visibility = View.GONE
            return
        }

        txtOrderType.visibility = View.VISIBLE
        val label = if (orderType == "TO_GO") "TO-GO" else "DINE IN"
        val bgColor = if (orderType == "TO_GO") "#FF9800" else "#4CAF50"

        txtOrderType.text = if (status == "OPEN") "$label  ✎" else label
        txtOrderType.setTextColor(android.graphics.Color.WHITE)

        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(android.graphics.Color.parseColor(bgColor))
        bg.cornerRadius = 16f
        txtOrderType.background = bg

        if (status == "OPEN") {
            txtOrderType.setOnClickListener { showSwitchOrderTypeDialog() }
        } else {
            txtOrderType.setOnClickListener(null)
            txtOrderType.isClickable = false
        }
    }

    private fun showSwitchOrderTypeDialog() {
        if (orderType == "TO_GO") {
            AlertDialog.Builder(this)
                .setTitle("Switch to Dine In")
                .setMessage("Select a table for this order?")
                .setPositiveButton("Yes") { _, _ ->
                    val intent = Intent(this, TableSelectionActivity::class.java)
                    intent.putExtra("SELECT_TABLE_ONLY", true)
                    tableSelectLauncher.launch(intent)
                }
                .setNegativeButton("No", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Switch to To Go")
                .setMessage("Change this order to To Go?")
                .setPositiveButton("Yes") { _, _ ->
                    switchToToGo()
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun switchToDineIn(tableId: String, tableName: String, guestCount: Int, guestNames: List<String>) {
        val updates = mutableMapOf<String, Any>(
            "orderType" to "DINE_IN",
            "updatedAt" to Date()
        )
        if (tableId.isNotBlank()) updates["tableId"] = tableId
        if (tableName.isNotBlank()) updates["tableName"] = tableName
        if (guestCount > 0) updates["guestCount"] = guestCount
        if (guestNames.isNotEmpty()) updates["guestNames"] = guestNames

        db.collection("Orders").document(orderId)
            .update(updates)
            .addOnSuccessListener {
                orderType = "DINE_IN"
                updateOrderTypeBadge("OPEN")
                Toast.makeText(this, "Switched to Dine In – $tableName", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun switchToToGo() {
        val updates = mapOf<String, Any>(
            "orderType" to "TO_GO",
            "tableId" to FieldValue.delete(),
            "tableName" to FieldValue.delete(),
            "guestCount" to FieldValue.delete(),
            "guestNames" to FieldValue.delete(),
            "updatedAt" to Date()
        )

        db.collection("Orders").document(orderId)
            .update(updates)
            .addOnSuccessListener {
                orderType = "TO_GO"
                updateOrderTypeBadge("OPEN")
                Toast.makeText(this, "Switched to To Go", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ===============================

    private var guestNames: List<String> = emptyList()

    private fun loadItems() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                @Suppress("UNCHECKED_CAST")
                guestNames = (orderDoc.get("guestNames") as? List<String>) ?: emptyList()

                db.collection("Orders").document(orderId)
                    .collection("items")
                    .get()
                    .addOnSuccessListener { docs ->
                        itemDocs.clear()
                        itemDocs.addAll(docs.documents)

                        listItems.clear()

                        val hasGuests = orderType == "DINE_IN" &&
                            itemDocs.any { (it.getLong("guestNumber") ?: 0L) > 0L }

                        if (hasGuests) {
                            val grouped = itemDocs.groupBy { (it.getLong("guestNumber") ?: 0L).toInt() }
                            for (guestNum in grouped.keys.sorted()) {
                                if (guestNum > 0) {
                                    val name = guestNames.getOrNull(guestNum - 1)?.takeIf { it.isNotBlank() }
                                    listItems.add(OrderListItem.GuestHeader(guestNum, name))
                                }
                                grouped[guestNum]?.forEach { listItems.add(OrderListItem.Item(it)) }
                            }
                        } else {
                            itemDocs.forEach { listItems.add(OrderListItem.Item(it)) }
                        }

                        adapter.notifyDataSetChanged()

                        if (listItems.isEmpty()) {
                            txtEmptyItems.visibility = View.VISIBLE
                            recycler.visibility = View.GONE
                        } else {
                            txtEmptyItems.visibility = View.GONE
                            recycler.visibility = View.VISIBLE
                        }
                    }
            }
    }

    // ===============================
    // VOID
    // ===============================

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
                val transactionId = orderDoc.getString("saleTransactionId")
                    ?: orderDoc.getString("transactionId")
                    ?: run {
                        Toast.makeText(this, "No transaction linked to this order.", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->
                        if (!txDoc.exists()) {
                            Toast.makeText(this, "Transaction not found.", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }
                        @Suppress("UNCHECKED_CAST")
                        val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val txType = txDoc.getString("type") ?: "SALE"
                        if (txType == "PRE_AUTH") {
                            Toast.makeText(this, "Pre-authorization cannot be voided. Capture the tab first, then void the capture if needed.", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }
                        val payments = paymentsRaw.map { p ->
                            val amountCents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
                            TransactionPayment(
                                paymentType = p["paymentType"]?.toString() ?: "",
                                cardBrand = p["cardBrand"]?.toString() ?: "",
                                last4 = p["last4"]?.toString() ?: "",
                                entryType = p["entryType"]?.toString() ?: "",
                                amountInCents = amountCents,
                                referenceId = p["referenceId"]?.toString() ?: p["terminalReference"]?.toString() ?: "",
                                clientReferenceId = p["clientReferenceId"]?.toString() ?: "",
                                authCode = p["authCode"]?.toString() ?: "",
                                batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                                transactionNumber = (p["transactionNumber"] as? Number)?.toString() ?: p["transactionNumber"]?.toString() ?: "",
                                paymentId = p["paymentId"]?.toString() ?: ""
                            )
                        }.ifEmpty {
                            val firstRef = getReferenceIdFromTransaction(txDoc)
                            val firstType = getPaymentTypeFromTransaction(txDoc).ifBlank { "Credit" }
                            val amountCents = txDoc.getLong("totalPaidInCents") ?: 0L
                            listOf(
                                TransactionPayment(
                                    paymentType = firstType,
                                    amountInCents = amountCents,
                                    referenceId = firstRef,
                                    clientReferenceId = txDoc.getString("clientReferenceId") ?: "",
                                    batchNumber = txDoc.getString("batchNumber") ?: "",
                                    transactionNumber = (txDoc.get("transactionNumber") as? Number)?.toString() ?: ""
                                )
                            )
                        }

                        val cashPayments = payments.filter { it.paymentType.equals("Cash", true) }
                        val cardPayments = payments.filter { !it.paymentType.equals("Cash", true) }
                        val totalCashCents = cashPayments.sumOf { it.amountInCents }
                        val totalCashDollars = totalCashCents / 100.0

                        if (totalCashCents > 0) {
                            AlertDialog.Builder(this)
                                .setTitle("Cash return required")
                                .setMessage("Return $%.2f in cash to the customer before completing the void.".format(totalCashDollars))
                                .setPositiveButton("I have returned the cash") { _, _ ->
                                    runVoidSequence(transactionId, cardPayments)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            runVoidSequence(transactionId, cardPayments)
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load transaction: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun runVoidSequence(txDocId: String, cardPayments: List<TransactionPayment>) {
        if (cardPayments.isEmpty()) {
            finalizeVoid(txDocId)
            return
        }
        voidCardPaymentsSequentially(txDocId, cardPayments, 0)
    }

    private fun voidCardPaymentsSequentially(
        txDocId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        if (index >= cardPayments.size) {
            finalizeVoid(txDocId)
            return
        }
        val payment = cardPayments[index]
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        if (refId.isBlank()) {
            Toast.makeText(this, "Cannot void: no ReferenceId for card payment.", Toast.LENGTH_LONG).show()
            return
        }
        callVoidApiForPayment(payment, txDocId, cardPayments, index)
    }

    private fun callVoidApiForPayment(
        payment: TransactionPayment,
        transactionId: String,
        cardPayments: List<TransactionPayment>,
        index: Int
    ) {
        val refId = payment.referenceId.ifBlank { payment.clientReferenceId }
        val amount = payment.amountInCents / 100.0
        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", payment.paymentType.ifBlank { "Credit" })
            put("ReferenceId", refId)
            if (payment.authCode.isNotBlank()) put("AuthCode", payment.authCode)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(this@OrderDetailActivity))
            put("RegisterId", TerminalPrefs.getRegisterId(this@OrderDetailActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@OrderDetailActivity))
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
        }
        Log.d("TX_API", "[VOID_REQ] ${json.toString()}")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = json.toString().toRequestBody("application/json".toMediaType())
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
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Void error ${response.code}: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    val resultCode = JSONObject(responseText)
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode")
                    if (resultCode == "0") {
                        voidCardPaymentsSequentially(transactionId, cardPayments, index + 1)
                    } else {
                        Toast.makeText(this@OrderDetailActivity, "Void Declined", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun finalizeVoid(transactionId: String) {
        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->
                var batchId = orderDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                if (batchId.isNullOrBlank()) {
                    db.collection("Transactions").document(transactionId).get()
                        .addOnSuccessListener { txDoc ->
                            batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                            if (!batchId.isNullOrBlank()) {
                                runFinalizeVoidBatch(transactionId, orderDoc, batchId!!)
                            } else {
                                Toast.makeText(this, "Cannot void: batch not found", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Cannot void: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    return@addOnSuccessListener
                }
                runFinalizeVoidBatch(transactionId, orderDoc, batchId!!)
            }
    }

    private fun runFinalizeVoidBatch(transactionId: String, orderDoc: DocumentSnapshot, batchId: String) {
        val txRef = db.collection("Transactions").document(transactionId)
        txRef.get()
            .addOnSuccessListener { txDoc ->
                if (!txDoc.exists()) return@addOnSuccessListener
                val amount = txDoc.getLong("totalPaidInCents")?.let { it / 100.0 }
                    ?: txDoc.getDouble("totalPaid") ?: txDoc.getDouble("amount") ?: 0.0
                @Suppress("UNCHECKED_CAST")
                val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val updatedPayments = paymentsRaw.map { p ->
                    val mutable = p.toMutableMap()
                    mutable["status"] = "VOIDED"
                    mutable
                }

                db.runBatch { batch ->
                    val orderRef = db.collection("Orders").document(orderId)
                    val batchRef = db.collection("Batches").document(batchId)

                    batch.update(txRef, mapOf("voided" to true, "payments" to updatedPayments))

                    val voidedBy = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                        ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)
                    batch.update(orderRef, mapOf(
                        "status" to "VOIDED",
                        "voidedAt" to Date(),
                        "voidedBy" to voidedBy
                    ))

                    batch.update(batchRef, mapOf(
                        "totalSales" to FieldValue.increment(-amount),
                        "netTotal" to FieldValue.increment(-amount),
                        "transactionCount" to FieldValue.increment(-1)
                    ))
                }
                    .addOnSuccessListener {
                        ReceiptPromptHelper.promptForReceipt(
                            this, ReceiptPromptHelper.ReceiptType.VOID, orderId, transactionId
                        ) { loadHeader() }
                    }
            }
    }

    private fun confirmRefund() {

        AlertDialog.Builder(this)
            .setTitle("Refund Order")
            .setMessage("Refund this order?")
            .setPositiveButton("Refund") { _, _ -> executeRefundForAmount(null, finishAfter = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onOrderItemClick(itemDoc: DocumentSnapshot) {
        val lineKey = itemDoc.id
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                val status = orderDoc.getString("status") ?: ""
                if (status != "CLOSED" && status != "REFUNDED") {
                    Toast.makeText(this, "Refund is only available for closed orders.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                if (totalRefundedInCents >= totalInCents && totalInCents > 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val saleTransactionId = orderDoc.getString("saleTransactionId") ?: orderDoc.getString("transactionId")
                if (!saleTransactionId.isNullOrBlank()) {
                    db.collection("Transactions")
                        .whereEqualTo("type", "REFUND")
                        .whereEqualTo("originalReferenceId", saleTransactionId)
                        .get()
                        .addOnSuccessListener { refundSnap ->
                            val refundedLineKeys = refundSnap.documents
                                .mapNotNull { it.getString("refundedLineKey")?.takeIf { k -> k.isNotBlank() } }
                                .toSet()
                            if (lineKey in refundedLineKeys) {
                                Toast.makeText(this, "This item has already been refunded.", Toast.LENGTH_SHORT).show()
                                return@addOnSuccessListener
                            }
                            val lineTotalInCents = itemDoc.getLong("lineTotalInCents") ?: 0L
                            val itemName = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: ""
                            val alreadyRefundedByNameAndAmount = refundSnap.documents.any { ref ->
                                ref.getString("refundedLineKey")?.isNotBlank() != true &&
                                ref.getLong("amountInCents") == lineTotalInCents &&
                                ref.getString("refundedItemName")?.trim() == itemName.trim()
                            }
                            if (alreadyRefundedByNameAndAmount) {
                                Toast.makeText(this, "This item has already been refunded.", Toast.LENGTH_SHORT).show()
                                return@addOnSuccessListener
                            }
                            val refundedNameAmountKeys = refundSnap.documents
                                .filter { it.getString("refundedLineKey")?.isNotBlank() != true }
                                .mapNotNull { ref ->
                                    val refName = ref.getString("refundedItemName")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                    val refAmount = ref.getLong("amountInCents") ?: return@mapNotNull null
                                    "$refName|$refAmount"
                                }.toSet()
                            proceedWithItemRefundDialog(orderDoc, itemDoc, lineKey, refundedLineKeys, refundedNameAmountKeys)
                        }
                } else {
                    proceedWithItemRefundDialog(orderDoc, itemDoc, lineKey, emptySet(), emptySet())
                }
            }
    }

    private fun proceedWithItemRefundDialog(
        orderDoc: DocumentSnapshot,
        itemDoc: DocumentSnapshot,
        lineKey: String,
        refundedLineKeys: Set<String>,
        refundedNameAmountKeys: Set<String>
    ) {
        val lineTotalInCents = itemDoc.getLong("lineTotalInCents") ?: 0L
        if (lineTotalInCents <= 0L) {
            Toast.makeText(this, "This item has no amount to refund.", Toast.LENGTH_SHORT).show()
            return
        }
        val itemName = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: "Item"

        val otherUnrefundedItems = itemDocs.filter { doc ->
            if (doc.id == lineKey) return@filter false
            if (doc.id in refundedLineKeys) return@filter false
            val docName = (doc.getString("name") ?: doc.getString("itemName") ?: "").trim()
            val docLineCents = doc.getLong("lineTotalInCents") ?: 0L
            if ("$docName|$docLineCents" in refundedNameAmountKeys) return@filter false
            true
        }

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
        val remainingBalance = totalInCents - totalRefundedInCents

        val refundAmount = if (otherUnrefundedItems.isEmpty() && remainingBalance > lineTotalInCents) {
            remainingBalance
        } else {
            lineTotalInCents
        }

        val amountStr = String.format(Locale.US, "%.2f", refundAmount / 100.0)
        val message = if (refundAmount > lineTotalInCents) {
            "Refund \"$itemName\" for \$$amountStr? (includes tax/fees)"
        } else {
            "Refund \"$itemName\" for \$$amountStr?"
        }

        AlertDialog.Builder(this)
            .setTitle("Refund item")
            .setMessage(message)
            .setPositiveButton("Refund") { _, _ ->
                executeRefundForAmount(refundAmount, finishAfter = false, refundedItemName = itemName, refundedLineKey = lineKey)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeRefundForAmount(amountInCents: Long?, finishAfter: Boolean, refundedItemName: String? = null, refundedLineKey: String? = null) {

        db.collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                if (!orderDoc.exists()) return@addOnSuccessListener

                val status = orderDoc.getString("status") ?: ""
                if (status == "REFUNDED") {
                    Toast.makeText(this, "Order already refunded", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val transactionId = orderDoc.getString("saleTransactionId")
                    ?: orderDoc.getString("transactionId")
                    ?: return@addOnSuccessListener

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->

                        val fullAmountInCents = txDoc.getLong("totalPaidInCents") ?: 0L
                        val refundAmountInCents = amountInCents ?: fullAmountInCents
                        if (refundAmountInCents <= 0L) return@addOnSuccessListener
                        if (refundAmountInCents > fullAmountInCents) {
                            Toast.makeText(this, "Refund amount cannot exceed transaction amount.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val payments = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val firstPayment = payments.firstOrNull()
                        val paymentType = firstPayment?.get("paymentType")?.toString() ?: "Credit"
                        val referenceId = firstPayment?.get("referenceId")?.toString()
                            ?: firstPayment?.get("terminalReference")?.toString()

                        if (paymentType.equals("Cash", ignoreCase = true)) {
                            finalizeRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName, refundedLineKey)
                            return@addOnSuccessListener
                        }

                        if (!referenceId.isNullOrBlank()) {
                            callRefundApi(
                                referenceId = referenceId,
                                paymentType = paymentType,
                                amount = refundAmountInCents / 100.0,
                                originalTransactionId = transactionId,
                                amountInCents = refundAmountInCents,
                                finishAfter = finishAfter,
                                refundedItemName = refundedItemName,
                                refundedLineKey = refundedLineKey
                            )
                        } else {
                            Toast.makeText(this, "Cannot refund: no reference for this transaction.", Toast.LENGTH_LONG).show()
                        }
                    }
            }
    }
    private fun callRefundApi(
        referenceId: String,
        paymentType: String,
        amount: Double,
        originalTransactionId: String,
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null,
        refundedLineKey: String? = null
    ) {
        // Debit refunds use the same flow as credit (Credit Return); do not send Debit Return (Z8)
        val requestPaymentType = if (paymentType.equals("Debit", true)) "Credit" else paymentType
        val json = JSONObject().apply {
            put("Amount", amount)
            put("PaymentType", requestPaymentType)
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", TerminalPrefs.getTpn(this@OrderDetailActivity))
            put("Authkey", TerminalPrefs.getAuthKey(this@OrderDetailActivity))
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://spinpos.net/v2/Payment/Return")
            .post(body)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "Refund Failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val responseText = response.body?.string() ?: ""

                runOnUiThread {

                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Refund error ${response.code}: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    try {
                        val jsonResponse = JSONObject(responseText)
                        val general = jsonResponse.optJSONObject("GeneralResponse")
                        val resultCode = general?.optString("ResultCode") ?: ""

                        if (resultCode == "0") {
                            finalizeRefund(originalTransactionId, amountInCents, finishAfter, refundedItemName, refundedLineKey)
                        } else {
                            Toast.makeText(
                                this@OrderDetailActivity,
                                "Refund Declined",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "Refund parse error: $responseText",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }
    private fun showEmailReceiptDialog() {
        val input = EditText(this).apply {
            hint = "Enter email address"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Email Receipt")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Please enter an email", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendReceiptEmail(email, orderId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendReceiptEmail(email: String, orderId: String) {
        val data = hashMapOf("email" to email, "orderId" to orderId)

        FirebaseFunctions.getInstance()
            .getHttpsCallable("sendReceiptEmail")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Toast.makeText(this, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to send receipt", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send receipt. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun finalizeRefund(
        originalTransactionId: String,
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null,
        refundedLineKey: String? = null
    ) {

        // 1️⃣ Find open batch
        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { batchSnap ->

                if (batchSnap.isEmpty) {
                    Toast.makeText(this, "Open a batch first", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val openBatch = batchSnap.documents.first()
                val openBatchId = openBatch.id

                val orderRef = db.collection("Orders").document(orderId)
                orderRef.get()
                    .addOnSuccessListener { orderDoc ->
                        if (!orderDoc.exists()) return@addOnSuccessListener
                        val employeeName = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                            ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)

                        val newRefundTxRef = db.collection("Transactions").document()
                        val batchRef = db.collection("Batches").document(openBatchId)

                        val refundDocData = buildMap<String, Any> {
                            put("orderId", orderId)
                            put("type", "REFUND")
                            put("amount", amountInCents / 100.0)
                            put("amountInCents", amountInCents)
                            put("originalReferenceId", originalTransactionId)
                            put("createdAt", Date())
                            put("batchId", openBatchId)
                            put("voided", false)
                            put("settled", false)
                            put("refundedBy", employeeName)
                            refundedItemName?.takeIf { it.isNotBlank() }?.let { put("refundedItemName", it) }
                            refundedLineKey?.takeIf { it.isNotBlank() }?.let { put("refundedLineKey", it) }
                        }
                        db.collection("Transactions").document(newRefundTxRef.id).set(refundDocData)
                            .addOnSuccessListener {
                                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                                val currentRefunded = orderDoc.getLong("totalRefundedInCents") ?: 0L
                                val newTotalRefunded = currentRefunded + amountInCents
                                val orderUpdates = mutableMapOf<String, Any>(
                                    "totalRefundedInCents" to newTotalRefunded,
                                    "refundedAt" to Date()
                                )
                                if (newTotalRefunded >= totalInCents) {
                                    orderUpdates["status"] = "REFUNDED"
                                }
                                orderRef.update(orderUpdates)
                                    .addOnSuccessListener {
                                        db.collection("Batches").document(openBatchId)
                                            .update(mapOf(
                                                "totalRefundsInCents" to FieldValue.increment(amountInCents),
                                                "netTotalInCents" to FieldValue.increment(-amountInCents),
                                                "transactionCount" to FieldValue.increment(1)
                                            ))
                                            .addOnSuccessListener {
                                                ReceiptPromptHelper.promptForReceipt(
                                                    this, ReceiptPromptHelper.ReceiptType.REFUND, orderId, originalTransactionId
                                                ) {
                                                    if (finishAfter) finish() else { loadHeader(); loadItems() }
                                                }
                                            }
                                    }
                            }
                    }
            }
    }
}