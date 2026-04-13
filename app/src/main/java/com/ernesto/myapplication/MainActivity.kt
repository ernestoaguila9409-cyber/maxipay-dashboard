package com.ernesto.myapplication

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import java.util.concurrent.Executors

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

    private var openOrdersListener: ListenerRegistration? = null
    private var dashboardConfigListener: ListenerRegistration? = null
    private var currentBatchId: String = ""

    private var employeeName: String = ""
    private var employeeRole: String = ""

    /**
     * When [moveTaskToBack] is false (rare), show a confirmation before [finishAffinity].
     * Default false for fast POS; set true if you want a dialog instead of immediate exit fallback.
     */
    private val confirmBeforeFullExitFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        setupDashboardGrid()
        listenForOpenOrders()
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

        loadCurrentSales()
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
                    dashboardAdapter.setModules(
                        DashboardModule.mergeTipDashboardTile(
                            this,
                            DashboardModule.mergeReservationDashboardTile(
                                DashboardModule.mergePrintersDashboardTile(modules)
                            ),
                        )
                    )
                    updatePageIndicator(dashboardPager.currentItem)
                    applyOrderTypeVisibility()
                }
            },
            onCacheThenServer = { serverModules ->
                if (!isDestroyed && serverModules.isNotEmpty()) {
                    dashboardAdapter.setModules(
                        DashboardModule.mergeTipDashboardTile(
                            this,
                            DashboardModule.mergeReservationDashboardTile(
                                DashboardModule.mergePrintersDashboardTile(serverModules)
                            ),
                        )
                    )
                    updatePageIndicator(dashboardPager.currentItem)
                }
            }
        )
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
        loadCurrentSales()
        applyOrderTypeVisibility()
        refreshDashboardFromServer()
    }

    private fun refreshDashboardFromServer() {
        DashboardConfigManager.loadFromServer(db) { modules ->
            if (!isDestroyed && modules.isNotEmpty()) {
                dashboardAdapter.setModules(
                    DashboardModule.mergeTipDashboardTile(
                        this,
                        DashboardModule.mergeReservationDashboardTile(
                            DashboardModule.mergePrintersDashboardTile(modules)
                        ),
                    )
                )
                updatePageIndicator(dashboardPager.currentItem)
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
        openOrdersListener?.remove()
        dashboardConfigListener?.remove()
    }

    private fun listenForOpenOrders() {
        openOrdersListener = db.collection("Orders")
            .whereEqualTo("status", "OPEN")
            .addSnapshotListener { snapshots, _ ->
                if (snapshots == null) return@addSnapshotListener

                var dineIn = 0
                var toGo = 0
                var bar = 0

                for (doc in snapshots) {
                    when (doc.getString("orderType")) {
                        // Match [TableSelectionActivity.listenForOccupiedTables]: only orders tied to a table
                        // count as "open dine-in" for the badge. Otherwise orphan DINE_IN docs (no tableId)
                        // show a badge but no occupied table on the floor plan.
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
            }
    }

    private fun loadCurrentSales() {
        db.collection("Transactions")
            .whereEqualTo("settled", false)
            .get()
            .addOnSuccessListener { documents ->
                Executors.newSingleThreadExecutor().execute {
                    var total = 0.0
                    var count = 0

                    for (doc in documents) {
                        val voided = doc.getBoolean("voided") ?: false
                        if (voided) continue

                        val type = doc.getString("type") ?: "SALE"
                        if (type == "PRE_AUTH") continue

                        if (type == "SALE" || type == "CAPTURE") {
                            // Prefer totalPaidInCents — includes tips after Tip Adjustment / Order Detail flows;
                            // payment line amounts are not updated when tips are added.
                            val totalPaidInCentsField = doc.getLong("totalPaidInCents")
                            if (totalPaidInCentsField != null) {
                                total += totalPaidInCentsField / 100.0
                            } else {
                                val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                                var totalCents = 0L

                                for (p in payments) {
                                    val map = p as? Map<*, *> ?: continue
                                    val status = (map["status"] as? String) ?: ""
                                    if (status.equals("VOIDED", ignoreCase = true)) continue
                                    val amountInCents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                                    totalCents += amountInCents
                                }

                                if (totalCents > 0L) {
                                    total += totalCents / 100.0
                                } else {
                                    val amount = doc.getDouble("amount")
                                        ?: doc.getDouble("totalPaid")
                                        ?: 0.0
                                    total += amount
                                }
                            }
                        } else if (type == "REFUND") {
                            val amount = doc.getDouble("amount") ?: 0.0
                            total -= amount
                        }

                        count++
                    }

                    val finalTotal = if (total < 0.005) 0.0 else total
                    val finalCount = count
                    runOnUiThread {
                        if (!isDestroyed) {
                            txtTodayTotal.text = String.format(Locale.US, "$%.2f", finalTotal)
                            txtTodayCount.text = "$finalCount transactions"
                        }
                    }
                }
            }
    }
}
