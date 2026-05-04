@file:SuppressLint("HardcodedText", "NotifyDataSetChanged")
@file:Suppress("SpellCheckingInspection")

package com.ernesto.myapplication

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.firebase.firestore.ListenerRegistration
import com.ernesto.myapplication.data.TransactionPayment
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import android.widget.EditText
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.SessionEmployee
import com.ernesto.myapplication.engine.OrderEngine
import com.ernesto.myapplication.engine.SplitReceiptPayload
import com.ernesto.myapplication.engine.SplitReceiptReprintHelper
import com.ernesto.myapplication.engine.PaymentService
import com.ernesto.myapplication.payments.SpinGatewayP
import com.ernesto.myapplication.payments.TransactionVoidReferenceResolver
import android.graphics.Typeface
import com.google.firebase.Timestamp
import com.google.firebase.functions.FirebaseFunctions
import kotlin.math.roundToLong

class OrderDetailActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val orderEngine = OrderEngine(FirebaseFirestore.getInstance())
    private lateinit var paymentService: PaymentService
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
    private lateinit var btnReceipt: MaterialButton
    private lateinit var btnTipAdjust: MaterialButton
    private lateinit var txtAddCustomer: TextView
    private lateinit var txtOrderType: TextView
    private lateinit var txtSplitBanner: TextView
    private lateinit var orderSummaryContainer: LinearLayout
    private lateinit var txtSubtotal: TextView
    private lateinit var txtOriginalTotal: TextView
    private lateinit var refundedSummaryRow: LinearLayout
    private lateinit var txtRefundedAmount: TextView
    private lateinit var txtRemainingTotal: TextView
    private lateinit var discountSummaryContainer: LinearLayout
    private lateinit var taxBreakdownContainer: LinearLayout
    private lateinit var tipRow: LinearLayout
    private lateinit var txtTipLabel: TextView
    private lateinit var txtTipAmount: TextView
    private lateinit var partialRefundContainer: LinearLayout
    private lateinit var txtPartialRefundAmount: TextView
    private lateinit var txtPartialRefundBy: TextView
    private lateinit var txtPartialRefundDate: TextView

    private lateinit var uberActionsContainer: LinearLayout
    private lateinit var btnAcceptUber: MaterialButton
    private lateinit var btnDenyUber: MaterialButton

    private lateinit var adapter: OrderItemsAdapter
    private val listItems = mutableListOf<OrderListItem>()
    private val itemDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var orderId: String
    private var currentBatchId: String? = null
    private var orderType: String = ""
    private var saleTransactionId: String? = null
    private var lastOrderStatusForBadge: String = ""
    private var dashboardListener: ListenerRegistration? = null
    private var orderItemsListener: ListenerRegistration? = null

    private val voidSequenceHandler = Handler(Looper.getMainLooper())

    private enum class ReceiptContentType { ORIGINAL, REFUND, VOID }

    private sealed class OriginalPrintSplitChoice {
        object CombinedReceipt : OriginalPrintSplitChoice()
        data class PersonReceipt(val payload: SplitReceiptPayload) : OriginalPrintSplitChoice()
    }

    private var originalPrintSplitChoice: OriginalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
    private var pendingBluetoothPrintAction: (() -> Unit)? = null

    companion object {
        private const val REQUEST_BT_CONNECT = 1001

        // Below this elapsed time since Restaurant Accepted, the "Mark Ready"
        // confirmation dialog escalates to a strong warning. Marking an Uber
        // order ready prematurely triggers courier dispatch against food that
        // isn't actually ready, which is the leading cause of merchant-side
        // cancellations on the Uber Eats integration.
        private const val MIN_PREP_SECONDS_WARNING: Long = 60L
        /** SPIn often returns "Service Busy" if the next void is sent before the first completes. */
        private const val VOID_GAP_BETWEEN_LEGS_MS = 1_800L
        private const val VOID_BUSY_MAX_RETRIES = 5
        private const val VOID_BUSY_RETRY_BASE_MS = 2_000L
    }

    private val tableSelectLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val tableId = data.getStringExtra("tableId") ?: ""
                val tableName = data.getStringExtra("tableName") ?: ""
                val tableLayoutId = data.getStringExtra("tableLayoutId") ?: ""
                val sectionId = data.getStringExtra("sectionId") ?: ""
                val sectionName = data.getStringExtra("sectionName") ?: ""
                val guestCount = data.getIntExtra("guestCount", 0)
                val guestNames = data.getStringArrayListExtra("guestNames") ?: arrayListOf()

                switchToDineIn(tableId, tableName, tableLayoutId, sectionId, sectionName, guestCount, guestNames)
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
        btnReceipt = findViewById(R.id.btnReceipt)
        btnTipAdjust = findViewById(R.id.btnTipAdjust)
        txtAddCustomer = findViewById(R.id.txtAddCustomer)
        paymentService = PaymentService(this)
        txtOrderType = findViewById(R.id.txtOrderType)
        txtSplitBanner = findViewById(R.id.txtSplitBanner)
        orderSummaryContainer = findViewById(R.id.orderSummaryContainer)
        txtSubtotal = findViewById(R.id.txtSubtotal)
        txtOriginalTotal = findViewById(R.id.txtOriginalTotal)
        refundedSummaryRow = findViewById(R.id.refundedSummaryRow)
        txtRefundedAmount = findViewById(R.id.txtRefundedAmount)
        txtRemainingTotal = findViewById(R.id.txtRemainingTotal)
        discountSummaryContainer = findViewById(R.id.discountSummaryContainer)
        taxBreakdownContainer = findViewById(R.id.taxBreakdownContainer)
        tipRow = findViewById(R.id.tipRow)
        txtTipLabel = findViewById(R.id.txtTipLabel)
        txtTipAmount = findViewById(R.id.txtTipAmount)
        partialRefundContainer = findViewById(R.id.partialRefundContainer)
        txtPartialRefundAmount = findViewById(R.id.txtPartialRefundAmount)
        txtPartialRefundBy = findViewById(R.id.txtPartialRefundBy)
        txtPartialRefundDate = findViewById(R.id.txtPartialRefundDate)
        uberActionsContainer = findViewById(R.id.uberActionsContainer)
        btnAcceptUber = findViewById(R.id.btnAcceptUber)
        btnDenyUber = findViewById(R.id.btnDenyUber)

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
                outRect.set(0, 1, 0, 1)
            }
        })

        loadHeader()
        attachOrderItemsRealtimeListener()

        dashboardListener = DashboardConfigManager.listenConfig(
            db,
            onUpdate = { modules ->
                OrderTypeColorResolver.updateFromDashboard(modules)
                if (lastOrderStatusForBadge.isNotEmpty()) {
                    runOnUiThread { updateOrderTypeBadge(lastOrderStatusForBadge) }
                }
            },
        )
    }

    override fun onDestroy() {
        dashboardListener?.remove()
        dashboardListener = null
        orderItemsListener?.remove()
        orderItemsListener = null
        super.onDestroy()
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
        txtSplitBanner.visibility = View.GONE
        btnCheckout.visibility = View.GONE
        bottomActions.visibility = View.GONE
        btnVoid.visibility = View.GONE
        btnRefund.visibility = View.GONE
        btnReceipt.visibility = View.GONE
        btnTipAdjust.visibility = View.GONE
        uberActionsContainer.visibility = View.GONE
        btnAcceptUber.visibility = View.VISIBLE
        btnAcceptUber.isEnabled = true
        btnAcceptUber.text = getString(R.string.order_detail_accept)
        btnDenyUber.visibility = View.VISIBLE
        btnDenyUber.isEnabled = true
        btnDenyUber.text = getString(R.string.order_detail_deny)

        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { doc ->

                if (!doc.exists()) return@addOnSuccessListener

                val status = doc.getString("status") ?: ""
                val employee = doc.getString("employeeName") ?: "Unknown"
                val customerName = doc.getString("customerName") ?: ""
                val orderNumber = doc.getLong("orderNumber") ?: 0L

                if (orderNumber > 0L) {
                    txtHeaderOrderNumber.text = getString(R.string.order_detail_order_number, orderNumber)
                    txtHeaderOrderNumber.visibility = View.VISIBLE
                } else {
                    txtHeaderOrderNumber.visibility = View.GONE
                }

                if (status == "VOIDED") {
                    val voidedBy = doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: "—"
                    txtHeaderEmployee.text = getString(R.string.order_detail_voided_by, voidedBy)
                    val voidedAt = doc.getTimestamp("voidedAt")
                    txtHeaderTime.text = if (voidedAt != null) {
                        SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US).format(voidedAt.toDate())
                    } else {
                        ""
                    }
                } else {
                    txtHeaderEmployee.text = getString(R.string.order_detail_employee, employee)
                    val createdAt = doc.getTimestamp("createdAt")
                    txtHeaderTime.text = if (createdAt != null) {
                        SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US).format(createdAt.toDate())
                    } else {
                        ""
                    }
                }

                if (customerName.isNotBlank()) {
                    txtHeaderCustomer.text = getString(R.string.order_detail_customer, customerName)
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
                saleTransactionId = doc.getString("saleTransactionId") ?: doc.getString("transactionId")

                displayOrderSummary(doc, status)
                lastOrderStatusForBadge = status
                updateOrderTypeBadge(status)
                refreshSplitBanner(saleTransactionId)

                if (status == "OPEN") {
                    if (orderType == "UBER_EATS") {
                        uberActionsContainer.visibility = View.VISIBLE
                        btnAcceptUber.setOnClickListener { acceptUberOrder() }
                        btnDenyUber.setOnClickListener { confirmDenyUberOrder() }
                    } else {
                        btnCheckout.visibility = View.VISIBLE
                        btnCheckout.setOnClickListener {
                            val i = Intent(this, MenuActivity::class.java)
                            i.putExtra("ORDER_ID", orderId)
                            if (orderType.isNotBlank()) {
                                i.putExtra("orderType", orderType)
                            }
                            if (OnlineOrderStatusDisplay.isUnpaidWebOnlineOrder(doc)) {
                                i.putExtra(MenuActivity.EXTRA_CART_FIRST_UNPAID_ONLINE, true)
                            }
                            startActivity(i)
                        }
                    }
                }

                if (orderType == "UBER_EATS" && status == "ACCEPTED") {
                    uberActionsContainer.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.txtUberBanner).text =
                        getString(R.string.order_detail_uber_banner_accepted)
                    btnAcceptUber.text = getString(R.string.order_detail_ready_pickup)
                    btnAcceptUber.setOnClickListener { confirmMarkUberOrderReady() }
                    btnDenyUber.text = getString(R.string.order_detail_cancel)
                    btnDenyUber.setOnClickListener { confirmCancelUberOrder() }
                }

                if (orderType == "UBER_EATS" && status == "READY") {
                    uberActionsContainer.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.txtUberBanner).text =
                        getString(R.string.order_detail_uber_banner_ready)
                    btnAcceptUber.visibility = View.GONE
                    btnDenyUber.text = getString(R.string.order_detail_cancel)
                    btnDenyUber.setOnClickListener { confirmCancelUberOrder() }
                }

                if (status == "VOIDED") {
                    bottomActions.visibility = View.VISIBLE
                    btnVoid.visibility = View.GONE
                    btnRefund.visibility = View.GONE
                    btnReceipt.visibility = View.VISIBLE
                    btnReceipt.setOnClickListener { showOrderReceiptFlow() }
                    txtRefundHistory.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val totalInCents = doc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val isFullyRefunded = totalRefundedInCents >= totalInCents && totalInCents > 0L

                loadRefundHistory(saleTransactionId) { totalFromRefunds, refundedLineKeys, refundedNameAmountKeys, lineKeyToEmployee, nameAmountToEmployee, lineKeyToDate, nameAmountToDate, wholeOrderRefundEmployee, wholeOrderRefundDate ->
                    val fullyRefunded = isFullyRefunded || (totalFromRefunds >= totalInCents && totalInCents > 0L)
                    txtRefundHistory.visibility = View.GONE

                    val effectiveWholeEmployee = if (fullyRefunded) wholeOrderRefundEmployee else null
                    val effectiveWholeDate = if (fullyRefunded) wholeOrderRefundDate else null
                    adapter.setRefundedKeys(refundedLineKeys, refundedNameAmountKeys, lineKeyToEmployee, nameAmountToEmployee, lineKeyToDate, nameAmountToDate, effectiveWholeEmployee, effectiveWholeDate)

                    if (!fullyRefunded && totalFromRefunds > 0 && wholeOrderRefundEmployee != null) {
                        partialRefundContainer.visibility = View.VISIBLE
                        txtPartialRefundAmount.text = MoneyUtils.centsToDisplay(totalFromRefunds)
                        if (wholeOrderRefundEmployee.isNotBlank() && wholeOrderRefundEmployee != "—") {
                            txtPartialRefundBy.text = getString(
                                R.string.order_detail_partial_refund_by,
                                wholeOrderRefundEmployee
                            )
                            txtPartialRefundBy.visibility = View.VISIBLE
                        } else {
                            txtPartialRefundBy.visibility = View.GONE
                        }
                        if (!wholeOrderRefundDate.isNullOrBlank()) {
                            txtPartialRefundDate.text = wholeOrderRefundDate
                            txtPartialRefundDate.visibility = View.VISIBLE
                        } else {
                            txtPartialRefundDate.visibility = View.GONE
                        }
                    } else {
                        partialRefundContainer.visibility = View.GONE
                    }
                    if (status == "REFUNDED" || fullyRefunded) {
                        bottomActions.visibility = View.VISIBLE
                        btnVoid.visibility = View.GONE
                        btnRefund.visibility = View.GONE
                        btnReceipt.visibility = View.VISIBLE
                        btnReceipt.setOnClickListener { showOrderReceiptFlow() }
                        return@loadRefundHistory
                    }
                    if (status == "CLOSED") {
                        bottomActions.visibility = View.VISIBLE
                        btnRefund.visibility = View.VISIBLE
                        btnRefund.setOnClickListener { confirmRefund() }
                        btnReceipt.visibility = View.VISIBLE
                        btnReceipt.setOnClickListener { showOrderReceiptFlow() }
                        resolveBatchAndShowVoid(saleTransactionId)
                        if (TipConfig.isTipsEnabled(this) && !TipConfig.isTipOnCustomerScreen(this)) {
                            resolveBatchAndShowTipAdjust(saleTransactionId)
                        } else {
                            btnTipAdjust.visibility = View.GONE
                        }
                    }
                }
            }
    }

    // ===============================
    // UBER EATS: ACCEPT / DENY
    // ===============================

    private fun acceptUberOrder() {
        btnAcceptUber.isEnabled = false
        btnDenyUber.isEnabled = false
        db.collection("Orders").document(orderId)
            .update(
                mapOf(
                    "status" to "ACCEPTED",
                    "acceptedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            .addOnSuccessListener {
                Toast.makeText(this, "Order accepted", Toast.LENGTH_SHORT).show()
                uberActionsContainer.visibility = View.GONE
                loadHeader()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                btnAcceptUber.isEnabled = true
                btnDenyUber.isEnabled = true
            }
    }

    private fun confirmDenyUberOrder() {
        AlertDialog.Builder(this)
            .setTitle("Deny Uber Eats Order?")
            .setMessage("This will notify the customer that the order cannot be fulfilled.")
            .setPositiveButton("Deny") { _, _ -> denyUberOrder() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun denyUberOrder() {
        btnAcceptUber.isEnabled = false
        btnDenyUber.isEnabled = false
        db.collection("Orders").document(orderId)
            .update(
                mapOf(
                    "status" to "DENIED",
                    "deniedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "denyReason" to "Order cannot be fulfilled at this time",
                    "denyReasonCode" to "ITEM_AVAILABILITY",
                )
            )
            .addOnSuccessListener {
                Toast.makeText(this, "Order denied", Toast.LENGTH_SHORT).show()
                uberActionsContainer.visibility = View.GONE
                loadHeader()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                btnAcceptUber.isEnabled = true
                btnDenyUber.isEnabled = true
            }
    }

    /**
     * Wraps [markUberOrderReady] with a confirmation dialog. If the order was
     * accepted less than [MIN_PREP_SECONDS_WARNING] seconds ago, the dialog
     * escalates to a strong warning instead of a routine prompt — this prevents
     * accidental rapid-tap dispatches that cause Uber to send a courier before
     * the food is actually ready.
     */
    private fun confirmMarkUberOrderReady() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                val acceptedAt = doc.getTimestamp("acceptedAt")
                val elapsedSeconds = if (acceptedAt != null) {
                    (System.currentTimeMillis() - acceptedAt.toDate().time) / 1000L
                } else {
                    Long.MAX_VALUE
                }

                val isPremature = elapsedSeconds in 0 until MIN_PREP_SECONDS_WARNING

                val title = if (isPremature) "Food really ready?" else "Mark order ready?"
                val message = if (isPremature) {
                    buildString {
                        append("This order was accepted only ")
                        append(elapsedSeconds.coerceAtLeast(0))
                        append(" second")
                        if (elapsedSeconds != 1L) append("s")
                        append(" ago.\n\n")
                        append("Marking it ready now will dispatch an Uber courier ")
                        append("immediately. If the food isn't actually ready when ")
                        append("the courier arrives, the order will likely be ")
                        append("cancelled.\n\n")
                        append("Are you sure the food is ready for pickup?")
                    }
                } else {
                    "Uber will dispatch a courier to pick up this order. Continue?"
                }
                val positiveLabel = if (isPremature) "Yes, mark ready anyway" else "Mark ready"

                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positiveLabel) { _, _ -> markUberOrderReady() }
                    .setNegativeButton("Not yet", null)
                    .show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Could not verify order timing: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun markUberOrderReady() {
        btnAcceptUber.isEnabled = false
        btnDenyUber.isEnabled = false
        db.collection("Orders").document(orderId)
            .update(
                mapOf(
                    "status" to "READY",
                    "readyAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            .addOnSuccessListener {
                Toast.makeText(this, "Order marked ready for pickup", Toast.LENGTH_SHORT).show()
                loadHeader()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                btnAcceptUber.isEnabled = true
                btnDenyUber.isEnabled = true
            }
    }

    private val uberCancelReasons = linkedMapOf(
        "ITEM_ISSUE"                to "Item issue",
        "KITCHEN_CLOSED"            to "Kitchen closed",
        "RESTAURANT_TOO_BUSY"       to "Restaurant too busy",
        "STORE_CLOSED"              to "Store closed",
        "CUSTOMER_CALLED_TO_CANCEL" to "Customer called to cancel",
        "ORDER_VALIDATION"          to "Order validation problem",
        "TECHNICAL_FAILURE"         to "Technical failure",
        "POS_NOT_READY"             to "POS not ready",
        "POS_OFFLINE"               to "POS offline",
        "CAPACITY"                  to "At capacity",
        "ADDRESS"                   to "Address issue",
        "SPECIAL_INSTRUCTIONS"      to "Special instructions issue",
        "PRICING"                   to "Pricing issue",
        "OTHER"                     to "Other",
    )

    private fun confirmCancelUberOrder() {
        val labels = uberCancelReasons.values.toTypedArray()
        val codes  = uberCancelReasons.keys.toList()

        AlertDialog.Builder(this)
            .setTitle("Cancel Uber Eats Order")
            .setItems(labels) { _, which ->
                val selectedCode = codes[which]
                cancelUberOrder(selectedCode)
            }
            .setNegativeButton("Go Back", null)
            .show()
    }

    private fun cancelUberOrder(reason: String) {
        btnAcceptUber.isEnabled = false
        btnDenyUber.isEnabled = false
        db.collection("Orders").document(orderId)
            .update(
                mapOf(
                    "status" to "CANCELLED",
                    "cancelledAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "cancelReason" to reason,
                )
            )
            .addOnSuccessListener {
                Toast.makeText(this, "Order cancelled", Toast.LENGTH_SHORT).show()
                uberActionsContainer.visibility = View.GONE
                loadHeader()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                btnAcceptUber.isEnabled = true
                btnDenyUber.isEnabled = true
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
                .update(updates)
                .addOnSuccessListener {
                    txtHeaderCustomer.text = getString(R.string.order_detail_customer, info.name)
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
                resolveCustomerId(info.name)
            } else {
                val customer = hashMapOf<String, Any>(
                    "name" to info.name,
                    "nameSearch" to CustomerFirestoreHelper.nameSearchKey(info.name),
                    "phone" to info.phone,
                    "email" to info.email,
                    "createdAt" to Timestamp.now(),
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

    private fun resolveCustomerId(name: String) {
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
    private fun displayOrderSummary(orderDoc: DocumentSnapshot, status: String = "") {
        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val isVoided = status == "VOIDED"

        if (totalInCents <= 0L) {
            orderSummaryContainer.visibility = View.GONE
            recomputeAndRefreshSummary()
            return
        }

        val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
        renderSummary(
            totalInCents,
            taxBreakdown,
            tipAmountInCents,
            discountInCents,
            appliedDiscounts,
            totalRefundedInCents,
            isVoided
        )
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
                            val discountInCents = doc.getLong("discountInCents") ?: 0L
                            val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                            @Suppress("UNCHECKED_CAST")
                            val appliedDiscounts = doc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
                            if (totalInCents > 0L) {
                                renderSummary(
                                    totalInCents,
                                    taxBreakdown,
                                    tipAmountInCents,
                                    discountInCents,
                                    appliedDiscounts,
                                    totalRefundedInCents
                                )
                            }
                        }
                    }
            },
            onFailure = { }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderSummary(
        totalInCents: Long,
        taxBreakdown: List<Map<String, Any>>,
        tipAmountInCents: Long = 0L,
        discountInCents: Long = 0L,
        appliedDiscounts: List<Map<String, Any>> = emptyList(),
        totalRefundedInCents: Long = 0L,
        isVoided: Boolean = false
    ) {
        var taxTotalCents = 0L
        discountSummaryContainer.removeAllViews()
        taxBreakdownContainer.removeAllViews()

        val grouped = DiscountDisplay.groupByName(appliedDiscounts)
        val hasDiscounts = discountInCents > 0L || grouped.isNotEmpty()

        if (hasDiscounts) {
            discountSummaryContainer.visibility = View.VISIBLE
            addOrderSummarySectionHeader(
                discountSummaryContainer,
                getString(R.string.order_detail_discounts),
                topMarginDp = 0
            )
            addOrderSummaryDivider(discountSummaryContainer)
            if (grouped.isNotEmpty()) {
                for (gd in grouped) {
                    addOrderSummaryAmountRow(
                        discountSummaryContainer,
                        getString(
                            R.string.order_detail_discount_bullet_label,
                            DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                        ),
                        getString(
                            R.string.order_detail_refunded_amount_neg,
                            MoneyUtils.centsToDisplay(gd.totalCents)
                        )
                    )
                }
            } else if (discountInCents > 0L) {
                addOrderSummaryAmountRow(
                    discountSummaryContainer,
                    getString(R.string.order_detail_discount_line),
                    getString(
                        R.string.order_detail_refunded_amount_neg,
                        MoneyUtils.centsToDisplay(discountInCents)
                    )
                )
            }
        } else {
            discountSummaryContainer.visibility = View.GONE
        }

        if (taxBreakdown.isNotEmpty()) {
            val headerTop = if (hasDiscounts) 8 else 4
            addOrderSummarySectionHeader(
                taxBreakdownContainer,
                getString(R.string.order_detail_taxes),
                topMarginDp = headerTop
            )
            addOrderSummaryDivider(taxBreakdownContainer)
        }

        for (entry in taxBreakdown) {
            val name = entry["name"]?.toString() ?: getString(R.string.order_detail_tax_default)
            val amountCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val rate = (entry["rate"] as? Number)?.toDouble()
            val taxType = entry["taxType"]?.toString() ?: ""
            taxTotalCents += amountCents

            val label = if (taxType == "PERCENTAGE" && rate != null && rate > 0) {
                val pctStr = if (rate % 1.0 == 0.0) rate.toInt().toString()
                else "%.2f".format(rate)
                getString(R.string.order_detail_tax_named_percent, name, pctStr)
            } else {
                name
            }

            addOrderSummaryAmountRow(
                taxBreakdownContainer,
                label,
                MoneyUtils.centsToDisplay(amountCents)
            )
        }

        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents
        txtSubtotal.text = MoneyUtils.centsToDisplay(subtotalCents)

        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(this, tipAmountInCents)) {
            tipRow.visibility = View.VISIBLE
            if (tipAmountInCents > 0L && subtotalCents > 0L) {
                val tipPct = tipAmountInCents.toDouble() / subtotalCents * 100.0
                val pctStr = if (tipPct % 1.0 == 0.0) tipPct.toInt().toString()
                else "%.1f".format(tipPct)
                txtTipLabel.text = getString(R.string.order_detail_tip_with_percent, pctStr)
            } else {
                txtTipLabel.text = getString(R.string.order_detail_tip)
            }
            txtTipAmount.text = MoneyUtils.centsToDisplay(tipAmountInCents)
        } else {
            tipRow.visibility = View.GONE
        }

        txtOriginalTotal.text = MoneyUtils.centsToDisplay(totalInCents)

        if (isVoided) {
            refundedSummaryRow.visibility = View.VISIBLE
            val refundedLabel = refundedSummaryRow.getChildAt(0) as? TextView
            refundedLabel?.text = getString(R.string.order_detail_voided)
            txtRefundedAmount.text = getString(
                R.string.order_detail_refunded_amount_neg,
                MoneyUtils.centsToDisplay(totalInCents)
            )
            txtRemainingTotal.text = MoneyUtils.centsToDisplay(0L)
        } else {
            val remainingCents = (totalInCents - totalRefundedInCents).coerceAtLeast(0L)
            val refundedLabel = refundedSummaryRow.getChildAt(0) as? TextView
            refundedLabel?.text = getString(R.string.order_detail_refunded)
            txtRefundedAmount.text = MoneyUtils.centsToDisplay(totalRefundedInCents)
            refundedSummaryRow.visibility = if (totalRefundedInCents > 0L) View.VISIBLE else View.GONE
            txtRemainingTotal.text = MoneyUtils.centsToDisplay(remainingCents)
        }
        orderSummaryContainer.visibility = View.VISIBLE
    }

    private fun orderSummaryDip(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun addOrderSummarySectionHeader(
        container: LinearLayout,
        title: String,
        topMarginDp: Int,
    ) {
        val textColor = 0xFF555555.toInt()
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = orderSummaryDip(topMarginDp)
                bottomMargin = orderSummaryDip(2)
            }
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        })
    }

    private fun addOrderSummaryDivider(container: LinearLayout) {
        val lineColor = 0xFFE0E0E0.toInt()
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                orderSummaryDip(1)
            ).apply {
                topMargin = orderSummaryDip(2)
                bottomMargin = orderSummaryDip(6)
            }
            setBackgroundColor(lineColor)
        })
    }

    private fun addOrderSummaryAmountRow(
        container: LinearLayout,
        leftLabel: String,
        rightAmount: String,
    ) {
        val textColor = 0xFF555555.toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = orderSummaryDip(4) }
        }
        row.addView(TextView(this@OrderDetailActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = leftLabel
            textSize = 14f
            setTextColor(textColor)
        })
        row.addView(TextView(this@OrderDetailActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = rightAmount
            textSize = 14f
            setTextColor(textColor)
        })
        container.addView(row)
    }

    private var refundHistoryLines: String = ""

    private fun isTransactionEcommerce(txDoc: DocumentSnapshot): Boolean {
        if (txDoc.getBoolean("ecommerce") == true) return true
        val payments = txDoc.get("payments") as? List<*> ?: return false
        return payments.any { p ->
            (p as? Map<*, *>)?.get("entryType")?.toString()?.equals("ECOMMERCE", true) == true
        }
    }

    /**
     * For CLOSED orders: load transaction to check if all-cash (then no Void), then resolve batchId
     * and show VOID button only when batch.closed == false and payment was not all cash.
     * Ecommerce (online HPP) still uses the SPIn void API so Dejavoo/gateway matches Firestore;
     * only the “batch must be open” gate is skipped so Void stays available after batch close.
     */
    private fun resolveBatchAndShowVoid(saleTransactionId: String?) {
        if (saleTransactionId.isNullOrBlank()) {
            btnVoid.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(saleTransactionId).get()
            .addOnSuccessListener { txDoc ->
                if (isTransactionEcommerce(txDoc)) {
                    btnVoid.visibility = View.VISIBLE
                    btnVoid.setOnClickListener { confirmVoid() }
                    return@addOnSuccessListener
                }
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

    // ===============================
    // TIP ADJUST
    // ===============================

    private fun resolveBatchAndShowTipAdjust(saleTransactionId: String?) {
        if (saleTransactionId.isNullOrBlank()) {
            btnTipAdjust.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(saleTransactionId).get()
            .addOnSuccessListener { txDoc ->
                if (!txDoc.exists()) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }
                if (isTransactionAllCash(txDoc)) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }

                val refId = getReferenceIdFromTransaction(txDoc)
                if (refId.isBlank()) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }

                val batchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                    ?: currentBatchId?.takeIf { it.isNotBlank() }
                if (batchId.isNullOrBlank()) { btnTipAdjust.visibility = View.GONE; return@addOnSuccessListener }

                db.collection("Batches").document(batchId).get()
                    .addOnSuccessListener { batchDoc ->
                        val batchClosed = batchDoc.getBoolean("closed") ?: true
                        if (batchClosed) {
                            btnTipAdjust.visibility = View.GONE
                            return@addOnSuccessListener
                        }

                        val existingTipCents = txDoc.getLong("tipAmountInCents") ?: 0L
                        btnTipAdjust.text = if (existingTipCents > 0L) {
                            getString(R.string.order_detail_tip_adjust)
                        } else {
                            getString(R.string.order_detail_tip_add)
                        }
                        btnTipAdjust.visibility = View.VISIBLE
                        btnTipAdjust.setOnClickListener {
                            showTipAdjustDialog(saleTransactionId, txDoc, batchId)
                        }
                    }
                    .addOnFailureListener { btnTipAdjust.visibility = View.GONE }
            }
            .addOnFailureListener { btnTipAdjust.visibility = View.GONE }
    }

    @SuppressLint("NewApi")
    private fun showTipAdjustDialog(saleTransactionId: String, txDoc: DocumentSnapshot, batchId: String) {
        val existingTipCents = txDoc.getLong("tipAmountInCents") ?: 0L
        val title = if (existingTipCents > 0L) "Adjust Tip" else "Add Tip"

        val input = EditText(this).apply {
            hint = "Tip amount (e.g. 5.00)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
            if (existingTipCents > 0L) {
                setText("%.2f".format(existingTipCents / 100.0))
                selectAll()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(if (existingTipCents > 0L) "Current tip: ${MoneyUtils.centsToDisplay(existingTipCents)}" else null)
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val tipStr = input.text.toString().trim()
                val tipDollars = tipStr.toDoubleOrNull()
                if (tipDollars == null || tipDollars < 0) {
                    Toast.makeText(this, "Please enter a valid tip amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executeTipAdjust(saleTransactionId, txDoc, batchId, tipDollars)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeTipAdjust(saleTransactionId: String, txDoc: DocumentSnapshot, batchId: String, newTipDollars: Double) {
        val newTipCents = MoneyUtils.dollarsToCents(newTipDollars)
        val refId = getReferenceIdFromTransaction(txDoc)
        if (refId.isBlank()) {
            Toast.makeText(this, "Cannot adjust tip: missing reference ID", Toast.LENGTH_LONG).show()
            return
        }

        val totalPaidCents = txDoc.getLong("totalPaidInCents") ?: 0L
        val existingTipCents = txDoc.getLong("tipAmountInCents") ?: 0L
        val baseAmountCents = totalPaidCents - existingTipCents
        val baseAmountDollars = baseAmountCents / 100.0

        Toast.makeText(this, "Processing tip adjustment\u2026", Toast.LENGTH_SHORT).show()

        paymentService.tipAdjust(
            originalAmount = baseAmountDollars,
            tipAmount = newTipDollars,
            referenceId = refId,
            onSuccess = { _ ->
                runOnUiThread {
                    finalizeTipAdjustInFirestore(saleTransactionId, batchId, newTipCents, existingTipCents, baseAmountCents)
                }
            },
            onFailure = { errorMsg ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Tip Adjust Failed")
                        .setMessage(errorMsg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    private fun finalizeTipAdjustInFirestore(
        saleTransactionId: String,
        batchId: String,
        newTipCents: Long,
        oldTipCents: Long,
        baseAmountCents: Long
    ) {
        val txRef = db.collection("Transactions").document(saleTransactionId)
        val batchRef = db.collection("Batches").document(batchId)
        val orderRef = db.collection("Orders").document(orderId)
        val deltaTipCents = newTipCents - oldTipCents

        db.runTransaction { transaction ->
            // Read txRef inside the transaction to ensure consistency.
            transaction.get(txRef)
            val batchSnap = transaction.get(batchRef)
            val orderSnap = transaction.get(orderRef)

            val batchClosed = batchSnap.getBoolean("closed") ?: true
            if (batchClosed) throw Exception("Batch is already closed")

            val newTotalPaidCents = baseAmountCents + newTipCents

            transaction.update(txRef, mapOf(
                "tipAmountInCents" to newTipCents,
                "totalPaidInCents" to newTotalPaidCents,
                "tipAdjusted" to true,
                "tipAdjustedAt" to Timestamp.now()
            ))

            val orderTotalCents = orderSnap.getLong("totalInCents") ?: 0L
            val orderTipCents = orderSnap.getLong("tipAmountInCents") ?: 0L
            val newOrderTotalCents = orderTotalCents - orderTipCents + newTipCents

            transaction.update(orderRef, mapOf(
                "tipAmountInCents" to newTipCents,
                "totalInCents" to newOrderTotalCents
            ))

            val currentBatchTips = batchSnap.getLong("totalTipsInCents") ?: 0L
            transaction.update(batchRef, mapOf(
                "totalTipsInCents" to currentBatchTips + deltaTipCents,
                "netTotalInCents" to FieldValue.increment(deltaTipCents)
            ))

            null
        }.addOnSuccessListener {
            Toast.makeText(this, "Tip adjusted successfully", Toast.LENGTH_SHORT).show()
            loadHeader()
        }.addOnFailureListener { e ->
            Log.e("TIP_ADJUST", "Firestore transaction failed", e)
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Tip was approved by processor but failed to save: ${e.message}\nPlease try again or contact support.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun getPaymentTypeFromTransaction(txDoc: DocumentSnapshot): String {
        val payments = txDoc.get("payments") as? List<*> ?: emptyList<Any>()
        val first = payments.firstOrNull() as? Map<*, *>
        return (first?.get("paymentType")?.toString()?.takeIf { it.isNotBlank() }
            ?: txDoc.getString("paymentType")?.takeIf { it.isNotBlank() }).orEmpty()
    }

    private fun getReferenceIdFromTransaction(txDoc: DocumentSnapshot): String {
        @Suppress("UNCHECKED_CAST")
        val first = (txDoc.get("payments") as? List<*>)?.firstOrNull() as? Map<String, Any>
        val fromLeg = TransactionVoidReferenceResolver.gatewayRefFromPaymentMap(first)
        if (fromLeg.isNotBlank()) return fromLeg
        return TransactionVoidReferenceResolver.firstGatewayRefFromTransactionDoc(txDoc)
    }

    private fun loadRefundHistory(saleTransactionId: String?, onLoaded: (totalRefundedCents: Long, refundedLineKeys: Set<String>, refundedNameAmountKeys: Set<String>, refundedLineKeyToEmployee: Map<String, String>, refundedNameAmountToEmployee: Map<String, String>, refundedLineKeyToDate: Map<String, String>, refundedNameAmountToDate: Map<String, String>, wholeOrderRefundEmployee: String?, wholeOrderRefundDate: String?) -> Unit) {
        refundHistoryLines = ""
        if (saleTransactionId.isNullOrBlank()) {
            onLoaded(0L, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, null)
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
                var wholeOrderRefundEmployee: String? = null
                var wholeOrderRefundDate: String? = null
                for (refundDoc in snap.documents) {
                    val amountCents = refundDoc.getLong("amountInCents")
                        ?: ((refundDoc.getDouble("amount") ?: 0.0) * 100).toLong()
                    totalCents += amountCents
                    val employee = refundDoc.getString("refundedBy")?.trim()?.takeIf { it.isNotBlank() } ?: "—"
                    val createdAt = refundDoc.getTimestamp("createdAt")?.toDate()
                    val dateStr = if (createdAt != null) dateFormat.format(createdAt) else ""
                    val hasLineKey = refundDoc.getString("refundedLineKey")?.isNotBlank() == true
                    val hasItemName = refundDoc.getString("refundedItemName")?.trim()?.isNotBlank() == true

                    if (hasLineKey) {
                        val key = refundDoc.getString("refundedLineKey")!!
                        refundedLineKeys.add(key)
                        refundedLineKeyToEmployee[key] = employee
                        refundedLineKeyToDate[key] = dateStr
                    } else if (hasItemName) {
                        val itemName = refundDoc.getString("refundedItemName")!!.trim()
                        val nameAmountKey = "$itemName|$amountCents"
                        refundedNameAmountKeys.add(nameAmountKey)
                        refundedNameAmountToEmployee[nameAmountKey] = employee
                        refundedNameAmountToDate[nameAmountKey] = dateStr
                    } else {
                        wholeOrderRefundEmployee = employee
                        wholeOrderRefundDate = dateStr
                    }
                }
                onLoaded(totalCents, refundedLineKeys, refundedNameAmountKeys, refundedLineKeyToEmployee, refundedNameAmountToEmployee, refundedLineKeyToDate, refundedNameAmountToDate, wholeOrderRefundEmployee, wholeOrderRefundDate)
            }
            .addOnFailureListener { onLoaded(0L, emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, null) }
    }
    // ===============================
    // LOAD ITEMS
    // ===============================

    private enum class SplitPayBannerKind { EVEN, BY_ITEMS }

    /**
     * Split receipts on sale payments: even splits use the Firestore `originalItemName` field on split
     * receipt line rows (see `SplitReceiptLine`) or a non-empty `SplitReceiptPayload.sharedItemsNote`;
     * split-by-item receipts have line items without original names.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractSplitBannerInfo(payments: List<*>): Pair<SplitPayBannerKind, Int>? {
        for (raw in payments) {
            val p = raw as? Map<String, Any> ?: continue
            val sr = p["splitReceipt"] as? Map<String, Any> ?: continue
            val totalSplits = (sr["totalSplits"] as? Number)?.toInt()?.coerceAtLeast(1) ?: continue
            if (totalSplits < 2) continue
            val sharedNote = sr["sharedItemsNote"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val itemsRaw = sr["items"] as? List<Map<String, Any>> ?: emptyList()
            val hasEvenLineMarkers = itemsRaw.any { row ->
                val o = row["originalItemName"]?.toString()?.trim()
                !o.isNullOrEmpty()
            }
            val isEven = sharedNote != null || hasEvenLineMarkers
            return if (isEven) SplitPayBannerKind.EVEN to totalSplits else SplitPayBannerKind.BY_ITEMS to totalSplits
        }
        return null
    }

    private fun refreshSplitBanner(transactionId: String?) {
        if (transactionId.isNullOrBlank()) {
            txtSplitBanner.visibility = View.GONE
            return
        }
        db.collection("Transactions").document(transactionId).get()
            .addOnSuccessListener { tx ->
                if (!tx.exists()) {
                    txtSplitBanner.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val payments = tx.get("payments") as? List<*> ?: emptyList<Any>()
                val info = extractSplitBannerInfo(payments)
                if (info == null) {
                    txtSplitBanner.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val (kind, people) = info
                txtSplitBanner.text = when (kind) {
                    SplitPayBannerKind.EVEN -> getString(R.string.order_detail_split_evenly, people)
                    SplitPayBannerKind.BY_ITEMS -> getString(R.string.order_detail_split_by_item)
                }
                txtSplitBanner.visibility = View.VISIBLE
                txtSplitBanner.setTextColor(Color.WHITE)
                val bg = GradientDrawable()
                bg.setColor("#6A4FB3".toColorInt())
                bg.cornerRadius = 16f
                txtSplitBanner.background = bg
            }
            .addOnFailureListener {
                txtSplitBanner.visibility = View.GONE
            }
    }

    private fun updateOrderTypeBadge(status: String) {
        val knownTypes = setOf("TO_GO", "DINE_IN", "UBER_EATS", "ONLINE_PICKUP")
        if (orderType !in knownTypes) {
            txtOrderType.visibility = View.GONE
            return
        }

        txtOrderType.visibility = View.VISIBLE
        val label = when (orderType) {
            "TO_GO" -> getString(R.string.order_detail_order_type_to_go)
            "UBER_EATS" -> getString(R.string.order_detail_order_type_uber)
            "ONLINE_PICKUP" -> getString(R.string.order_detail_order_type_online_order)
            else -> getString(R.string.order_detail_order_type_dine_in)
        }
        val bgArgb = OrderTypeColorResolver.colorArgbForOrderType(orderType)

        val isEditable = status == "OPEN" && orderType != "UBER_EATS" && orderType != "ONLINE_PICKUP"
        txtOrderType.text = if (isEditable) {
            getString(R.string.order_detail_order_type_editable, label)
        } else {
            label
        }
        txtOrderType.setTextColor(Color.WHITE)

        val bg = GradientDrawable()
        bg.setColor(bgArgb)
        bg.cornerRadius = 16f
        txtOrderType.background = bg

        if (isEditable) {
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

    private fun switchToDineIn(
        tableId: String,
        tableName: String,
        tableLayoutId: String,
        sectionId: String,
        sectionName: String,
        guestCount: Int,
        guestNames: List<String>
    ) {
        val updates = mutableMapOf<String, Any>(
            "orderType" to "DINE_IN",
            "updatedAt" to Date()
        )
        if (tableId.isNotBlank()) updates["tableId"] = tableId
        if (tableName.isNotBlank()) updates["tableName"] = tableName
        if (tableLayoutId.isNotBlank()) updates["tableLayoutId"] = tableLayoutId
        if (sectionId.isNotBlank()) updates["sectionId"] = sectionId
        if (sectionName.isNotBlank()) updates["sectionName"] = sectionName
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
            "tableLayoutId" to FieldValue.delete(),
            "tableName" to FieldValue.delete(),
            "sectionId" to FieldValue.delete(),
            "sectionName" to FieldValue.delete(),
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

    private fun orderLineDisplayName(doc: DocumentSnapshot): String =
        doc.getString("name") ?: doc.getString("itemName") ?: "Item"

    /**
     * Visual-only grouping for Order Details: same display name → one row (expandable if 2+ line docs).
     * [guestNumberKey] is 0 when not using guest sections; otherwise the guest # for stable expansion keys.
     */
    private fun appendGroupedOrderLinesByName(
        docs: List<DocumentSnapshot>,
        guestNumberKey: Int,
        into: MutableList<OrderListItem>,
    ) {
        if (docs.isEmpty()) return
        val byName = docs.groupBy { orderLineDisplayName(it) }
        val nameOrder = docs.map { orderLineDisplayName(it) }.distinct()
        for (name in nameOrder) {
            val group = byName[name] ?: continue
            if (group.size == 1) {
                into.add(OrderListItem.Item(group.first()))
            } else {
                into.add(OrderListItem.NamedGroup(name, guestNumberKey, group))
            }
        }
    }

    /** Real-time line items (modifiers, per-line `kdsStatus`, payments on line, etc.). */
    private fun attachOrderItemsRealtimeListener() {
        orderItemsListener?.remove()
        val orderRef = db.collection("Orders").document(orderId)
        val itemsRef = orderRef.collection("items")
        orderItemsListener = itemsRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w("OrderDetail", "items snapshot", error)
                return@addSnapshotListener
            }
            val docs = snapshot?.documents ?: emptyList()
            orderRef.get()
                .addOnSuccessListener { orderDoc ->
                    if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@addOnSuccessListener
                    if (!orderDoc.exists()) return@addOnSuccessListener
                    applyOrderItemsToUi(orderDoc, docs)
                }
        }
    }

    private fun applyOrderItemsToUi(orderDoc: DocumentSnapshot, itemDocuments: List<DocumentSnapshot>) {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        @Suppress("UNCHECKED_CAST")
        guestNames = (orderDoc.get("guestNames") as? List<String>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val orderAppliedDiscounts =
            (orderDoc.get("appliedDiscounts") as? List<Map<String, Any>>) ?: emptyList()
        adapter.setAppliedDiscounts(orderAppliedDiscounts)

        itemDocs.clear()
        itemDocs.addAll(itemDocuments)

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
                appendGroupedOrderLinesByName(grouped[guestNum].orEmpty(), guestNum, listItems)
            }
        } else {
            appendGroupedOrderLinesByName(itemDocs, 0, listItems)
        }

        val validGroupKeys = listItems.mapNotNull { (it as? OrderListItem.NamedGroup)?.stableKey() }.toSet()
        adapter.retainExpandedGroupsOnly(validGroupKeys)
        adapter.retainExpandedBatchLineIdsOnly(itemDocs.map { it.id }.toSet())
        adapter.notifyDataSetChanged()

        if (listItems.isEmpty()) {
            txtEmptyItems.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            txtEmptyItems.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    // ===============================
    // VOID
    // ===============================

    private fun confirmVoid() {
        val txId = saleTransactionId
        if (txId != null) {
            db.collection("Transactions").document(txId).get()
                .addOnSuccessListener { txDoc ->
                    if (isTransactionEcommerce(txDoc)) {
                        AlertDialog.Builder(this)
                            .setTitle("Void Online Order")
                            .setMessage(
                                "This sends a void to your payment terminal (SPIn/Dejavoo) using the sale reference. " +
                                    "If the host declines or the sale is already settled, use a refund from the portal or POS instead.",
                            )
                            .setPositiveButton("Void") { _, _ -> finalizeEcommerceVoid(txId) }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return@addOnSuccessListener
                    }
                    confirmVoidWithBatchCheck()
                }
                .addOnFailureListener { confirmVoidWithBatchCheck() }
        } else {
            confirmVoidWithBatchCheck()
        }
    }

    private fun confirmVoidWithBatchCheck() {
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

    /**
     * Online / HPP sale: same Dejavoo+SPIn void as in-store (see [executeVoid]), then Firestore.
     * Previously this path only updated Firestore, so the app showed “voided” while the terminal did not.
     */
    private fun finalizeEcommerceVoid(transactionId: String) {
        startGatewayVoidSequenceForTransaction(transactionId)
    }

    private fun finalizeVoidFirestoreOnly(transactionId: String) {
        val txRef = db.collection("Transactions").document(transactionId)
        val orderRef = db.collection("Orders").document(orderId)
        val voidedBy = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
            ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)

        txRef.get().addOnSuccessListener { txDoc ->
            if (!txDoc.exists()) return@addOnSuccessListener
            @Suppress("UNCHECKED_CAST")
            val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
            val updatedPayments = paymentsRaw.map { p ->
                val mutable = p.toMutableMap()
                mutable["status"] = "VOIDED"
                mutable
            }
            db.runBatch { batch ->
                batch.update(
                    txRef,
                    mapOf(
                        "voided" to true,
                        "voidedBy" to voidedBy,
                        "payments" to updatedPayments,
                    ),
                )
                batch.update(
                    orderRef,
                    mapOf(
                        "status" to "VOIDED",
                        "voidedAt" to Date(),
                        "voidedBy" to voidedBy,
                    ),
                )
            }.addOnSuccessListener {
                ReceiptPromptHelper.promptForReceipt(
                    this, ReceiptPromptHelper.ReceiptType.VOID, orderId, transactionId
                ) { loadHeader() }
            }.addOnFailureListener { e ->
                Toast.makeText(this, "Void saved on terminal but Firestore update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resolveEnrichedCardPaymentsForVoid(
        txDoc: DocumentSnapshot,
        cardPayments: List<TransactionPayment>,
        onReady: (List<TransactionPayment>) -> Unit,
    ) {
        val step1 = TransactionVoidReferenceResolver.enrichPaymentsForVoid(txDoc, cardPayments)
        if (!TransactionVoidReferenceResolver.anyCardLegMissingGatewayRef(step1)) {
            onReady(step1)
            return
        }
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { od ->
                onReady(TransactionVoidReferenceResolver.enrichPaymentsFromOrderDoc(od, step1))
            }
            .addOnFailureListener { onReady(step1) }
    }

    /** Loads [Transactions] doc and runs SPIn void leg(s), then [finalizeVoid]. */
    private fun startGatewayVoidSequenceForTransaction(transactionId: String) {
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
                    Toast.makeText(
                        this,
                        "Pre-authorization cannot be voided. Capture the tab first, then void the capture if needed.",
                        Toast.LENGTH_LONG,
                    ).show()
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
                        referenceId = TransactionVoidReferenceResolver.gatewayRefFromPaymentMap(p),
                        clientReferenceId = TransactionVoidReferenceResolver.clientRefFromPaymentMap(p),
                        authCode = p["authCode"]?.toString() ?: "",
                        batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                        transactionNumber = (p["transactionNumber"] as? Number)?.toString()
                            ?: p["transactionNumber"]?.toString() ?: "",
                        invoiceNumber = p["invoiceNumber"]?.toString() ?: "",
                        pnReferenceId = TransactionVoidReferenceResolver.pnReferenceFromPaymentMap(p),
                        paymentId = p["paymentId"]?.toString() ?: "",
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
                            clientReferenceId = TransactionVoidReferenceResolver.firstClientRefFromTransactionDoc(txDoc),
                            batchNumber = txDoc.getString("batchNumber") ?: "",
                            transactionNumber = (txDoc.get("transactionNumber") as? Number)?.toString() ?: "",
                        ),
                    )
                }

                val cashPayments = payments.filter { it.paymentType.equals("Cash", true) }
                val cardPayments = payments.filter { !it.paymentType.equals("Cash", true) }
                val totalCashCents = cashPayments.sumOf { it.amountInCents }
                val totalCashDollars = totalCashCents / 100.0

                resolveEnrichedCardPaymentsForVoid(txDoc, cardPayments) { enriched ->
                    if (totalCashCents > 0) {
                        AlertDialog.Builder(this)
                            .setTitle("Cash return required")
                            .setMessage(
                                "Return $%.2f in cash to the customer before completing the void.".format(totalCashDollars),
                            )
                            .setPositiveButton("I have returned the cash") { _, _ ->
                                runVoidSequence(transactionId, enriched)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        runVoidSequence(transactionId, enriched)
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load transaction: ${it.message}", Toast.LENGTH_LONG).show()
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
                                referenceId = TransactionVoidReferenceResolver.gatewayRefFromPaymentMap(p),
                                clientReferenceId = TransactionVoidReferenceResolver.clientRefFromPaymentMap(p),
                                authCode = p["authCode"]?.toString() ?: "",
                                batchNumber = (p["batchNumber"] as? Number)?.toString() ?: p["batchNumber"]?.toString() ?: "",
                                transactionNumber = (p["transactionNumber"] as? Number)?.toString() ?: p["transactionNumber"]?.toString() ?: "",
                                invoiceNumber = p["invoiceNumber"]?.toString() ?: "",
                                pnReferenceId = TransactionVoidReferenceResolver.pnReferenceFromPaymentMap(p),
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
                                    clientReferenceId = TransactionVoidReferenceResolver.firstClientRefFromTransactionDoc(txDoc),
                                    batchNumber = txDoc.getString("batchNumber") ?: "",
                                    transactionNumber = (txDoc.get("transactionNumber") as? Number)?.toString() ?: ""
                                )
                            )
                        }

                        val cashPayments = payments.filter { it.paymentType.equals("Cash", true) }
                        val cardPayments = payments.filter { !it.paymentType.equals("Cash", true) }
                        val totalCashCents = cashPayments.sumOf { it.amountInCents }
                        val totalCashDollars = totalCashCents / 100.0

                        resolveEnrichedCardPaymentsForVoid(txDoc, cardPayments) { enriched ->
                            if (totalCashCents > 0) {
                                AlertDialog.Builder(this)
                                    .setTitle("Cash return required")
                                    .setMessage("Return $%.2f in cash to the customer before completing the void.".format(totalCashDollars))
                                    .setPositiveButton("I have returned the cash") { _, _ ->
                                        runVoidSequence(transactionId, enriched)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            } else {
                                runVoidSequence(transactionId, enriched)
                            }
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

    private fun voidLegLabelForDetail(payment: TransactionPayment, index: Int, legCount: Int): String {
        val last4 = payment.last4.trim().ifBlank { "????" }
        return "Card ${index + 1} of $legCount (••••$last4)"
    }

    private fun isHostBusyVoidMessage(msg: String): Boolean = SpinGatewayP.isVoidHostBusyMessage(msg)

    private fun voidCardPaymentsSequentially(
        txDocId: String,
        cardPayments: List<TransactionPayment>,
        index: Int,
        busyRetryOnLeg: Int = 0
    ) {
        if (index >= cardPayments.size) {
            finalizeVoid(txDocId)
            return
        }
        val payment = cardPayments[index]
        callVoidApiForPayment(payment, txDocId, cardPayments, index, busyRetryOnLeg)
    }

    private fun callVoidApiForPayment(
        payment: TransactionPayment,
        transactionId: String,
        cardPayments: List<TransactionPayment>,
        index: Int,
        busyRetryOnLeg: Int = 0
    ) {
        val leg = voidLegLabelForDetail(payment, index, cardPayments.size)
        var refId = payment.referenceId.trim()
        if (refId.isEmpty()) {
            val client = payment.clientReferenceId.trim()
            if (client.isNotEmpty()) {
                Log.w("TX_API", "[$leg] Order detail void: using clientReferenceId (gateway referenceId was blank).")
                refId = client
            }
        }
        if (refId.isEmpty()) {
            Toast.makeText(
                this,
                "Cannot void $leg: this payment is missing the gateway ReferenceId. Check the transaction in Firestore or try void from the Transactions list.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        SpinGatewayP.enqueueVoidPayment(
            this,
            payment,
            refId,
            readTimeoutSeconds = 120,
        ) { result ->
            val leg = voidLegLabelForDetail(payment, index, cardPayments.size)
            runOnUiThread {
                if (result.networkError != null) {
                    Toast.makeText(this@OrderDetailActivity, "Void Failed: ${result.networkError}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val responseText = result.responseBody
                if (result.httpCode !in 200..299) {
                    val reason = SpinGatewayP.voidDeclineMessage(responseText)
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "Void failed (HTTP ${result.httpCode}) $leg: $reason",
                        Toast.LENGTH_LONG,
                    ).show()
                    Log.e("TX_API", "Void HTTP ${result.httpCode} body: $responseText")
                    return@runOnUiThread
                }
                if (result.hostApproved) {
                    val hasMoreLegs = index + 1 < cardPayments.size
                    if (hasMoreLegs) {
                        voidSequenceHandler.postDelayed(
                            { voidCardPaymentsSequentially(transactionId, cardPayments, index + 1, 0) },
                            VOID_GAP_BETWEEN_LEGS_MS,
                        )
                    } else {
                        voidCardPaymentsSequentially(transactionId, cardPayments, index + 1, 0)
                    }
                } else {
                    val reason = SpinGatewayP.voidDeclineMessage(responseText)
                    if (isHostBusyVoidMessage(reason) && busyRetryOnLeg < VOID_BUSY_MAX_RETRIES) {
                        val waitMs = VOID_BUSY_RETRY_BASE_MS * (busyRetryOnLeg + 1)
                        Toast.makeText(
                            this@OrderDetailActivity,
                            "$leg\nHost busy (${reason.trim()}). Retrying in ${waitMs / 1000}s (${busyRetryOnLeg + 1}/$VOID_BUSY_MAX_RETRIES)…",
                            Toast.LENGTH_SHORT,
                        ).show()
                        voidSequenceHandler.postDelayed(
                            { voidCardPaymentsSequentially(transactionId, cardPayments, index, busyRetryOnLeg + 1) },
                            waitMs,
                        )
                        return@runOnUiThread
                    }
                    val partial = if (index > 0) {
                        " Earlier void attempt(s) may have succeeded on the host—check the terminal before trying again."
                    } else {
                        ""
                    }
                    Log.w("TX_API", "Void declined full=$responseText")
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "VOID declined: $leg\n$reason$partial",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
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
                                runFinalizeVoidBatch(transactionId, batchId!!)
                            } else if (isTransactionEcommerce(txDoc)) {
                                finalizeVoidFirestoreOnly(transactionId)
                            } else {
                                Toast.makeText(this, "Cannot void: batch not found", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Cannot void: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    return@addOnSuccessListener
                }
                runFinalizeVoidBatch(transactionId, batchId!!)
            }
    }

    private fun runFinalizeVoidBatch(transactionId: String, batchId: String) {
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

                    val voidedBy = intent.getStringExtra("employeeName")?.takeIf { it.isNotBlank() }
                        ?: SessionEmployee.getEmployeeName(this@OrderDetailActivity)
                    batch.update(txRef, mapOf(
                        "voided" to true,
                        "voidedBy" to voidedBy,
                        "payments" to updatedPayments
                    ))
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
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                val remainingCents = (totalInCents - totalRefundedInCents).coerceAtLeast(0L)
                if (remainingCents <= 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                RefundDialogHelper.showRefundOptionsDialog(this, remainingCents) { amountCents ->
                    executeRefundForAmount(amountCents, finishAfter = true)
                }
            }
    }

    private fun onOrderItemClick(itemDoc: DocumentSnapshot) {
        val lineKey = itemDoc.id
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                val status = orderDoc.getString("status") ?: ""
                if (status != "CLOSED" && status != "REFUNDED") {
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
                            val lineTotalWithTaxInCents = itemDoc.getLong("lineTotalWithTaxInCents")
                            val itemName = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: ""
                            val alreadyRefundedByNameAndAmount = refundSnap.documents.any { ref ->
                                ref.getString("refundedLineKey")?.isNotBlank() != true &&
                                ref.getString("refundedItemName")?.trim() == itemName.trim() &&
                                (ref.getLong("amountInCents") == lineTotalInCents ||
                                 (lineTotalWithTaxInCents != null && ref.getLong("amountInCents") == lineTotalWithTaxInCents))
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

        val storedTotalWithTax = itemDoc.getLong("lineTotalWithTaxInCents")
        val itemRefundBase: Long = if (storedTotalWithTax != null && storedTotalWithTax > 0L) {
            storedTotalWithTax
        } else {
            val orderTotalInCents = orderDoc.getLong("totalInCents") ?: 0L
            val subtotalInCents = itemDocs.sumOf { it.getLong("lineTotalInCents") ?: 0L }
            if (subtotalInCents > 0L && orderTotalInCents > subtotalInCents) {
                (orderTotalInCents.toDouble() * lineTotalInCents / subtotalInCents).roundToLong()
            } else {
                lineTotalInCents
            }
        }

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

        if (remainingBalance <= 0L) {
            Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
            return
        }

        val refundAmount = if (otherUnrefundedItems.isEmpty()) {
            remainingBalance
        } else {
            minOf(itemRefundBase, remainingBalance)
        }

        val amountStr = "%.2f".format(refundAmount / 100.0)
        val includesTax = refundAmount > lineTotalInCents
        val dollar = "$"
        val message = if (includesTax) {
            "Refunding:\n$itemName\n\nAmount:\n$dollar$amountStr (includes tax)"
        } else {
            "Refunding:\n$itemName\n\nAmount:\n$dollar$amountStr"
        }

        AlertDialog.Builder(this)
            .setTitle("Refund Item")
            .setMessage(message)
            .setPositiveButton("Refund") { _, _ ->
                executeRefundForAmount(refundAmount, finishAfter = false, refundedItemName = itemName, refundedLineKey = lineKey)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("UNCHECKED_CAST")
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

                val orderTotalInCents = orderDoc.getLong("totalInCents") ?: 0L
                val alreadyRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
                val remainingRefundable = orderTotalInCents - alreadyRefundedInCents
                if (remainingRefundable <= 0L) {
                    Toast.makeText(this, "Order is already fully refunded.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                db.collection("Transactions")
                    .document(transactionId)
                    .get()
                    .addOnSuccessListener { txDoc ->

                        val fullAmountInCents = txDoc.getLong("totalPaidInCents") ?: 0L
                        val uncappedAmount = amountInCents ?: fullAmountInCents
                        val refundAmountInCents = minOf(uncappedAmount, remainingRefundable)
                        if (refundAmountInCents <= 0L) return@addOnSuccessListener
                        if (refundAmountInCents > fullAmountInCents) {
                            Toast.makeText(this, "Refund amount cannot exceed transaction amount.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        if (isTransactionEcommerce(txDoc)) {
                            finalizeRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName, refundedLineKey, "Credit")
                            return@addOnSuccessListener
                        }

                        val payments = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        val firstPayment = payments.firstOrNull()
                        val paymentType = firstPayment?.get("paymentType")?.toString() ?: "Credit"

                        if (paymentType.equals("Cash", ignoreCase = true)) {
                            finalizeRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName, refundedLineKey)
                            return@addOnSuccessListener
                        }

                        val isPerItemRefund = !refundedLineKey.isNullOrBlank()
                        val txBatchId = txDoc.getString("batchId")?.takeIf { it.isNotBlank() }
                            ?: currentBatchId?.takeIf { it.isNotBlank() }

                        if (txBatchId != null) {
                            db.collection("Batches").document(txBatchId).get()
                                .addOnSuccessListener batchCheck@{ batchDoc ->
                                    val batchIsClosed = batchDoc.getBoolean("closed") ?: true
                                    val useServerRefund = batchIsClosed || !isPerItemRefund
                                    if (useServerRefund) {
                                        callServerRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName, refundedLineKey)
                                        return@batchCheck
                                    }
                                    val leg = SpinGatewayP.cardLegForHostReturnFromTxDoc(txDoc)
                                    if (leg.referenceId.isBlank() && leg.clientReferenceId.isBlank()) {
                                        Toast.makeText(this@OrderDetailActivity, "Cannot refund: no reference for this transaction.", Toast.LENGTH_LONG).show()
                                        return@batchCheck
                                    }
                                    callRefundApi(
                                        leg = leg,
                                        paymentType = paymentType,
                                        amount = refundAmountInCents / 100.0,
                                        originalTransactionId = transactionId,
                                        amountInCents = refundAmountInCents,
                                        finishAfter = finishAfter,
                                        refundedItemName = refundedItemName,
                                        refundedLineKey = refundedLineKey
                                    )
                                }
                                .addOnFailureListener {
                                    val leg = SpinGatewayP.cardLegForHostReturnFromTxDoc(txDoc)
                                    if (leg.referenceId.isBlank() && leg.clientReferenceId.isBlank()) {
                                        Toast.makeText(this@OrderDetailActivity, "Cannot refund: no reference for this transaction.", Toast.LENGTH_LONG).show()
                                        return@addOnFailureListener
                                    }
                                    callRefundApi(
                                        leg = leg,
                                        paymentType = paymentType,
                                        amount = refundAmountInCents / 100.0,
                                        originalTransactionId = transactionId,
                                        amountInCents = refundAmountInCents,
                                        finishAfter = finishAfter,
                                        refundedItemName = refundedItemName,
                                        refundedLineKey = refundedLineKey
                                    )
                                }
                        } else {
                            callServerRefund(transactionId, refundAmountInCents, finishAfter, refundedItemName, refundedLineKey)
                        }
                    }
            }
    }
    private fun callRefundApi(
        leg: TransactionPayment,
        paymentType: String,
        amount: Double,
        originalTransactionId: String,
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null,
        refundedLineKey: String? = null
    ) {
        val requestPaymentType = if (paymentType.equals("Debit", true)) "Credit" else paymentType
        SpinGatewayP.enqueueRefundPayment(this, amount, requestPaymentType, leg) { result ->
            runOnUiThread {
                if (result.networkError != null) {
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "Refund Failed: ${result.networkError}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                if (!result.hostApproved) {
                    val reason = SpinGatewayP.voidDeclineMessage(result.responseBody)
                    Toast.makeText(
                        this@OrderDetailActivity,
                        if (reason.isNotBlank()) "Refund Declined: $reason" else "Refund Declined",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                finalizeRefund(originalTransactionId, amountInCents, finishAfter, refundedItemName, refundedLineKey, paymentType)
            }
        }
    }
    private fun callServerRefund(
        transactionId: String,
        amountInCents: Long,
        finishAfter: Boolean,
        refundedItemName: String? = null,
        refundedLineKey: String? = null
    ) {
        val data = hashMapOf<String, Any>(
            "transactionId" to transactionId,
            "orderId" to orderId,
            "amountInCents" to amountInCents
        )
        refundedLineKey?.takeIf { it.isNotBlank() }?.let { data["refundedLineKey"] = it }
        refundedItemName?.takeIf { it.isNotBlank() }?.let { data["refundedItemName"] = it }

        Toast.makeText(this, "Processing server refund…", Toast.LENGTH_SHORT).show()

        FirebaseFunctions.getInstance()
            .getHttpsCallable("processServerRefund")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    val msg = response["message"]?.toString() ?: "Refund processed."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (finishAfter) finish() else loadHeader()
                } else {
                    val err = response?.get("error")?.toString() ?: "Refund failed."
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Refund failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
    // ===============================
    // RECEIPT FLOW
    // ===============================

    private fun showOrderReceiptFlow() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                val status = doc.getString("status") ?: ""
                val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                val isVoided = status == "VOIDED"
                val hasRefunds = totalRefundedInCents > 0L

                if (!hasRefunds && !isVoided) {
                    showReceiptDeliveryDialog(ReceiptContentType.ORIGINAL)
                } else {
                    showReceiptTypeDialog(isVoided, hasRefunds)
                }
            }
    }

    private fun showReceiptTypeDialog(isVoided: Boolean, hasRefunds: Boolean) {
        val options = mutableListOf("\uD83D\uDCC4  Original Transaction")
        if (isVoided) {
            options.add("\uD83D\uDEAB  Void")
        } else if (hasRefunds) {
            options.add("\u21A9\uFE0F  Refund")
        }
        options.add("Cancel")

        AlertDialog.Builder(this)
            .setTitle("Select Receipt Type")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    options[which].contains("Original") -> showReceiptDeliveryDialog(ReceiptContentType.ORIGINAL)
                    options[which].contains("Void") -> showReceiptDeliveryDialog(ReceiptContentType.VOID)
                    options[which].contains("Refund") -> showReceiptDeliveryDialog(ReceiptContentType.REFUND)
                }
            }
            .show()
    }

    private fun showReceiptDeliveryDialog(contentType: ReceiptContentType) {
        val options = arrayOf("\uD83D\uDDA8\uFE0F  Print Receipt", "\u2709\uFE0F  Email Receipt", "Cancel")
        AlertDialog.Builder(this)
            .setTitle("Send Receipt")
            .setItems(options) { _, which ->
                when {
                    options[which].contains("Print") -> handlePrintReceipt(contentType)
                    options[which].contains("Email") -> handleEmailReceipt(contentType)
                }
            }
            .show()
    }

    // ── Email Receipt ────────────────────────────────────────────

    private fun handleEmailReceipt(contentType: ReceiptContentType) {
        val txId = saleTransactionId ?: ""
        when (contentType) {
            ReceiptContentType.ORIGINAL -> beginOriginalEmailFlow()
            ReceiptContentType.REFUND -> showTypedEmailDialog(orderId, "sendRefundReceiptEmail", txId)
            ReceiptContentType.VOID -> showTypedEmailDialog(orderId, "sendVoidReceiptEmail", txId)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun beginOriginalEmailFlow() {
        val txId = saleTransactionId?.trim()?.takeIf { it.isNotEmpty() }
        if (txId == null) {
            showTypedEmailDialog(orderId, "sendReceiptEmail", "")
            return
        }
        db.collection("Transactions").document(txId).get()
            .addOnSuccessListener { txDoc ->
                val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val splitPayloads = SplitReceiptReprintHelper.payloadsOrderedBySplitIndex(payments)
                if (splitPayloads == null) {
                    showTypedEmailDialog(orderId, "sendReceiptEmail", "")
                } else {
                    showSplitReceiptEmailChooser(splitPayloads)
                }
            }
            .addOnFailureListener {
                showTypedEmailDialog(orderId, "sendReceiptEmail", "")
            }
    }

    private fun showSplitReceiptEmailChooser(payloads: List<SplitReceiptPayload>) {
        val labels = payloads.map { "Person ${it.splitIndex}" }.toMutableList()
        labels.add("All in one (full receipt)")
        labels.add("Cancel")
        AlertDialog.Builder(this)
            .setTitle("Email which receipt?")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    labels.lastIndex -> Unit
                    payloads.size -> showTypedEmailDialog(orderId, "sendReceiptEmail", "")
                    else -> showSplitReceiptEmailEntryDialog(orderId, payloads[which])
                }
            }
            .show()
    }

    private fun showSplitReceiptEmailEntryDialog(orderId: String, payload: SplitReceiptPayload) {
        ReceiptEmailKeypadDialog.show(
            this,
            title = "Email receipt",
            message = "Enter the email address for this guest's receipt.",
            hint = "customer@email.com",
            onSend = { email -> launchSplitReceiptEmailIntent(orderId, email, payload) }
        )
    }

    private fun launchSplitReceiptEmailIntent(orderId: String, email: String, payload: SplitReceiptPayload) {
        SplitReceiptEmailSender.send(this, email, orderId, payload)
    }

    private fun showTypedEmailDialog(orderId: String, cloudFunction: String, transactionId: String) {
        ReceiptEmailKeypadDialog.show(
            this,
            title = "\u2709\uFE0F  Email Receipt",
            hint = "Enter email address",
            onSend = { email -> sendTypedReceiptEmail(email, orderId, cloudFunction, transactionId) }
        )
    }

    private fun sendTypedReceiptEmail(email: String, orderId: String, cloudFunction: String, transactionId: String) {
        val data = hashMapOf<String, Any>(
            "email" to email,
            "orderId" to orderId
        )
        if (transactionId.isNotBlank()) data["transactionId"] = transactionId

        FirebaseFunctions.getInstance()
            .getHttpsCallable(cloudFunction)
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

    // ── Print Receipt ────────────────────────────────────────────

    private fun runAfterBluetoothPermission(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingBluetoothPrintAction = block
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_CONNECT
                )
                return
            }
        }
        block()
    }

    private fun handlePrintReceipt(contentType: ReceiptContentType) {
        if (contentType != ReceiptContentType.ORIGINAL) {
            originalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
            runAfterBluetoothPermission { executePrint(contentType) }
            return
        }
        beginOriginalPrintFlow()
    }

    private fun beginOriginalPrintFlow() {
        val txId = saleTransactionId?.trim()?.takeIf { it.isNotEmpty() }
        if (txId == null) {
            originalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
            runAfterBluetoothPermission { executePrint(ReceiptContentType.ORIGINAL) }
            return
        }
        db.collection("Transactions").document(txId).get()
            .addOnSuccessListener { txDoc ->
                val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                val splitPayloads = SplitReceiptReprintHelper.payloadsOrderedBySplitIndex(payments)
                if (splitPayloads == null) {
                    originalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
                    runAfterBluetoothPermission { executePrint(ReceiptContentType.ORIGINAL) }
                } else {
                    showSplitReceiptReprintChooser(splitPayloads)
                }
            }
            .addOnFailureListener {
                originalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
                runAfterBluetoothPermission { executePrint(ReceiptContentType.ORIGINAL) }
            }
    }

    private fun showSplitReceiptReprintChooser(payloads: List<SplitReceiptPayload>) {
        val labels = payloads.map { "Person ${it.splitIndex}" }.toMutableList()
        labels.add("All in one (full receipt)")
        labels.add("Cancel")
        AlertDialog.Builder(this)
            .setTitle("Print which receipt?")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    labels.lastIndex -> Unit
                    payloads.size -> {
                        originalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
                        runAfterBluetoothPermission { executePrint(ReceiptContentType.ORIGINAL) }
                    }
                    else -> {
                        originalPrintSplitChoice = OriginalPrintSplitChoice.PersonReceipt(payloads[which])
                        runAfterBluetoothPermission { executePrint(ReceiptContentType.ORIGINAL) }
                    }
                }
            }
            .show()
    }

    private fun executePrint(contentType: ReceiptContentType) {
        Toast.makeText(this, "Preparing receipt\u2026", Toast.LENGTH_SHORT).show()
        when (contentType) {
            ReceiptContentType.ORIGINAL -> {
                when (val choice = originalPrintSplitChoice) {
                    is OriginalPrintSplitChoice.PersonReceipt -> printOriginalSplitReceipt(choice.payload)
                    is OriginalPrintSplitChoice.CombinedReceipt -> printOriginalReceipt()
                }
                originalPrintSplitChoice = OriginalPrintSplitChoice.CombinedReceipt
            }
            ReceiptContentType.REFUND -> printRefundReceipt()
            ReceiptContentType.VOID -> printVoidReceipt()
        }
    }

    private fun printOriginalSplitReceipt(payload: SplitReceiptPayload) {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    Toast.makeText(this, "Order not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val rs = ReceiptSettings.load(this)
                EscPosPrinter.print(
                    this,
                    SplitReceiptRenderer.buildEscPosSegments(this, orderDoc, payload),
                    rs
                )
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun printOriginalReceipt() {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    Toast.makeText(this, "Order not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val txId = saleTransactionId ?: ""
                        val rs = ReceiptSettings.load(this)
                        if (txId.isNotBlank()) {
                            db.collection("Transactions").document(txId).get()
                                .addOnSuccessListener { txDoc ->
                                    val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                    val txStatus = txDoc?.getString("status")
                                    val txVoided = txDoc?.getBoolean("voided") ?: false
                                    EscPosPrinter.print(
                                        this,
                                        buildOriginalSegments(orderDoc, itemsSnap.documents, payments, txStatus, txVoided),
                                        rs
                                    )
                                }
                                .addOnFailureListener {
                                    EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, emptyList()), rs)
                                }
                        } else {
                            EscPosPrinter.print(this, buildOriginalSegments(orderDoc, itemsSnap.documents, emptyList()), rs)
                        }
                    }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildOriginalSegments(
        orderDoc: DocumentSnapshot,
        items: List<DocumentSnapshot>,
        payments: List<Map<String, Any>>,
        transactionStatus: String? = null,
        transactionVoided: Boolean = false
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        EscPosPrinter.appendHeaderSegments(segs, rs)
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("RECEIPT", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("", fontSize = rs.fontSizeOrderInfo, centered = true)

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val oType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (oType.isNotBlank()) {
            val label = when (oType) {
                "DINE_IN" -> "Dine In"
                "TO_GO" -> "To Go"
                "BAR_TAB" -> "Bar Tab"
                "UBER_EATS" -> "Uber Eats"
                "ONLINE_PICKUP" -> "Online Order"
                else -> oType
            }
            segs += EscPosPrinter.Segment("Type: $label", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (rs.showServerName && empName.isNotBlank()) segs += EscPosPrinter.Segment("Server: $empName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (custName.isNotBlank()) segs += EscPosPrinter.Segment("Customer: $custName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        @Suppress("UNCHECKED_CAST")
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()

        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (doc in items) {
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents
            val itemLabel = if (qty > 1) "${qty}x $name" else name
            if (basePriceCents > 0L) {
                segs += EscPosPrinter.Segment(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
            } else {
                segs += EscPosPrinter.Segment(itemLabel, bold = rs.boldItems, fontSize = rs.fontSizeItems)
            }
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            fun addModSegs(items: List<Map<String, Any>>, indent: String = "") {
                for (mod in items) {
                    val modName = mod["name"]?.toString() ?: continue
                    val modAction = mod["action"]?.toString() ?: "ADD"
                    val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                    val modCents = (modPrice * 100).roundToLong()
                    when {
                        modAction == "REMOVE" -> segs += EscPosPrinter.Segment("$indent  ${ModifierRemoveDisplay.receiptNoLine(modName)}", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                        modCents > 0 -> segs += EscPosPrinter.Segment(formatLine("$indent  + $modName", MoneyUtils.centsToDisplay(modCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
                        else -> segs += EscPosPrinter.Segment("$indent  + $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val children = mod["children"] as? List<Map<String, Any>>
                    if (children != null) addModSegs(children, "$indent    ")
                }
            }
            addModSegs(mods)
            val lineKey = doc.id
            val itemDiscounts = appliedDiscounts.filter { ad ->
                val lk = ad["lineKey"]?.toString()?.trim().orEmpty()
                lk == lineKey
            }
            for (ad in itemDiscounts) {
                segs += EscPosPrinter.Segment("  ${DiscountDisplay.formatBulletFromFirestoreMap(ad)}", bold = rs.boldItems, fontSize = rs.fontSizeItems)
            }
        }
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("")

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) { taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        segs += EscPosPrinter.Segment(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)

        val groupedDiscounts = DiscountDisplay.groupByName(appliedDiscounts)
        if (groupedDiscounts.isNotEmpty()) {
            for (gd in groupedDiscounts) {
                val label = DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                segs += EscPosPrinter.Segment(formatLine(label, "-${MoneyUtils.centsToDisplay(gd.totalCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
        } else if (discountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }

        for (entry in taxBreakdown) {
            val tName = entry["name"]?.toString() ?: "Tax"
            val aCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val tRate = (entry["rate"] as? Number)?.toDouble()
            val tType = entry["taxType"]?.toString()
            val tLabel = DiscountDisplay.formatTaxLabel(tName, tType, tRate)
            segs += EscPosPrinter.Segment(formatLine(tLabel, MoneyUtils.centsToDisplay(aCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(this, tipAmountInCents)) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        segs += EscPosPrinter.Segment(formatLine("TOTAL", MoneyUtils.centsToDisplay(totalInCents), lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        segs.addAll(
            buildCreditTipReceiptFollowUpSegments(
                this,
                rs,
                subtotalCents,
                taxTotalCents,
                totalInCents,
                tipAmountInCents,
                payments,
                transactionStatus,
                transactionVoided
            )
        )

        // ── Payments (split / mixed) ──
        val paymentLines = ReceiptPaymentFormatting.buildPaymentsSectionLines(
            totalInCents = totalInCents,
            payments = payments,
            width = lwt,
        )
        if (paymentLines.isNotEmpty()) {
            for (line in paymentLines) {
                segs += EscPosPrinter.Segment(line, bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you for dining with us!", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    @Suppress("UNCHECKED_CAST")
    private fun printRefundReceipt() {
        val txId = saleTransactionId ?: ""
        val rs = ReceiptSettings.load(this)
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        if (txId.isBlank()) {
                            EscPosPrinter.print(this, buildSimpleRefundSegments(orderDoc), rs)
                            return@addOnSuccessListener
                        }
                        db.collection("Transactions")
                            .whereEqualTo("type", "REFUND")
                            .whereEqualTo("originalReferenceId", txId)
                            .get()
                            .addOnSuccessListener { refundSnap ->
                                db.collection("Transactions").document(txId).get()
                                    .addOnSuccessListener { txDoc ->
                                        val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                        val segments = buildDetailedRefundSegments(orderDoc, itemsSnap.documents, refundSnap.documents, payments)
                                        EscPosPrinter.print(this, segments, rs)
                                    }
                                    .addOnFailureListener {
                                        val segments = buildDetailedRefundSegments(orderDoc, itemsSnap.documents, refundSnap.documents, emptyList())
                                        EscPosPrinter.print(this, segments, rs)
                                    }
                            }
                            .addOnFailureListener {
                                EscPosPrinter.print(this, buildSimpleRefundSegments(orderDoc), rs)
                            }
                    }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedRefundSegments(
        orderDoc: DocumentSnapshot,
        items: List<DocumentSnapshot>,
        refundDocs: List<DocumentSnapshot>,
        payments: List<Map<String, Any>> = emptyList()
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        EscPosPrinter.appendHeaderSegments(segs, rs, includeEmail = false)
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("REFUND RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)

        val refundedByName = refundDocs.firstOrNull()?.getString("refundedBy")?.trim()?.takeIf { it.isNotBlank() }
        if (refundedByName != null) {
            segs += EscPosPrinter.Segment("Refunded by: $refundedByName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        segs += EscPosPrinter.Segment("")

        val itemById = items.associateBy { it.id }
        val itemByName = items.groupBy { (it.getString("name") ?: it.getString("itemName") ?: "").trim() }

        data class RefundedItem(val name: String, val qty: Int, val amountCents: Long, val baseCents: Long, val taxBreakdown: List<Map<String, Any>>)
        val refundedItems = mutableListOf<RefundedItem>()
        var totalRefundCents = 0L

        for (refDoc in refundDocs) {
            val refAmountCents = refDoc.getLong("amountInCents")
                ?: ((refDoc.getDouble("amount") ?: 0.0) * 100).toLong()
            totalRefundCents += refAmountCents
            val lineKey = refDoc.getString("refundedLineKey")?.takeIf { it.isNotBlank() }
            val itemName = refDoc.getString("refundedItemName")?.trim()?.takeIf { it.isNotBlank() }

            val matchedItem = if (lineKey != null) {
                itemById[lineKey]
            } else if (itemName != null) {
                itemByName[itemName]?.firstOrNull()
            } else null

            if (matchedItem != null) {
                val name = matchedItem.getString("name") ?: matchedItem.getString("itemName") ?: "Item"
                val qty = (matchedItem.getLong("qty") ?: matchedItem.getLong("quantity") ?: 1L).toInt()
                val storedTaxBreakdown = matchedItem.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                val lineTotalInCents = matchedItem.getLong("lineTotalInCents") ?: refAmountCents
                refundedItems.add(RefundedItem(name, qty, refAmountCents, lineTotalInCents, storedTaxBreakdown))
            } else if (itemName != null) {
                refundedItems.add(RefundedItem(itemName, 1, refAmountCents, refAmountCents, emptyList()))
            } else {
                for (item in items) {
                    val name = item.getString("name") ?: item.getString("itemName") ?: "Item"
                    val qty = (item.getLong("qty") ?: item.getLong("quantity") ?: 1L).toInt()
                    val lineCents = item.getLong("lineTotalInCents") ?: 0L
                    val storedTaxBreakdown = item.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                    refundedItems.add(RefundedItem(name, qty, lineCents, lineCents, storedTaxBreakdown))
                }
            }
        }

        segs += EscPosPrinter.Segment("Refunded Items:", bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (ri in refundedItems) {
            val label = if (ri.qty > 1) "${ri.name} x${ri.qty}" else ri.name
            segs += EscPosPrinter.Segment(
                formatLine(label, MoneyUtils.centsToDisplay(ri.baseCents), lwi),
                bold = rs.boldItems, fontSize = rs.fontSizeItems
            )
        }
        segs += EscPosPrinter.Segment("")

        val taxGroupMap = mutableMapOf<String, Triple<String, Double, Long>>()
        for (ri in refundedItems) {
            for (tax in ri.taxBreakdown) {
                val taxName = tax["name"]?.toString() ?: continue
                val taxRate = (tax["rate"] as? Number)?.toDouble() ?: 0.0
                val taxAmount = (tax["amountInCents"] as? Number)?.toLong() ?: 0L
                val existing = taxGroupMap[taxName]
                if (existing != null) {
                    taxGroupMap[taxName] = Triple(taxName, existing.second, existing.third + taxAmount)
                } else {
                    taxGroupMap[taxName] = Triple(taxName, taxRate, taxAmount)
                }
            }
        }

        if (taxGroupMap.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val orderTaxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
            val orderSubtotalCents = items.sumOf { it.getLong("lineTotalInCents") ?: 0L }
            val refundedBaseCents = refundedItems.sumOf { it.baseCents }
            for (tax in orderTaxBreakdown) {
                val taxName = tax["name"]?.toString() ?: continue
                val taxRate = (tax["rate"] as? Number)?.toDouble() ?: 0.0
                val orderTaxAmount = (tax["amountInCents"] as? Number)?.toLong() ?: 0L
                val prorated = if (taxRate > 0) {
                    (refundedBaseCents * taxRate / 100.0).roundToLong()
                } else if (orderSubtotalCents > 0) {
                    (orderTaxAmount.toDouble() * refundedBaseCents / orderSubtotalCents).roundToLong()
                } else {
                    orderTaxAmount
                }
                if (prorated > 0L) taxGroupMap[taxName] = Triple(taxName, taxRate, prorated)
            }
        }

        if (taxGroupMap.isNotEmpty()) {
            segs += EscPosPrinter.Segment("Taxes Refunded:", bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            segs += EscPosPrinter.Segment("-".repeat(lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            for ((_, group) in taxGroupMap) {
                val (name, rate, totalAmount) = group
                val pctStr = if (rate > 0) {
                    if (rate % 1.0 == 0.0) "${rate.toInt()}%" else "%.2f%%".format(rate)
                } else ""
                val taxLabel = if (pctStr.isNotBlank()) "$name ($pctStr)" else name
                segs += EscPosPrinter.Segment(
                    formatLine(taxLabel, MoneyUtils.centsToDisplay(totalAmount), lwt),
                    bold = rs.boldTotals, fontSize = rs.fontSizeTotals
                )
            }
            segs += EscPosPrinter.Segment("")
        }

        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("TOTAL REFUND", "-${MoneyUtils.centsToDisplay(totalRefundCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val l4 = p["last4"]?.toString() ?: ""
                val auth = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || l4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (brand.isNotBlank()) append(brand); if (l4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** $l4") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (auth.isNotBlank()) segs += EscPosPrinter.Segment("Auth: $auth", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                receiptLabelForCardEntryType(p["entryType"]?.toString())?.let { method ->
                    segs += EscPosPrinter.Segment(
                        "Payment method: $method",
                        bold = rs.boldFooter,
                        fontSize = rs.fontSizeFooter,
                        centered = true
                    )
                }
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)

        return segs
    }

    private fun buildSimpleRefundSegments(orderDoc: DocumentSnapshot): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        EscPosPrinter.appendHeaderSegments(segs, rs, includeEmail = false)
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("REFUND RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        val totalRefundedInCents = orderDoc.getLong("totalRefundedInCents") ?: 0L
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("REFUND TOTAL", "-${MoneyUtils.centsToDisplay(totalRefundedInCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    @Suppress("UNCHECKED_CAST")
    private fun printVoidReceipt() {
        val rs = ReceiptSettings.load(this)
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) return@addOnSuccessListener
                db.collection("Orders").document(orderId).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        db.collection("Orders").document(orderId).collection("transactions").get()
                            .addOnSuccessListener { txnsSnap ->
                                @Suppress("UNCHECKED_CAST")
                                val reversed = txnsSnap.documents.mapNotNull { it.data as? Map<String, Any> }
                                if (reversed.isNotEmpty()) {
                                    val segs = buildDetailedVoidReceiptSegments(orderDoc, itemsSnap.documents, reversed)
                                    EscPosPrinter.print(this, segs, rs)
                                    return@addOnSuccessListener
                                }

                                // Fallback: older orders may not have Orders/{orderId}/transactions yet.
                                val txId = saleTransactionId ?: ""
                                if (txId.isNotBlank()) {
                                    db.collection("Transactions").document(txId).get()
                                        .addOnSuccessListener { txDoc ->
                                            @Suppress("UNCHECKED_CAST")
                                            val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                            val segs = buildDetailedVoidReceiptSegments(orderDoc, itemsSnap.documents, payments)
                                            EscPosPrinter.print(this, segs, rs)
                                        }
                                        .addOnFailureListener {
                                            val segs = buildDetailedVoidReceiptSegments(orderDoc, itemsSnap.documents, emptyList())
                                            EscPosPrinter.print(this, segs, rs)
                                        }
                                } else {
                                    val segs = buildDetailedVoidReceiptSegments(orderDoc, itemsSnap.documents, emptyList())
                                    EscPosPrinter.print(this, segs, rs)
                                }
                            }
                            .addOnFailureListener {
                                val segs = buildDetailedVoidReceiptSegments(orderDoc, itemsSnap.documents, emptyList())
                                EscPosPrinter.print(this, segs, rs)
                            }
                    }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedVoidReceiptSegments(
        orderDoc: DocumentSnapshot,
        items: List<DocumentSnapshot>,
        reversedTransactions: List<Map<String, Any>>
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        EscPosPrinter.appendHeaderSegments(segs, rs, includeEmail = false)
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("VOID RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val voidedBy = orderDoc.getString("voidedBy")?.trim()?.takeIf { it.isNotBlank() }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (orderType.isNotBlank()) {
            val label = when (orderType) {
                "DINE_IN" -> "Dine In"
                "TO_GO" -> "To Go"
                "BAR_TAB" -> "Bar Tab"
                "UBER_EATS" -> "Uber Eats"
                "ONLINE_PICKUP" -> "Online Order"
                else -> orderType
            }
            segs += EscPosPrinter.Segment("Type: $label", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (rs.showServerName && empName.isNotBlank()) segs += EscPosPrinter.Segment("Server: $empName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (custName.isNotBlank()) segs += EscPosPrinter.Segment("Customer: $custName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (voidedBy != null) segs += EscPosPrinter.Segment("Voided by: $voidedBy", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (doc in items) {
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents
            val itemLabel = if (qty > 1) "${qty}x $name" else name
            if (basePriceCents > 0L) {
                segs += EscPosPrinter.Segment(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
            } else {
                segs += EscPosPrinter.Segment(itemLabel, bold = rs.boldItems, fontSize = rs.fontSizeItems)
            }
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            fun addModSegs(items: List<Map<String, Any>>, indent: String = "") {
                for (mod in items) {
                    val modName = mod["name"]?.toString() ?: continue
                    val modAction = mod["action"]?.toString() ?: "ADD"
                    val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                    val modCents = (modPrice * 100).roundToLong()
                    when {
                        modAction == "REMOVE" -> segs += EscPosPrinter.Segment("$indent  ${ModifierRemoveDisplay.receiptNoLine(modName)}", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                        modCents > 0 -> segs += EscPosPrinter.Segment(formatLine("$indent  + $modName", MoneyUtils.centsToDisplay(modCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
                        else -> segs += EscPosPrinter.Segment("$indent  + $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val children = mod["children"] as? List<Map<String, Any>>
                    if (children != null) addModSegs(children, "$indent    ")
                }
            }
            addModSegs(mods)
        }
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("")

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) { taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        segs += EscPosPrinter.Segment(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)

        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val groupedDiscounts = DiscountDisplay.groupByName(appliedDiscounts)
        if (groupedDiscounts.isNotEmpty()) {
            for (gd in groupedDiscounts) {
                val discLabel = DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                segs += EscPosPrinter.Segment(formatLine(discLabel, "-${MoneyUtils.centsToDisplay(gd.totalCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
        } else if (discountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }

        for (entry in taxBreakdown) {
            val tName = entry["name"]?.toString() ?: "Tax"
            val aCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val tRate = (entry["rate"] as? Number)?.toDouble()
            val tType = entry["taxType"]?.toString()
            val tLabel = DiscountDisplay.formatTaxLabel(tName, tType, tRate)
            segs += EscPosPrinter.Segment(formatLine(tLabel, MoneyUtils.centsToDisplay(aCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(this, tipAmountInCents)) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("VOID TOTAL", "-${MoneyUtils.centsToDisplay(totalInCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        // ── Reversed payments (from Orders/{orderId}/transactions) ──
        val reversedLines = ReceiptPaymentFormatting.buildReversedPaymentsSectionLines(
            reversedTransactions = reversedTransactions,
            width = lwt
        )
        if (reversedLines.isNotEmpty()) {
            reversedLines.forEach { line ->
                segs += EscPosPrinter.Segment(line, bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
            segs += EscPosPrinter.Segment("")
        }

        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_CONNECT) {
            val block = pendingBluetoothPrintAction
            pendingBluetoothPrintAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                block?.invoke()
            } else {
                Toast.makeText(this, "Bluetooth permission required for printing", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finalizeRefund(
        originalTransactionId: String,
        amountInCents: Long,
        finishAfter: Boolean = true,
        refundedItemName: String? = null,
        refundedLineKey: String? = null,
        paymentType: String = "Cash"
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

                        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
                        val refundDocData = mutableMapOf<String, Any>(
                            "orderId" to orderId,
                            "orderNumber" to orderNumber,
                            "type" to "REFUND",
                            "amount" to amountInCents / 100.0,
                            "amountInCents" to amountInCents,
                            "originalReferenceId" to originalTransactionId,
                            "createdAt" to Date(),
                            "batchId" to openBatchId,
                            "voided" to false,
                            "settled" to false,
                            "refundedBy" to employeeName,
                            "paymentType" to paymentType
                        )
                        refundedItemName?.takeIf { it.isNotBlank() }?.let { refundDocData["refundedItemName"] = it }
                        refundedLineKey?.takeIf { it.isNotBlank() }?.let { refundDocData["refundedLineKey"] = it }

                        db.runTransaction { firestoreTxn ->
                            val batchSnap2 = firestoreTxn.get(batchRef)
                            val counter = batchSnap2.getLong("transactionCounter") ?: 0L
                            val next = counter + 1
                            firestoreTxn.update(batchRef, "transactionCounter", next)
                            refundDocData["appTransactionNumber"] = next
                            firestoreTxn.set(newRefundTxRef, refundDocData)
                        }.addOnSuccessListener {
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
                                                    if (finishAfter) finish() else { loadHeader() }
                                                }
                                            }
                                    }
                            }
                    }
            }
    }
}