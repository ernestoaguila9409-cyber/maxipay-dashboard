package com.ernesto.myapplication

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import android.media.RingtoneManager
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * POS dashboard host. **Back key:** pops [androidx.fragment.app.FragmentManager] when
 * [androidx.fragment.app.FragmentTransaction.addToBackStack] was used; at root, sends the task
 * to the background with [android.app.Activity.moveTaskToBack] instead of finishing.
 *
 * Example when you add a fragment container to this activity:
 * ```
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.fragment_container, OrderSummaryFragment())
 *     .addToBackStack(null)
 *     .commit()
 * ```
 */
class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView
    private lateinit var dashboardAdapter: DashboardAdapter
    private lateinit var dashboardPager: ViewPager2
    private lateinit var pageIndicator: LinearLayout
    private lateinit var pendingOnlineOrderSign: View
    private lateinit var txtPendingOnlineSignOrderLine: TextView
    private lateinit var txtPendingOnlineSignCustomer: TextView
    private lateinit var txtPendingOnlineSignPayment: TextView
    private lateinit var txtPendingOnlineSignMore: TextView

    private var openOrdersListener: ListenerRegistration? = null
    private var pendingOnlineConfirmListener: ListenerRegistration? = null
    private val pendingOnlineConfirmDocs = mutableListOf<DocumentSnapshot>()
    private var dashboardConfigListener: ListenerRegistration? = null
    private var uberOrdersListener: ListenerRegistration? = null
    private var currentSalesListener: ListenerRegistration? = null
    private var onlineOrderAlertSystem: OnlineOrderAlertSystem? = null
    /** Latest modules from Firestore [Settings/dashboard] before ONLINE visibility merge. */
    private var lastRawDashboardModules: List<DashboardModule>? = null

    private val onlineOrderingOpenStateListener: () -> Unit = {
        if (!isDestroyed) {
            applyMergedDashboardFromCache()
        }
    }
    private val knownUberOrderIds = mutableSetOf<String>()
    private var uberAlertDialog: AlertDialog? = null
    private var currentBatchId: String = ""

    private var employeeName: String = ""
    private var employeeRole: String = ""

    private var lastPendingOnlineConfirmIds: Set<String> = emptySet()
    private var pendingOnlineConfirmListenerPrimed = false
    private val incomingOrderChimePlayer by lazy { NewOrderSoundPlayer(this) }

    /**
     * When [moveTaskToBack] is false (rare), show a confirmation before [finishAffinity].
     * Default false for fast POS; set true if you want a dialog instead of immediate exit fallback.
     */
    private val confirmBeforeFullExitFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        KdsActiveCache.start()

        employeeName = intent.getStringExtra("employeeName") ?: ""
        employeeRole = intent.getStringExtra("employeeRole") ?: ""
        if (employeeName.isNotBlank()) {
            SessionEmployee.setEmployee(this, employeeName, employeeRole)
        } else {
            employeeName = SessionEmployee.getEmployeeName(this)
            employeeRole = SessionEmployee.getEmployeeRole(this)
        }

        val txtLoggedUser = findViewById<TextView>(R.id.txtLoggedUser)
        txtLoggedUser.text = "Logged in as: $employeeName"

        val txtEmployeeRole = findViewById<TextView>(R.id.txtEmployeeRole)
        txtEmployeeRole.text = employeeRole.ifBlank { "EMPLOYEE" }

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)
        pendingOnlineOrderSign = findViewById(R.id.pendingOnlineOrderSign)
        txtPendingOnlineSignOrderLine = findViewById(R.id.txtPendingOnlineSignOrderLine)
        txtPendingOnlineSignCustomer = findViewById(R.id.txtPendingOnlineSignCustomer)
        txtPendingOnlineSignPayment = findViewById(R.id.txtPendingOnlineSignPayment)
        txtPendingOnlineSignMore = findViewById(R.id.txtPendingOnlineSignMore)
        pendingOnlineOrderSign.setOnClickListener { onOnlineStaffConfirmBannerClicked() }

        setupDashboardGrid()
        OnlineOrderingDashboardSync.start(db)
        OnlineOrderingDashboardSync.addListener(onlineOrderingOpenStateListener)
        OnlineOrderKitchenRoutingCache.start(db)
        CourseFiringCache.start(db)
        listenForOpenOrders()
        listenForPendingOnlineStaffConfirm()
        listenForUberOrders()
        onlineOrderAlertSystem = OnlineOrderAlertSystem(
            activity = this,
            firestore = db,
            getEmployeeName = { employeeName },
            ordersCollectionPath = OnlineOrderAlertFirestoreListener.DEFAULT_ORDERS_COLLECTION,
            sourceField = "orderSource",
            sourceValue = "online_ordering",
            statusField = OnlineOrderAlertFirestoreListener.DEFAULT_STATUS_FIELD,
            statusValue = OnlineOrderAlertFirestoreListener.DEFAULT_STATUS_VALUE,
        ).also { it.attach() }
        ensureOpenBatch()
        ReceiptSettings.startBusinessInfoSync(this)

        val bizName = ReceiptSettings.load(this).businessName
        CustomerDisplayManager.setIdle(this, bizName)

        findViewById<View>(R.id.todaySalesArea).setOnClickListener {
            startActivity(Intent(this, TodaySalesActivity::class.java))
        }

        findViewById<View>(R.id.btnLogout).setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        attachCurrentSalesListener()
        // Register last so this callback runs before any library-added default back behavior.
        registerDashboardBackHandling()
    }

    /**
     * System / predictive Back: pop fragment stack if used; otherwise always try to minimize the
     * task. Never calls [finishAffinity] (that was killing the app when [moveTaskToBack] returned false).
     */
    private fun registerDashboardBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val fm = supportFragmentManager
                    if (fm.backStackEntryCount > 0) {
                        fm.popBackStack()
                        return
                    }
                    if (moveTaskToBack(true)) {
                        return
                    }
                    if (confirmBeforeFullExitFallback) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Leave POS?")
                            .setMessage("Can't send the app to the background from here. Close completely?")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Close") { _, _ ->
                                finishAffinity()
                            }
                            .show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Use the Home button to leave the app.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    private fun setupDashboardGrid() {
        dashboardPager = findViewById(R.id.dashboardPager)
        pageIndicator = findViewById(R.id.pageIndicator)

        dashboardAdapter = DashboardAdapter(mutableListOf()) { module ->
            handleModuleClick(module)
        }
        dashboardPager.adapter = dashboardAdapter

        dashboardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })

        dashboardConfigListener = DashboardConfigManager.listenConfig(
            db,
            onUpdate = { modules ->
                if (!isDestroyed) {
                    setDashboardModulesRaw(modules)
                }
            },
            onCacheThenServer = { serverModules ->
                if (!isDestroyed && serverModules.isNotEmpty()) {
                    setDashboardModulesRaw(serverModules)
                }
            }
        )
    }

    private fun setDashboardModulesRaw(modules: List<DashboardModule>) {
        lastRawDashboardModules = modules
        applyMergedDashboardFromCache()
    }

    private fun applyMergedDashboardFromCache() {
        val raw = lastRawDashboardModules ?: return
        val showOnline = OnlineOrderingDashboardSync.shouldShowOnlineOrdersTile()
        dashboardAdapter.setModules(
            DashboardModule.mergeTipDashboardTile(
                this,
                DashboardModule.mergeReservationDashboardTile(
                    DashboardModule.mergeOnlineOrdersDashboardTile(
                        DashboardModule.mergePrintersDashboardTile(raw),
                        showOnlineOrdersTile = showOnline,
                    ),
                ),
            ),
        )
        updatePageIndicator(dashboardPager.currentItem)
        applyOrderTypeVisibility()
    }

    private fun updatePageIndicator(selectedPage: Int) {
        pageIndicator.removeAllViews()
        val pageCount = dashboardAdapter.getPageCount()
        if (pageCount <= 1) {
            pageIndicator.visibility = View.GONE
            return
        }
        pageIndicator.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val dotSize = (8 * density).toInt()
        val dotMargin = (4 * density).toInt()

        for (i in 0 until pageCount) {
            val dot = View(this)
            val params = LinearLayout.LayoutParams(dotSize, dotSize)
            params.setMargins(dotMargin, 0, dotMargin, 0)
            dot.layoutParams = params
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == selectedPage) 0xFF6A4FB3.toInt() else 0xFFCCCCCC.toInt())
            }
            pageIndicator.addView(dot)
        }
    }

    private fun handleModuleClick(module: DashboardModule) {
        when (module.key) {
            "dine_in" -> {
                val i = Intent(this, TableSelectionActivity::class.java)
                i.putExtra("batchId", currentBatchId)
                i.putExtra("employeeName", employeeName)
                startActivity(i)
            }
            "to_go" -> {
                val i = Intent(this, MenuActivity::class.java)
                i.putExtra("batchId", currentBatchId)
                i.putExtra("employeeName", employeeName)
                i.putExtra("orderType", "TO_GO")
                startActivity(i)
            }
            "bar" -> {
                val i = Intent(this, BarTabsActivity::class.java)
                i.putExtra("batchId", currentBatchId)
                i.putExtra("employeeName", employeeName)
                startActivity(i)
            }
            "online_orders" -> {
                val i = Intent(this, OrdersActivity::class.java)
                i.putExtra("employeeName", employeeName)
                i.putExtra("FILTER_ONLINE", true)
                startActivity(i)
            }
            "transactions" -> {
                val i = Intent(this, TransactionActivity::class.java)
                i.putExtra("employeeName", employeeName)
                startActivity(i)
            }
            "settle_batch" -> startActivity(Intent(this, BatchManagementActivity::class.java))
            "employees" -> startActivity(Intent(this, EmployeesActivity::class.java))
            "customers" -> startActivity(Intent(this, CustomersActivity::class.java))
            "orders" -> {
                val i = Intent(this, OrdersActivity::class.java)
                i.putExtra("employeeName", employeeName)
                startActivity(i)
            }
            "setup" -> startActivity(Intent(this, ConfigurationActivity::class.java))
            "modifiers" -> startActivity(Intent(this, GlobalModifierActivity::class.java))
            "inventory" -> {
                val i = Intent(this, MenuOnlyActivity::class.java)
                i.putExtra("batchId", currentBatchId)
                startActivity(i)
            }
            "reports" -> startActivity(Intent(this, ReportsActivity::class.java))
            "printers" -> startActivity(Intent(this, PrintersActivity::class.java))
            "cash_flow" -> startActivity(Intent(this, CashFlowActivity::class.java))
            "tips" -> startActivity(Intent(this, TipAdjustmentActivity::class.java))
            "reservation" -> {
                val i = Intent(this, ReservationActivity::class.java)
                i.putExtra("employeeName", employeeName)
                startActivity(i)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CustomerDisplayManager.attach(this)
        attachCurrentSalesListener()
        applyOrderTypeVisibility()
        refreshDashboardFromServer()
    }

    private fun refreshDashboardFromServer() {
        DashboardConfigManager.loadFromServer(db) { modules ->
            if (!isDestroyed && modules.isNotEmpty()) {
                setDashboardModulesRaw(modules)
            }
        }
    }

    private fun applyOrderTypeVisibility() {
    }

    private fun ensureOpenBatch() {
        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    currentBatchId = documents.documents[0].id
                } else {
                    val newBatchId = "BATCH_${System.currentTimeMillis()}"
                    val batchData = hashMapOf(
                        "batchId" to newBatchId,
                        "total" to 0.0,
                        "count" to 0,
                        "closed" to false,
                        "createdAt" to java.util.Date(),
                        "type" to "OPEN",
                        "transactionCounter" to 0
                    )
                    db.collection("Batches")
                        .document(newBatchId)
                        .set(batchData)
                        .addOnSuccessListener { currentBatchId = newBatchId }
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        OnlineOrderingDashboardSync.removeListener(onlineOrderingOpenStateListener)
        OnlineOrderingDashboardSync.stop()
        OnlineOrderKitchenRoutingCache.stop()
        openOrdersListener?.remove()
        dashboardConfigListener?.remove()
        uberOrdersListener?.remove()
        pendingOnlineConfirmListener?.remove()
        currentSalesListener?.remove()
        uberAlertDialog?.dismiss()
        onlineOrderAlertSystem = null
    }

    private fun listenForOpenOrders() {
        openOrdersListener = db.collection("Orders")
            .whereEqualTo("status", "OPEN")
            .addSnapshotListener { snapshots, _ ->
                if (snapshots == null) return@addSnapshotListener

                var dineIn = 0
                var toGo = 0
                var bar = 0
                var online = 0

                for (doc in snapshots) {
                    val source = doc.getString("orderSource") ?: ""
                    val ot = doc.getString("orderType") ?: ""
                    if (source.isNotBlank() || ot == "UBER_EATS" || ot == "ONLINE_PICKUP") {
                        if (!OnlineOrderStaffConfirm.isAwaitingStaffWebOnline(doc)) {
                            online++
                        }
                        continue
                    }
                    when (ot) {
                        "DINE_IN" -> {
                            val tid = doc.getString("tableId")
                            if (!tid.isNullOrBlank()) dineIn++
                        }
                        "TO_GO" -> toGo++
                        "BAR", "BAR_TAB" -> bar++
                    }
                }

                dashboardAdapter.updateBadge("dine_in", dineIn)
                dashboardAdapter.updateBadge("to_go", toGo)
                dashboardAdapter.updateBadge("bar", bar)
                dashboardAdapter.updateBadge("online_orders", online)
            }
    }

    private fun listenForPendingOnlineStaffConfirm() {
        pendingOnlineConfirmListener?.remove()
        pendingOnlineConfirmListener = db.collection("Orders")
            .whereEqualTo(OnlineOrderStaffConfirm.FIELD_AWAITING, true)
            .whereEqualTo("orderSource", "online_ordering")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null || isDestroyed) return@addSnapshotListener
                val pending = snapshots.documents
                    .filter { OnlineOrderStaffConfirm.isAwaitingStaffWebOnline(it) }
                    .sortedByDescending { it.getTimestamp("createdAt")?.seconds ?: 0 }
                pendingOnlineConfirmDocs.clear()
                pendingOnlineConfirmDocs.addAll(pending)
                updatePendingOnlineOrderSign(pending)
                val ids = pending.map { it.id }.toSet()
                if (!pendingOnlineConfirmListenerPrimed) {
                    pendingOnlineConfirmListenerPrimed = true
                    lastPendingOnlineConfirmIds = ids
                } else {
                    val hasNewAwaiting = ids.any { it !in lastPendingOnlineConfirmIds }
                    if (hasNewAwaiting && pending.isNotEmpty()) {
                        incomingOrderChimePlayer.playNewOrderChime()
                    }
                    lastPendingOnlineConfirmIds = ids
                }
            }
    }

    private fun updatePendingOnlineOrderSign(pending: List<DocumentSnapshot>) {
        if (pending.isEmpty()) {
            pendingOnlineOrderSign.visibility = View.GONE
            return
        }
        pendingOnlineOrderSign.visibility = View.VISIBLE
        val doc = pending.first()
        val orderNum = doc.getLong("orderNumber") ?: 0L
        val itemCount = (doc.getLong("itemsCount") ?: doc.getLong("itemCount") ?: 0L).toInt()
        txtPendingOnlineSignOrderLine.text = getString(
            R.string.online_order_alert_subtitle,
            orderNum,
            itemCount,
        )
        val customer = doc.getString("customerName")?.trim().orEmpty().ifBlank { "Guest" }
        txtPendingOnlineSignCustomer.text = customer
        val payment = doc.getString("onlinePaymentChoice") ?: ""
        txtPendingOnlineSignPayment.text = when (payment) {
            "PAY_ONLINE_HPP" -> getString(R.string.pending_online_payment_card_online)
            else -> getString(R.string.pending_online_payment_pickup)
        }
        if (pending.size > 1) {
            txtPendingOnlineSignMore.visibility = View.VISIBLE
            txtPendingOnlineSignMore.text = getString(R.string.pending_online_sign_more, pending.size - 1)
        } else {
            txtPendingOnlineSignMore.visibility = View.GONE
        }
    }

    private fun onOnlineStaffConfirmBannerClicked() {
        if (isDestroyed || isFinishing) return
        val pending = pendingOnlineConfirmDocs.toList()
        if (pending.isEmpty()) {
            Toast.makeText(this, "No pending orders.", Toast.LENGTH_SHORT).show()
            return
        }
        if (pending.size == 1) {
            showPendingOnlineOrderDialog(pending[0])
            return
        }
        val labels = pending.map { doc ->
            val n = doc.getLong("orderNumber") ?: 0L
            val cust = doc.getString("customerName")?.trim().orEmpty().ifEmpty { "Guest" }
            "#$n · $cust"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Incoming online orders")
            .setItems(labels) { _, which ->
                val id = pending[which].id
                db.collection("Orders").document(id).get()
                    .addOnSuccessListener { d ->
                        if (d.exists() && OnlineOrderStaffConfirm.isAwaitingStaffWebOnline(d)) {
                            showPendingOnlineOrderDialog(d)
                        } else {
                            Toast.makeText(this, "That order was already handled.", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPendingOnlineOrderDialog(orderDoc: DocumentSnapshot) {
        OnlineOrderAwaitingStaffReviewDialog.showFromDocument(this, db, orderDoc, employeeName)
    }

    private fun listenForUberOrders() {
        uberOrdersListener?.remove()
        uberOrdersListener = db.collection("Orders")
            .whereEqualTo("orderType", "UBER_EATS")
            .whereEqualTo("status", "OPEN")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                for (change in snapshots.documentChanges) {
                    if (change.type == DocumentChange.Type.ADDED) {
                        val docId = change.document.id
                        if (knownUberOrderIds.add(docId)) {
                            showUberOrderAlert(change.document)
                        }
                    }
                }
                val currentIds = snapshots.documents.map { it.id }.toSet()
                knownUberOrderIds.retainAll(currentIds)
            }
    }

    private fun showUberOrderAlert(doc: com.google.firebase.firestore.DocumentSnapshot) {
        if (isDestroyed || isFinishing) return

        val orderNumber = doc.getLong("orderNumber") ?: 0L
        val customerName = doc.getString("customerName") ?: "Uber Customer"
        val totalCents = doc.getLong("totalInCents") ?: 0L
        val itemsCount = (doc.getLong("itemsCount") ?: 0L).toInt()
        val total = String.format(Locale.US, "$%.2f", totalCents / 100.0)

        val message = buildString {
            append("Order #$orderNumber")
            append("\nCustomer: $customerName")
            if (itemsCount > 0) append("\nItems: $itemsCount")
            append("\nTotal: $total")
        }

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) { }

        uberAlertDialog?.dismiss()
        uberAlertDialog = AlertDialog.Builder(this)
            .setTitle("New Uber Eats Order!")
            .setMessage(message)
            .setPositiveButton("View Order") { _, _ ->
                val intent = Intent(this, OrderDetailActivity::class.java)
                intent.putExtra("orderId", doc.id)
                intent.putExtra("employeeName", employeeName)
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun attachCurrentSalesListener() {
        currentSalesListener?.remove()
        currentSalesListener = db.collection("Transactions")
            .whereEqualTo("settled", false)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("MainActivity", "Current sales query failed", error)
                    return@addSnapshotListener
                }
                if (snapshots == null || isDestroyed) return@addSnapshotListener
                val (finalTotal, finalCount) = UnsettledSalesSummary.compute(snapshots)
                txtTodayTotal.text = String.format(Locale.US, "$%.2f", finalTotal)
                txtTodayCount.text = "$finalCount transactions"
            }
    }
}
