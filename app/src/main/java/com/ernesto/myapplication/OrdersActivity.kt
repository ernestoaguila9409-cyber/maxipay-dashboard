package com.ernesto.myapplication

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Calendar

class OrdersActivity : AppCompatActivity() {

    private lateinit var recyclerOrders: RecyclerView
    private lateinit var btnMultiDelete: ImageButton
    private lateinit var btnFilter: ImageButton

    private lateinit var chipGroupStatus: ChipGroup
    private lateinit var chipGroupOrderType: ChipGroup
    private lateinit var txtRefundCountSummary: TextView
    private lateinit var txtEmptyState: TextView

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var adapter: OrdersAdapter
    private val orders = mutableListOf<OrderRow>()
    private val allOrders = mutableListOf<OrderRow>() // unfiltered for status/date filter

    private var listener: ListenerRegistration? = null
    private var dashboardListener: ListenerRegistration? = null

    /** orderId → SENT / PREPARING / READY for list-row icon (orders released to kitchen only). */
    private val kitchenAggregateByOrderId = ConcurrentHashMap<String, String>()

    // Filters: "ALL", "OPEN", "CLOSED" (chips) — kept for quick tabs
    private var filter: String = "ALL"

    // Advanced filter (from filter dialog)
    private var statusFilter: String = "ALL" // ALL, OPEN, CLOSED, VOIDED, REFUNDED_FULLY, PARTIALLY_REFUNDED
    private var orderTypeFilter: String = "ALL" // ALL, DINE_IN, TO_GO
    private var employeeFilter: String? = null
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null

    private var employeeNames: List<String> = emptyList()

    // Selection mode
    private var selectionMode = false

    private var currentEmployeeName: String = ""
    private var filterBatchId: String? = null
    private var currentOrderView: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)
        KdsActiveCache.start(db)
        currentEmployeeName = intent.getStringExtra("employeeName") ?: ""
        currentOrderView = intent.getBooleanExtra("CURRENT_ORDER", false)
        filterBatchId = intent.getStringExtra("BATCH_ID")?.takeIf { it.isNotBlank() }
        if (currentOrderView) title = "Closed Order"

        recyclerOrders = findViewById(R.id.recyclerOrders)
        btnMultiDelete = findViewById(R.id.btnMultiDelete)
        btnFilter = findViewById(R.id.btnFilter)

        chipGroupStatus = findViewById(R.id.chipGroupFilter)
        chipGroupOrderType = findViewById(R.id.chipGroupOrderType)
        txtRefundCountSummary = findViewById(R.id.txtRefundCountSummary)
        txtEmptyState = findViewById(R.id.txtEmptyState)

        adapter = OrdersAdapter(
            onOrderClick = { order ->
                if (selectionMode) {
                    if (order.status != "OPEN") {
                        Toast.makeText(this, "Only open orders can be deleted.", Toast.LENGTH_SHORT).show()
                        return@OrdersAdapter
                    }
                    adapter.toggleSelected(order.id)
                    updateDeleteButtonState()
                } else {
                    val intent = Intent(this, OrderDetailActivity::class.java)
                    intent.putExtra("orderId", order.id)
                    intent.putExtra("employeeName", currentEmployeeName)
                    startActivity(intent)
                }
            },
            onOrderLongPress = { order ->
                if (!selectionMode) {
                    selectionMode = true
                    if (order.status == "OPEN") adapter.toggleSelected(order.id)
                    updateDeleteButtonState()
                    true
                } else {
                    false
                }
            }
        )

        recyclerOrders.layoutManager = LinearLayoutManager(this)
        recyclerOrders.adapter = adapter

        btnMultiDelete.setOnClickListener {
            if (!selectionMode) {
                selectionMode = true
                updateDeleteButtonState()
            } else {
                val selected = adapter.getSelectedIds()
                if (selected.isEmpty()) {
                    selectionMode = false
                    adapter.clearSelection()
                    updateDeleteButtonState()
                } else {
                    // Only open orders can be deleted
                    val openOrderIds = orders.filter { it.status == "OPEN" }.map { it.id }.toSet()
                    val toDelete = selected.intersect(openOrderIds)
                    val skipped = selected.size - toDelete.size
                    if (toDelete.isEmpty()) {
                        Toast.makeText(this, "Only open orders can be deleted. Select open orders.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (skipped > 0) {
                        adapter.clearSelection()
                        toDelete.forEach { adapter.toggleSelected(it) }
                    }
                    deleteSelectedOrders(toDelete)
                }
            }
        }

        chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            statusFilter = when (checkedId) {
                R.id.chipOpen -> "OPEN"
                R.id.chipClosed -> "NOT_OPEN"
                else -> "ALL"
            }
            filter = when (checkedId) {
                R.id.chipOpen -> "OPEN"
                R.id.chipClosed -> "CLOSED"
                else -> "ALL"
            }
            applyAndRefresh()
        }

        chipGroupOrderType.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            orderTypeFilter = when (checkedId) {
                R.id.chipDineIn -> "DINE_IN"
                R.id.chipToGo -> "TO_GO"
                R.id.chipBar -> "BAR"
                R.id.chipOnline -> "ONLINE"
                else -> "ALL"
            }
            applyAndRefresh()
        }

        btnFilter.setOnClickListener { showFilterDialog() }

        if (intent.getBooleanExtra("FILTER_ONLINE", false)) {
            chipGroupOrderType.check(R.id.chipOnline)
            chipGroupStatus.check(R.id.chipOpen)
            orderTypeFilter = "ONLINE"
            statusFilter = "OPEN"
            filter = "OPEN"
        }
    }

    override fun onStart() {
        super.onStart()
        loadEmployeeNames()
        startListening()
        dashboardListener?.remove()
        dashboardListener = DashboardConfigManager.listenConfig(
            db,
            onUpdate = { modules ->
                OrderTypeColorResolver.updateFromDashboard(modules)
                runOnUiThread { adapter.notifyDataSetChanged() }
            },
        )
    }

    private fun loadEmployeeNames() {
        db.collection("Employees").get()
            .addOnSuccessListener { snap ->
                employeeNames = snap.mapNotNull { it.getString("name")?.takeIf { n -> n.isNotBlank() } }
                    .distinct().sorted()
            }
    }

    override fun onStop() {
        super.onStop()
        listener?.remove()
        listener = null
        dashboardListener?.remove()
        dashboardListener = null
    }

    /**
     * Refetches line [kdsStatus] for orders touched in this snapshot so list icons stay in sync
     * (line writes alone do not include line data on the order document).
     */
    private suspend fun refreshKitchenAggregates(snap: QuerySnapshot) {
        val changes = snap.documentChanges
        val idsToRefresh = linkedSetOf<String>()
        if (changes.isEmpty()) {
            for (doc in snap.documents) {
                if (OrderListKdsAggregator.orderReleasedToKitchen(doc)) {
                    idsToRefresh.add(doc.id)
                }
            }
        } else {
            for (ch in changes) {
                when (ch.type) {
                    DocumentChange.Type.REMOVED -> kitchenAggregateByOrderId.remove(ch.document.id)
                    else -> {
                        val d = ch.document
                        if (OrderListKdsAggregator.orderReleasedToKitchen(d)) {
                            idsToRefresh.add(d.id)
                        } else {
                            kitchenAggregateByOrderId.remove(d.id)
                        }
                    }
                }
            }
        }
        coroutineScope {
            idsToRefresh.map { id ->
                async {
                    val doc = snap.documents.find { it.id == id } ?: return@async
                    val qs = doc.reference.collection("items").get().await()
                    val phase = OrderListKdsAggregator.aggregatePhase(qs.documents)
                    if (phase != null) {
                        kitchenAggregateByOrderId[id] = phase
                    } else {
                        kitchenAggregateByOrderId.remove(id)
                    }
                }
            }.awaitAll()
        }
    }

    private fun startListening() {
        listener?.remove()
        listener = null
        kitchenAggregateByOrderId.clear()

        var query: Query = db.collection("Orders")

        if (filterBatchId != null) {
            query = query.whereEqualTo("batchId", filterBatchId)
        }
        query = query.orderBy("createdAt", Query.Direction.DESCENDING)

        if (dateFromMillis != null) {
            query = query.whereGreaterThanOrEqualTo("createdAt", Timestamp(java.util.Date(dateFromMillis!!)))
        }
        if (dateToMillis != null) {
            query = query.whereLessThanOrEqualTo("createdAt", Timestamp(java.util.Date(dateToMillis!!)))
        }

        query = query.limit(200)

        listener = query.addSnapshotListener { snap, err ->
            if (err != null) {
                Toast.makeText(this, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            lifecycleScope.launch {
                if (KdsActiveCache.hasActiveKds) {
                    withContext(Dispatchers.IO) {
                        refreshKitchenAggregates(snap)
                    }
                }
                val parsed = withContext(Dispatchers.Default) {
                    val result = mutableListOf<OrderRow>()
                    for (doc in snap.documents) {
                        val id = doc.id
                        val status = doc.getString("status") ?: "OPEN"
                        val totalCents = doc.getLong("totalInCents") ?: 0L
                        val totalRefundedInCents = doc.getLong("totalRefundedInCents") ?: 0L
                        val employee = doc.getString("employeeName") ?: "—"
                        val displayEmployee = if (status == "VOIDED") {
                            doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: employee
                        } else {
                            employee
                        }
                        val customerName = doc.getString("customerName") ?: ""
                        val orderNumber = doc.getLong("orderNumber") ?: 0L
                        val createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                        val orderType = doc.getString("orderType") ?: ""
                        val orderSource = doc.getString("orderSource") ?: ""
                        val itemsCount = (doc.getLong("itemsCount") ?: 0L).toInt()
                        val preAuthAmount = doc.getDouble("preAuthAmount") ?: 0.0
                        val preAuthAmountCents = Math.round(preAuthAmount * 100)
                        val kdsAgg = if (KdsActiveCache.hasActiveKds && OrderListKdsAggregator.orderReleasedToKitchen(doc)) {
                            kitchenAggregateByOrderId[id]
                        } else {
                            null
                        }
                        result.add(
                            OrderRow(
                                id = id,
                                orderNumber = orderNumber,
                                status = status,
                                totalCents = totalCents,
                                totalRefundedInCents = totalRefundedInCents,
                                employeeName = displayEmployee,
                                customerName = customerName,
                                createdAt = createdAt,
                                orderType = orderType,
                                preAuthAmountCents = preAuthAmountCents,
                                kdsAggregateStatus = kdsAgg,
                                orderSource = orderSource,
                                itemsCount = itemsCount,
                            )
                        )
                    }
                    result
                }

                val filtered = if (currentOrderView) {
                    parsed.filter { !(it.preAuthAmountCents > 0L && it.status.uppercase() == "OPEN") }
                } else {
                    parsed
                }

                allOrders.clear()
                allOrders.addAll(filtered)
                orders.clear()
                orders.addAll(applyFilters(allOrders))
                adapter.submit(orders)
                updateDeleteButtonState()
                updateEmptyState()
                updateRefundCountSummary()

                if (currentOrderView && orders.isEmpty()) {
                    AlertDialog.Builder(this@OrdersActivity)
                        .setMessage("NO ORDERS AT THE MOMENT")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    private fun applyAndRefresh() {
        orders.clear()
        orders.addAll(applyFilters(allOrders))
        adapter.submit(orders)
        updateEmptyState()
        updateRefundCountSummary()
    }

    private fun updateEmptyState() {
        if (orders.isEmpty()) {
            val msg = if (orderTypeFilter == "ONLINE") "No online orders yet"
                      else "No orders found"
            txtEmptyState.text = msg
            txtEmptyState.visibility = View.VISIBLE
            recyclerOrders.visibility = View.GONE
        } else {
            txtEmptyState.visibility = View.GONE
            recyclerOrders.visibility = View.VISIBLE
        }
    }

    private fun updateRefundCountSummary() {
        if (employeeFilter == null) {
            txtRefundCountSummary.visibility = View.GONE
            return
        }
        val fullyRefundedCount = allOrders.count { it.employeeName == employeeFilter && isFullyRefunded(it) }
        txtRefundCountSummary.text = "$fullyRefundedCount order(s) fully refunded by $employeeFilter"
        txtRefundCountSummary.visibility = View.VISIBLE
    }

    private fun applyFilters(list: List<OrderRow>): List<OrderRow> {
        var result = applyStatusFilter(list)
        if (orderTypeFilter != "ALL") {
            result = when (orderTypeFilter) {
                "BAR" -> result.filter { it.orderType == "BAR" || it.orderType == "BAR_TAB" }
                "ONLINE" -> result.filter { it.orderSource.isNotBlank() }
                else -> result.filter { it.orderType == orderTypeFilter }
            }
        }
        if (employeeFilter != null) {
            result = result.filter { it.employeeName == employeeFilter }
        }
        return result
    }

    private fun isFullyRefunded(order: OrderRow): Boolean =
        order.status == "REFUNDED" || order.totalRefundedInCents >= order.totalCents

    private fun applyStatusFilter(list: List<OrderRow>): List<OrderRow> {
        return when (statusFilter) {
            "OPEN" -> list.filter { it.status == "OPEN" }
            "NOT_OPEN" -> list.filter { it.status != "OPEN" }
            "CLOSED" -> list.filter { it.status == "CLOSED" && it.totalRefundedInCents == 0L }
            "VOIDED" -> list.filter { it.status == "VOIDED" }
            "REFUNDED_FULLY" -> list.filter { it.status == "REFUNDED" || it.totalRefundedInCents >= it.totalCents }
            "PARTIALLY_REFUNDED" -> list.filter { it.status == "CLOSED" && it.totalRefundedInCents > 0L && it.totalRefundedInCents < it.totalCents }
            else -> list
        }
    }

    private fun showFilterDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_orders_filter, null)
        val radioAll = view.findViewById<RadioButton>(R.id.radioAll)
        val radioOpen = view.findViewById<RadioButton>(R.id.radioOpen)
        val radioClosed = view.findViewById<RadioButton>(R.id.radioClosed)
        val radioVoided = view.findViewById<RadioButton>(R.id.radioVoided)
        val radioRefundedFully = view.findViewById<RadioButton>(R.id.radioRefundedFully)
        val radioPartiallyRefunded = view.findViewById<RadioButton>(R.id.radioPartiallyRefunded)
        val spinnerEmployee = view.findViewById<Spinner>(R.id.spinnerEmployee)
        val txtDateFrom = view.findViewById<TextView>(R.id.txtDateFrom)
        val txtDateTo = view.findViewById<TextView>(R.id.txtDateTo)

        val options = listOf("All Employees") + employeeNames
        spinnerEmployee.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val empIndex = employeeFilter?.let { name -> employeeNames.indexOf(name).takeIf { it >= 0 }?.plus(1) } ?: 0
        spinnerEmployee.setSelection(empIndex.coerceAtMost(options.size - 1))

        when (statusFilter) {
            "OPEN" -> radioOpen.isChecked = true
            "CLOSED" -> radioClosed.isChecked = true
            "VOIDED" -> radioVoided.isChecked = true
            "REFUNDED_FULLY" -> radioRefundedFully.isChecked = true
            "PARTIALLY_REFUNDED" -> radioPartiallyRefunded.isChecked = true
            else -> radioAll.isChecked = true
        }

        val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        fun formatDate(ms: Long?) = if (ms != null) dateFormat.format(ms) else "Any"
        txtDateFrom.text = "From: ${formatDate(dateFromMillis)}"
        txtDateTo.text = "To: ${formatDate(dateToMillis)}"

        txtDateFrom.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            dateFromMillis?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                dateFromMillis = cal.timeInMillis
                txtDateFrom.text = "From: ${formatDate(dateFromMillis)}"
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        txtDateTo.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            dateToMillis?.let { cal.timeInMillis = it }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                cal.set(java.util.Calendar.MILLISECOND, 999)
                dateToMillis = cal.timeInMillis
                txtDateTo.text = "To: ${formatDate(dateToMillis)}"
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        view.findViewById<MaterialButton>(R.id.btnFilterClear).setOnClickListener {
            val datesChanged = dateFromMillis != null || dateToMillis != null
            statusFilter = "ALL"
            orderTypeFilter = "ALL"
            employeeFilter = null
            dateFromMillis = null
            dateToMillis = null
            filter = "ALL"
            chipGroupStatus.check(R.id.chipAll)
            chipGroupOrderType.clearCheck()
            dialog.dismiss()
            if (datesChanged) startListening() else applyAndRefresh()
        }
        view.findViewById<MaterialButton>(R.id.btnFilterApply).setOnClickListener {
            val checkedId = view.findViewById<android.widget.RadioGroup>(R.id.radioGroupStatus).checkedRadioButtonId
            statusFilter = when (checkedId) {
                R.id.radioOpen -> "OPEN"
                R.id.radioClosed -> "CLOSED"
                R.id.radioVoided -> "VOIDED"
                R.id.radioRefundedFully -> "REFUNDED_FULLY"
                R.id.radioPartiallyRefunded -> "PARTIALLY_REFUNDED"
                else -> "ALL"
            }
            val empPos = spinnerEmployee.selectedItemPosition
            employeeFilter = if (empPos > 0 && empPos <= employeeNames.size) employeeNames[empPos - 1] else null
            filter = if (statusFilter in listOf("ALL", "OPEN", "CLOSED")) statusFilter else "ALL"
            when (filter) {
                "OPEN" -> chipGroupStatus.check(R.id.chipOpen)
                "CLOSED" -> chipGroupStatus.check(R.id.chipClosed)
                else -> chipGroupStatus.check(R.id.chipAll)
            }
            dialog.dismiss()
            applyAndRefresh()
        }
        dialog.show()
    }

    private fun updateDeleteButtonState() {
        val selectedCount = adapter.getSelectedIds().size
        btnMultiDelete.alpha = if (!selectionMode) 1f else if (selectedCount == 0) 0.6f else 1f
    }

    private fun deleteSelectedOrders(selectedIds: Set<String>) {
        // selectedIds are already restricted to OPEN orders by caller
        lifecycleScope.launch {
            try {
                btnMultiDelete.isEnabled = false

                withContext(Dispatchers.IO) {
                    for (orderId in selectedIds) {
                        deleteOrderWithItems(orderId)
                    }
                }

                Toast.makeText(this@OrdersActivity, "Deleted ${selectedIds.size} order(s)", Toast.LENGTH_SHORT).show()

                selectionMode = false
                adapter.clearSelection()
                updateDeleteButtonState()

            } catch (e: Exception) {
                Toast.makeText(this@OrdersActivity, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnMultiDelete.isEnabled = true
            }
        }
    }

    private suspend fun deleteOrderWithItems(orderId: String) {
        val orderRef = db.collection("Orders").document(orderId)

        val itemsSnap = orderRef.collection("items").get().await()
        for (item in itemsSnap.documents) {
            item.reference.delete().await()
        }

        orderRef.delete().await()
    }
}