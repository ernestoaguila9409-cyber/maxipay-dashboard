package com.ernesto.myapplication

import android.animation.ObjectAnimator
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CashFlowActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val currencyFmt = NumberFormat.getCurrencyInstance()
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

    private lateinit var progressBar: ProgressBar
    private lateinit var cardCashIn: MaterialCardView
    private lateinit var cardCashOut: MaterialCardView
    private lateinit var cardDrawerTotal: MaterialCardView
    private lateinit var cardCashActivity: MaterialCardView

    private lateinit var valCashSales: TextView
    private lateinit var valCashAdded: TextView
    private lateinit var valTotalCashIn: TextView
    private lateinit var valCashRefunds: TextView
    private lateinit var valPaidOuts: TextView
    private lateinit var valTotalCashOut: TextView
    private lateinit var valStartingCash: TextView
    private lateinit var valExpectedDrawer: TextView
    private lateinit var txtActivityCount: TextView
    private lateinit var txtEmptyActivity: TextView
    private lateinit var txtDate: TextView

    private lateinit var btnFilterBatch: MaterialButton
    private lateinit var btnFilterDate: MaterialButton
    private lateinit var rowBatchSelector: LinearLayout
    private lateinit var rowDateSelector: LinearLayout
    private lateinit var txtSelectedBatch: TextView
    private lateinit var txtSelectedDate: TextView
    private lateinit var btnChangeBatch: MaterialButton
    private lateinit var btnChangeDate: MaterialButton

    private lateinit var headerCashActivity: LinearLayout
    private lateinit var layoutCashActivityContent: LinearLayout
    private lateinit var imgExpandArrow: ImageView
    private var cashActivityExpanded = false

    private lateinit var recyclerCashActivity: RecyclerView
    private val cashActivityAdapter = CashActivityAdapter()

    private var transactionListener: ListenerRegistration? = null
    private var startingCash = 0.0

    private enum class FilterMode { BATCH, DATE }

    private var filterMode = FilterMode.BATCH

    private var currentBatchId: String? = null
    private var currentBatchLabel: String = "Current Open Batch"
    private var selectedDate: Date = Date()

    private data class BatchInfo(val id: String, val label: String, val closed: Boolean, val startingCash: Double)
    private var batchList = listOf<BatchInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cash_flow)
        supportActionBar?.hide()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        txtDate = findViewById(R.id.txtDate)
        txtDate.text = dateFmt.format(Date())

        progressBar = findViewById(R.id.progressBar)
        cardCashIn = findViewById(R.id.cardCashIn)
        cardCashOut = findViewById(R.id.cardCashOut)
        cardDrawerTotal = findViewById(R.id.cardDrawerTotal)
        cardCashActivity = findViewById(R.id.cardCashActivity)

        valCashSales = findViewById(R.id.valCashSales)
        valCashAdded = findViewById(R.id.valCashAdded)
        valTotalCashIn = findViewById(R.id.valTotalCashIn)
        valCashRefunds = findViewById(R.id.valCashRefunds)
        valPaidOuts = findViewById(R.id.valPaidOuts)
        valTotalCashOut = findViewById(R.id.valTotalCashOut)
        valStartingCash = findViewById(R.id.valStartingCash)
        valExpectedDrawer = findViewById(R.id.valExpectedDrawer)
        txtActivityCount = findViewById(R.id.txtActivityCount)
        txtEmptyActivity = findViewById(R.id.txtEmptyActivity)

        btnFilterBatch = findViewById(R.id.btnFilterBatch)
        btnFilterDate = findViewById(R.id.btnFilterDate)
        rowBatchSelector = findViewById(R.id.rowBatchSelector)
        rowDateSelector = findViewById(R.id.rowDateSelector)
        txtSelectedBatch = findViewById(R.id.txtSelectedBatch)
        txtSelectedDate = findViewById(R.id.txtSelectedDate)
        btnChangeBatch = findViewById(R.id.btnChangeBatch)
        btnChangeDate = findViewById(R.id.btnChangeDate)

        headerCashActivity = findViewById(R.id.headerCashActivity)
        layoutCashActivityContent = findViewById(R.id.layoutCashActivityContent)
        imgExpandArrow = findViewById(R.id.imgExpandArrow)

        recyclerCashActivity = findViewById(R.id.recyclerCashActivity)
        recyclerCashActivity.layoutManager = LinearLayoutManager(this)
        recyclerCashActivity.adapter = cashActivityAdapter

        headerCashActivity.setOnClickListener { toggleCashActivity() }
        findViewById<LinearLayout>(R.id.rowStartingCash).setOnClickListener { showSetStartingCashDialog() }

        txtSelectedDate.text = dateFmt.format(selectedDate)

        btnFilterBatch.setOnClickListener { switchFilter(FilterMode.BATCH) }
        btnFilterDate.setOnClickListener { switchFilter(FilterMode.DATE) }
        btnChangeBatch.setOnClickListener { showBatchPicker() }
        btnChangeDate.setOnClickListener { showDatePicker() }

        loadOpenBatchAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        transactionListener?.remove()
    }

    // ── Cash Activity collapse / expand ───────────────────────────────

    private fun toggleCashActivity() {
        cashActivityExpanded = !cashActivityExpanded
        if (cashActivityExpanded) {
            layoutCashActivityContent.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(imgExpandArrow, View.ROTATION, 0f, 180f)
                .setDuration(200).start()
        } else {
            layoutCashActivityContent.visibility = View.GONE
            ObjectAnimator.ofFloat(imgExpandArrow, View.ROTATION, 180f, 0f)
                .setDuration(200).start()
        }
    }

    // ── Filter toggle ──────────────────────────────────────────────────

    private fun switchFilter(mode: FilterMode) {
        filterMode = mode
        if (mode == FilterMode.BATCH) {
            btnFilterBatch.setBackgroundColor(getColor(com.google.android.material.R.color.design_default_color_primary))
            btnFilterBatch.setTextColor(getColor(android.R.color.white))
            btnFilterDate.setBackgroundColor(getColor(android.R.color.transparent))
            btnFilterDate.setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
            btnFilterDate.strokeColor = android.content.res.ColorStateList.valueOf(
                getColor(com.google.android.material.R.color.design_default_color_primary)
            )
            btnFilterDate.strokeWidth = 1

            rowBatchSelector.visibility = View.VISIBLE
            rowDateSelector.visibility = View.GONE
            reloadData()
        } else {
            btnFilterDate.setBackgroundColor(getColor(com.google.android.material.R.color.design_default_color_primary))
            btnFilterDate.setTextColor(getColor(android.R.color.white))
            btnFilterBatch.setBackgroundColor(getColor(android.R.color.transparent))
            btnFilterBatch.setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
            btnFilterBatch.strokeColor = android.content.res.ColorStateList.valueOf(
                getColor(com.google.android.material.R.color.design_default_color_primary)
            )
            btnFilterBatch.strokeWidth = 1

            rowBatchSelector.visibility = View.GONE
            rowDateSelector.visibility = View.VISIBLE
            reloadData()
        }
    }

    // ── Date picker ────────────────────────────────────────────────────

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { time = selectedDate }
        DatePickerDialog(this, { _, y, m, d ->
            val picked = Calendar.getInstance().apply { set(y, m, d) }
            selectedDate = picked.time
            txtSelectedDate.text = dateFmt.format(selectedDate)
            txtDate.text = dateFmt.format(selectedDate)
            reloadData()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── Batch picker ───────────────────────────────────────────────────

    private fun showBatchPicker() {
        progressBar.visibility = View.VISIBLE
        db.collection("Batches")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                progressBar.visibility = View.GONE
                val dateFmtShort = SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.getDefault())
                batchList = snap.documents.map { doc ->
                    val closed = doc.getBoolean("closed") == true
                    val createdAt = doc.getDate("createdAt")
                    val closedAt = doc.getDate("closedAt")
                    val label = if (closed) {
                        "Closed – ${dateFmtShort.format(closedAt ?: createdAt ?: Date())}"
                    } else {
                        "Open – ${dateFmtShort.format(createdAt ?: Date())}"
                    }
                    BatchInfo(
                        id = doc.id,
                        label = label,
                        closed = closed,
                        startingCash = doc.getDouble("startingCash") ?: 0.0
                    )
                }
                if (batchList.isEmpty()) {
                    Toast.makeText(this, "No batches found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val labels = batchList.map { it.label }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select Batch")
                    .setItems(labels) { _, which ->
                        val selected = batchList[which]
                        currentBatchId = selected.id
                        currentBatchLabel = selected.label
                        startingCash = selected.startingCash
                        txtSelectedBatch.text = selected.label
                        reloadData()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to load batches: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Set Starting Cash ────────────────────────────────────────────────

    private fun showSetStartingCashDialog() {
        val batchId = currentBatchId
        if (batchId == null) {
            Toast.makeText(this, "No open batch to set starting cash", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.00"
            if (startingCash > 0.0) setText(String.format(Locale.US, "%.2f", startingCash))
            setSelectAllOnFocus(true)
        }

        val container = FrameLayout(this).apply {
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Starting Cash")
            .setMessage("Enter the amount of cash currently in the drawer.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toDoubleOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveStartingCash(batchId, value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveStartingCash(batchId: String, amount: Double) {
        db.collection("Batches").document(batchId)
            .update("startingCash", amount)
            .addOnSuccessListener {
                startingCash = amount
                valStartingCash.text = currencyFmt.format(amount)
                reloadData()
                Toast.makeText(this, "Starting cash set to ${currencyFmt.format(amount)}", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Initial load: find the open batch ──────────────────────────────

    private fun loadOpenBatchAndStart() {
        progressBar.visibility = View.VISIBLE

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    val doc = snap.documents[0]
                    currentBatchId = doc.id
                    startingCash = doc.getDouble("startingCash") ?: 0.0
                    currentBatchLabel = "Current Open Batch"
                    txtSelectedBatch.text = currentBatchLabel
                } else {
                    currentBatchId = null
                    startingCash = 0.0
                    currentBatchLabel = "No open batch"
                    txtSelectedBatch.text = currentBatchLabel
                }
                reloadData()
            }
            .addOnFailureListener { e ->
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to load batch: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Reload based on current filter ─────────────────────────────────

    private fun reloadData() {
        transactionListener?.remove()
        transactionListener = null

        cardCashIn.visibility = View.GONE
        cardCashOut.visibility = View.GONE
        cardDrawerTotal.visibility = View.GONE
        cardCashActivity.visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardStartingCash).visibility = View.GONE
        progressBar.visibility = View.VISIBLE

        when (filterMode) {
            FilterMode.BATCH -> {
                val batchId = currentBatchId
                if (batchId == null) {
                    bindData(0.0, 0.0, 0.0, 0.0, 0.0)
                    bindActivityLog(emptyList())
                    return
                }
                listenByBatch(batchId)
            }
            FilterMode.DATE -> {
                val (start, end) = dayRange(selectedDate)
                txtDate.text = dateFmt.format(selectedDate)
                loadStartingCashForDate(start)
                listenByDateRange(start, end)
            }
        }
    }

    private fun dayRange(date: Date = Date()): Pair<Date, Date> {
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.time
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.time
    }

    private fun loadStartingCashForDate(dayStart: Date) {
        db.collection("Batches")
            .whereLessThanOrEqualTo("createdAt", dayStart)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                startingCash = if (!snap.isEmpty) {
                    snap.documents[0].getDouble("startingCash") ?: 0.0
                } else 0.0
            }
    }

    // ── Listen by batch ────────────────────────────────────────────────

    private fun listenByBatch(batchId: String) {
        transactionListener = db.collection("Transactions")
            .whereEqualTo("batchId", batchId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed to load cash flow: ${error.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener
                processSnapshots(snapshots.documents)
            }
    }

    // ── Listen by date range ───────────────────────────────────────────

    private fun listenByDateRange(start: Date, end: Date) {
        transactionListener = db.collection("Transactions")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed to load cash flow: ${error.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener
                processSnapshots(snapshots.documents)
            }
    }

    // ── Shared transaction processing ──────────────────────────────────

    private fun processSnapshots(documents: List<com.google.firebase.firestore.DocumentSnapshot>) {
        var cashSales = 0.0
        var cashAdded = 0.0
        var cashRefunds = 0.0
        var paidOuts = 0.0
        val activityItems = mutableListOf<CashActivityItem>()

        for (doc in documents) {
            if (doc.getBoolean("voided") == true) continue

            val type = doc.getString("type") ?: ""
            val orderNumber = doc.getLong("orderNumber") ?: 0L
            val createdAt = doc.getDate("createdAt") ?: Date()

            when (type) {
                "SALE", "CAPTURE" -> {
                    val cashInfo = extractCashPaymentInfo(doc)
                    if (cashInfo != null) {
                        cashSales += cashInfo.amountCents / 100.0
                        activityItems.add(
                            CashActivityItem(
                                timestamp = cashInfo.timestamp ?: createdAt,
                                orderNumber = orderNumber,
                                type = type,
                                amountDueCents = cashInfo.amountCents,
                                tenderedCents = cashInfo.tenderedCents,
                                changeCents = cashInfo.changeCents
                            )
                        )
                    }
                }
                "REFUND" -> {
                    if (isCashTransaction(doc)) {
                        val amount = resolveAmount(doc)
                        cashRefunds += amount
                        activityItems.add(
                            CashActivityItem(
                                timestamp = createdAt,
                                orderNumber = orderNumber,
                                type = type,
                                amountDueCents = (amount * 100).toLong(),
                                tenderedCents = 0L,
                                changeCents = 0L
                            )
                        )
                    }
                }
                "CASH_ADD" -> {
                    val amount = resolveAmount(doc)
                    cashAdded += amount
                    activityItems.add(
                        CashActivityItem(
                            timestamp = createdAt,
                            orderNumber = 0L,
                            type = type,
                            amountDueCents = (amount * 100).toLong(),
                            tenderedCents = 0L,
                            changeCents = 0L
                        )
                    )
                }
                "PAID_OUT" -> {
                    val amount = resolveAmount(doc)
                    paidOuts += amount
                    activityItems.add(
                        CashActivityItem(
                            timestamp = createdAt,
                            orderNumber = 0L,
                            type = type,
                            amountDueCents = (amount * 100).toLong(),
                            tenderedCents = 0L,
                            changeCents = 0L
                        )
                    )
                }
            }
        }

        activityItems.sortByDescending { it.timestamp }
        bindData(startingCash, cashSales, cashAdded, cashRefunds, paidOuts)
        bindActivityLog(activityItems)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private data class CashPaymentInfo(
        val amountCents: Long,
        val tenderedCents: Long,
        val changeCents: Long,
        val timestamp: Date?
    )

    @Suppress("UNCHECKED_CAST")
    private fun extractCashPaymentInfo(doc: com.google.firebase.firestore.DocumentSnapshot): CashPaymentInfo? {
        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()

        for (p in payments) {
            if ((p["status"] as? String) == "VOIDED") continue
            val method = (p["paymentType"] as? String) ?: ""
            if (method.equals("Cash", ignoreCase = true)) {
                val amountCents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
                val tenderedCents = (p["cashTenderedInCents"] as? Number)?.toLong() ?: 0L
                val changeCents = (p["cashChangeInCents"] as? Number)?.toLong() ?: 0L
                val ts = (p["timestamp"] as? com.google.firebase.Timestamp)?.toDate()
                    ?: (p["timestamp"] as? Date)
                return CashPaymentInfo(amountCents, tenderedCents, changeCents, ts)
            }
        }

        if (isCashTransaction(doc)) {
            val amount = doc.getDouble("amount")
                ?: ((doc.getLong("totalPaidInCents") ?: 0L) / 100.0)
            return CashPaymentInfo((amount * 100).toLong(), 0L, 0L, null)
        }
        return null
    }

    private fun isCashTransaction(doc: com.google.firebase.firestore.DocumentSnapshot): Boolean {
        val pt = doc.getString("paymentType")
        if (pt != null) return pt.equals("Cash", ignoreCase = true)

        @Suppress("UNCHECKED_CAST")
        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        return payments.any {
            ((it["paymentType"] as? String) ?: "").equals("Cash", ignoreCase = true)
        }
    }

    private fun resolveAmount(doc: com.google.firebase.firestore.DocumentSnapshot): Double {
        val cents = doc.getLong("amountInCents")
        if (cents != null && cents > 0L) return cents / 100.0

        return doc.getDouble("amount")
            ?: doc.getDouble("totalPaid")
            ?: ((doc.getLong("totalPaidInCents") ?: 0L) / 100.0)
    }

    private fun bindData(
        startingCash: Double,
        cashSales: Double,
        cashAdded: Double,
        cashRefunds: Double,
        paidOuts: Double
    ) {
        progressBar.visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardStartingCash).visibility = View.VISIBLE
        cardCashIn.visibility = View.VISIBLE
        cardCashOut.visibility = View.VISIBLE
        cardDrawerTotal.visibility = View.VISIBLE

        valCashSales.text = currencyFmt.format(cashSales)
        valCashAdded.text = currencyFmt.format(cashAdded)
        valTotalCashIn.text = currencyFmt.format(cashSales + cashAdded)

        valCashRefunds.text = currencyFmt.format(cashRefunds)
        valPaidOuts.text = currencyFmt.format(paidOuts)
        valTotalCashOut.text = currencyFmt.format(cashRefunds + paidOuts)

        valStartingCash.text = currencyFmt.format(startingCash)

        val expectedDrawer = startingCash + cashSales + cashAdded - cashRefunds - paidOuts
        valExpectedDrawer.text = currencyFmt.format(expectedDrawer)
    }

    private fun bindActivityLog(items: List<CashActivityItem>) {
        cardCashActivity.visibility = View.VISIBLE

        if (items.isEmpty()) {
            txtEmptyActivity.visibility = View.VISIBLE
            recyclerCashActivity.visibility = View.GONE
            txtActivityCount.text = ""
        } else {
            txtEmptyActivity.visibility = View.GONE
            recyclerCashActivity.visibility = View.VISIBLE
            txtActivityCount.text = "${items.size} transaction${if (items.size != 1) "s" else ""}"
            cashActivityAdapter.submitList(items)
        }
    }
}
