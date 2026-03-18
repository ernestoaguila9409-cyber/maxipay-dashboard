package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView
    private lateinit var dashboardAdapter: DashboardAdapter

    private var openOrdersListener: ListenerRegistration? = null
    private var dashboardConfigListener: ListenerRegistration? = null
    private var currentBatchId: String = ""

    private var employeeName: String = ""
    private var employeeRole: String = ""

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
        txtLoggedUser.text = "Logged in as: $employeeName ($employeeRole)"

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        setupDashboardGrid()
        listenForOpenOrders()
        ensureOpenBatch()

        findViewById<View>(R.id.todaySalesArea).setOnClickListener {
            startActivity(Intent(this, TodaySalesActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnLogout).setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        loadCurrentSales()
    }

    private fun setupDashboardGrid() {
        val rv = findViewById<RecyclerView>(R.id.dashboardGrid)
        rv.layoutManager = GridLayoutManager(this, 3)

        dashboardAdapter = DashboardAdapter(mutableListOf()) { module ->
            handleModuleClick(module)
        }
        rv.adapter = dashboardAdapter

        dashboardConfigListener = DashboardConfigManager.listenConfig(
            db,
            onUpdate = { modules ->
                if (!isDestroyed) {
                    dashboardAdapter.setModules(modules)
                    applyOrderTypeVisibility()
                }
            },
            onCacheThenServer = { serverModules ->
                if (!isDestroyed && serverModules.isNotEmpty()) {
                    dashboardAdapter.setModules(serverModules)
                }
            }
        )
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
            "setup" -> {
                val i = Intent(this, SideMenuActivity::class.java)
                i.putExtra("employeeName", employeeName)
                i.putExtra("employeeRole", employeeRole)
                startActivity(i)
            }
            "modifiers" -> startActivity(Intent(this, GlobalModifierActivity::class.java))
            "inventory" -> {
                val i = Intent(this, MenuOnlyActivity::class.java)
                i.putExtra("batchId", currentBatchId)
                startActivity(i)
            }
            "reports" -> startActivity(Intent(this, ReportsActivity::class.java))
            "cash_flow" -> startActivity(Intent(this, CashFlowActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadCurrentSales()
        applyOrderTypeVisibility()
        refreshDashboardFromServer()
    }

    private fun refreshDashboardFromServer() {
        DashboardConfigManager.loadFromServer(db) { modules ->
            if (!isDestroyed && modules.isNotEmpty()) {
                dashboardAdapter.setModules(modules)
            }
        }
    }

    private fun applyOrderTypeVisibility() {
        // Order type visibility is now handled via the adapter; hidden modules
        // still exist in config but we skip rendering disabled ones.
        // For now we keep all visible — the config screen controls order.
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
                        "type" to "OPEN"
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
                        "DINE_IN" -> dineIn++
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

                        if (type == "SALE" || type == "CAPTURE") {
                            val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                            var totalCents = 0L

                            for (p in payments) {
                                val map = p as? Map<*, *> ?: continue
                                val amountInCents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                                totalCents += amountInCents
                            }

                            if (totalCents > 0L) {
                                total += totalCents / 100.0
                                count++
                            } else {
                                val amount = doc.getDouble("amount")
                                    ?: doc.getDouble("totalPaid")
                                    ?: 0.0
                                if (amount > 0.0) {
                                    total += amount
                                    count++
                                }
                            }
                        } else if (type == "REFUND") {
                            val amount = doc.getDouble("amount") ?: 0.0
                            if (amount > 0.0) {
                                total -= amount
                                count++
                            }
                        }
                    }

                    val finalTotal = total
                    val finalCount = count
                    runOnUiThread {
                        if (!isDestroyed) {
                            txtTodayTotal.text = String.format(Locale.US, "Current Sales: $%.2f", finalTotal)
                            txtTodayCount.text = "Transactions: $finalCount"
                        }
                    }
                }
            }
    }
}
