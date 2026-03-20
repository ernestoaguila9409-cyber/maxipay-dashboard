package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.CardBrandSale
import com.ernesto.myapplication.engine.CategorySalesRow
import com.ernesto.myapplication.engine.DiscountBreakdown
import com.ernesto.myapplication.engine.EmployeeReportData
import com.ernesto.myapplication.engine.HourlySale
import com.ernesto.myapplication.engine.ItemSalesRow
import com.ernesto.myapplication.engine.MenuPerformanceEngine
import com.ernesto.myapplication.engine.ModifierSalesRow
import com.ernesto.myapplication.engine.PaymentMethodBreakdown
import com.ernesto.myapplication.engine.DineInSectionSale
import com.ernesto.myapplication.engine.DineInTableSale
import com.ernesto.myapplication.engine.ReportEngine
import com.ernesto.myapplication.engine.SalesByOrderType
import com.ernesto.myapplication.engine.TaxByOrderType
import com.ernesto.myapplication.engine.TaxByTaxName
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class ReportsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val reportEngine = ReportEngine(db)
    private val menuPerfEngine = MenuPerformanceEngine(db)
    private val currencyFmt = NumberFormat.getCurrencyInstance()

    private lateinit var progressBar: ProgressBar
    private lateinit var card: MaterialCardView
    private lateinit var valGross: TextView
    private lateinit var valDiscounts: TextView
    private lateinit var valTax: TextView
    private lateinit var valTips: TextView
    private lateinit var valNet: TextView
    private lateinit var valTxCount: TextView
    private lateinit var valAvg: TextView
    private lateinit var valItemsSold: TextView
    private lateinit var valRefunds: TextView
    private lateinit var valVoidedItems: TextView
    private lateinit var valCash: TextView
    private lateinit var valCredit: TextView
    private lateinit var valDebit: TextView
    private lateinit var rowTaxCollected: LinearLayout
    private lateinit var arrowTax: TextView
    private lateinit var contentTaxBreakdown: LinearLayout
    private lateinit var rowDiscounts: LinearLayout
    private lateinit var arrowDiscount: TextView
    private lateinit var contentDiscountBreakdown: LinearLayout

    private lateinit var progressOrderType: ProgressBar
    private lateinit var cardOrderType: MaterialCardView
    private lateinit var valDineIn: TextView
    private lateinit var valDineInPct: TextView
    private lateinit var rowDineIn: LinearLayout
    private lateinit var arrowDineIn: TextView
    private lateinit var contentDineInTables: LinearLayout
    private lateinit var valToGo: TextView
    private lateinit var valToGoPct: TextView
    private lateinit var valBar: TextView
    private lateinit var valBarPct: TextView
    private lateinit var valOrderTypeTotal: TextView

    private lateinit var progressCardBrand: ProgressBar
    private lateinit var cardCardBrand: MaterialCardView
    private lateinit var contentCardBrand: LinearLayout

    private lateinit var progressEmployee: ProgressBar
    private lateinit var cardEmployee: MaterialCardView
    private lateinit var contentEmployee: LinearLayout

    private lateinit var progressHourlySales: ProgressBar
    private lateinit var cardHourlySales: MaterialCardView
    private lateinit var contentHourlySales: LinearLayout

    private lateinit var progressMenuPerf: ProgressBar
    private lateinit var containerMenuPerf: LinearLayout

    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private var selectedStartDate: Date = Date()
    private var selectedEndDate: Date = Date()

    private var allEmployeeData: List<EmployeeReportData> = emptyList()
    private var selectedEmployeeFilter: String? = null

    private var globalEmployeeFilter: String? = null
    private lateinit var spinnerEmployeeFilter: Spinner
    private var employeeNames: List<String> = emptyList()
    private var spinnerInitialised = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)
        supportActionBar?.hide()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupCollapsibleCard(
            header = findViewById(R.id.headerMenuPerformance),
            content = findViewById(R.id.contentMenuPerformance),
            arrow = findViewById(R.id.arrowMenuPerformance)
        )
        progressMenuPerf = findViewById(R.id.progressMenuPerf)
        containerMenuPerf = findViewById(R.id.containerMenuPerf)

        spinnerEmployeeFilter = findViewById(R.id.spinnerEmployeeFilter)
        loadEmployeeNames()

        updateDateRangeDisplay()
        findViewById<View>(R.id.rowDateFilter).setOnClickListener { showDateRangePicker() }

        progressBar = findViewById(R.id.progressBar)
        card = findViewById(R.id.cardDailySummary)
        valGross = findViewById(R.id.valGrossSales)
        valDiscounts = findViewById(R.id.valDiscounts)
        valTax = findViewById(R.id.valTaxCollected)
        valTips = findViewById(R.id.valTips)
        valNet = findViewById(R.id.valNetSales)
        valTxCount = findViewById(R.id.valTransactions)
        valAvg = findViewById(R.id.valAvgTicket)
        valItemsSold = findViewById(R.id.valItemsSold)
        valRefunds = findViewById(R.id.valRefunds)
        valVoidedItems = findViewById(R.id.valVoidedItems)
        valCash = findViewById(R.id.valCashPayments)
        valCredit = findViewById(R.id.valCreditPayments)
        valDebit = findViewById(R.id.valDebitPayments)
        rowTaxCollected = findViewById(R.id.rowTaxCollected)
        arrowTax = findViewById(R.id.arrowTax)
        contentTaxBreakdown = findViewById(R.id.contentTaxBreakdown)
        rowDiscounts = findViewById(R.id.rowDiscounts)
        arrowDiscount = findViewById(R.id.arrowDiscount)
        contentDiscountBreakdown = findViewById(R.id.contentDiscountBreakdown)

        progressOrderType = findViewById(R.id.progressOrderType)
        cardOrderType = findViewById(R.id.cardSalesByOrderType)
        valDineIn = findViewById(R.id.valDineIn)
        valDineInPct = findViewById(R.id.valDineInPct)
        rowDineIn = findViewById(R.id.rowDineIn)
        arrowDineIn = findViewById(R.id.arrowDineIn)
        contentDineInTables = findViewById(R.id.contentDineInTables)
        valToGo = findViewById(R.id.valToGo)
        valToGoPct = findViewById(R.id.valToGoPct)
        valBar = findViewById(R.id.valBar)
        valBarPct = findViewById(R.id.valBarPct)
        valOrderTypeTotal = findViewById(R.id.valOrderTypeTotal)

        progressCardBrand = findViewById(R.id.progressCardBrand)
        cardCardBrand = findViewById(R.id.cardCardBrand)
        contentCardBrand = findViewById(R.id.contentCardBrand)

        progressEmployee = findViewById(R.id.progressEmployee)
        cardEmployee = findViewById(R.id.cardEmployee)
        contentEmployee = findViewById(R.id.contentEmployee)

        progressHourlySales = findViewById(R.id.progressHourlySales)
        cardHourlySales = findViewById(R.id.cardHourlySales)
        contentHourlySales = findViewById(R.id.contentHourlySales)

        setupCollapsibleCard(
            header = findViewById(R.id.headerDailySummary),
            content = findViewById(R.id.contentDailySummary),
            arrow = findViewById(R.id.arrowDailySummary)
        )
        setupCollapsibleCard(
            header = findViewById(R.id.headerOrderType),
            content = findViewById(R.id.contentOrderType),
            arrow = findViewById(R.id.arrowOrderType)
        )
        setupCollapsibleCard(
            header = findViewById(R.id.headerCardBrand),
            content = contentCardBrand,
            arrow = findViewById(R.id.arrowCardBrand)
        )
        setupCollapsibleCard(
            header = findViewById(R.id.headerEmployee),
            content = contentEmployee,
            arrow = findViewById(R.id.arrowEmployee)
        )
        setupCollapsibleCard(
            header = findViewById(R.id.headerHourlySales),
            content = contentHourlySales,
            arrow = findViewById(R.id.arrowHourlySales)
        )

        loadAllReports()
    }

    /** Converts local date to UTC millis for MaterialDatePicker (expects UTC midnight). */
    private fun localDateToUtcMillis(localDate: Date): Long {
        val localCal = Calendar.getInstance().apply { time = localDate }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(localCal.get(Calendar.YEAR), localCal.get(Calendar.MONTH), localCal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Converts UTC midnight from MaterialDatePicker to local midnight for the same calendar date. */
    private fun utcMillisToLocalDate(utcMillis: Long): Date {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcMillis
        }
        return Calendar.getInstance().apply {
            set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun updateDateRangeDisplay() {
        findViewById<TextView>(R.id.txtReportDateFrom).text = dateFmt.format(selectedStartDate)
        findViewById<TextView>(R.id.txtReportDateTo).text = dateFmt.format(selectedEndDate)
    }

    private fun showDateRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select date range")
            .setSelection(androidx.core.util.Pair(
                localDateToUtcMillis(selectedStartDate),
                localDateToUtcMillis(selectedEndDate)
            ))
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            if (selection != null) {
                // MaterialDatePicker returns UTC midnight; convert to local date to avoid off-by-one
                selectedStartDate = utcMillisToLocalDate(selection.first)
                selectedEndDate = utcMillisToLocalDate(selection.second)
                if (selectedStartDate.after(selectedEndDate)) {
                    val tmp = selectedStartDate
                    selectedStartDate = selectedEndDate
                    selectedEndDate = tmp
                }
                updateDateRangeDisplay()
                loadAllReports()
            }
        }
        picker.show(supportFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun loadAllReports() {
        loadDailySummary()
        loadSalesByOrderType()
        loadHourlySalesByOrderType()
        loadSalesByCardBrand()
        loadEmployeeReport()
        loadMenuPerformanceReports()
    }

    private fun loadEmployeeNames() {
        db.collection("Employees").get()
            .addOnSuccessListener { snap ->
                val names = snap.mapNotNull { it.getString("name")?.takeIf { n -> n.isNotBlank() } }
                    .distinct().sorted()
                runOnUiThread {
                    employeeNames = names
                    val options = listOf("All Employees") + names
                    spinnerEmployeeFilter.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, options
                    )
                    spinnerEmployeeFilter.setSelection(0, false)
                    spinnerEmployeeFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            if (!spinnerInitialised) { spinnerInitialised = true; return }
                            val newFilter = if (pos == 0) null else employeeNames.getOrNull(pos - 1)
                            if (newFilter != globalEmployeeFilter) {
                                globalEmployeeFilter = newFilter
                                loadAllReports()
                            }
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                    spinnerInitialised = true
                }
            }
    }

    private fun setupCollapsibleCard(
        header: LinearLayout,
        content: View,
        arrow: TextView
    ) {
        header.setOnClickListener {
            val expanding = content.visibility == View.GONE
            content.visibility = if (expanding) View.VISIBLE else View.GONE
            arrow.text = if (expanding) "▼" else "▶"
        }
    }

    // =========================================================
    // DAILY SALES SUMMARY
    // =========================================================

    private fun loadDailySummary() {
        progressBar.visibility = View.VISIBLE
        card.visibility = View.GONE

        reportEngine.getDailySalesSummary(selectedStartDate, selectedEndDate, globalEmployeeFilter,
            onSuccess = { summary ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    card.visibility = View.VISIBLE

                    valGross.text = currencyFmt.format(summary.grossSales)
                    valDiscounts.text = if (summary.discounts > 0)
                        "−${currencyFmt.format(summary.discounts)}" else currencyFmt.format(0.0)
                    valTax.text = currencyFmt.format(summary.taxCollected)
                    valTips.text = currencyFmt.format(summary.tipsCollected)
                    valNet.text = currencyFmt.format(summary.netSales)
                    valTxCount.text = summary.totalTransactions.toString()
                    valAvg.text = currencyFmt.format(summary.averageTicket)
                    valItemsSold.text = summary.itemsSold.toString()
                    valRefunds.text = currencyFmt.format(summary.refunds)
                    valVoidedItems.text = summary.voidedItems.toString()
                    valCash.text = currencyFmt.format(summary.cashPayments)
                    valCredit.text = currencyFmt.format(summary.creditPayments)
                    valDebit.text = currencyFmt.format(summary.debitPayments)

                    bindTaxBreakdown(summary.taxesByOrderType, summary.taxesByTaxName)
                    bindDiscountBreakdown(summary.discountBreakdown)
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed to load report: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun bindTaxBreakdown(
        byOrderType: List<TaxByOrderType>,
        byTaxName: List<TaxByTaxName>
    ) {
        contentTaxBreakdown.removeAllViews()
        val hasData = byOrderType.isNotEmpty() || byTaxName.isNotEmpty()
        arrowTax.visibility = if (hasData) View.VISIBLE else View.GONE
        contentTaxBreakdown.visibility = View.GONE
        arrowTax.text = "▶"

        if (!hasData) {
            rowTaxCollected.setOnClickListener(null)
            rowTaxCollected.isClickable = false
            return
        }

        rowTaxCollected.setOnClickListener {
            val expanding = contentTaxBreakdown.visibility == View.GONE
            contentTaxBreakdown.visibility = if (expanding) View.VISIBLE else View.GONE
            arrowTax.text = if (expanding) "▼" else "▶"
        }

        val activeByOrderType = byOrderType.filter { it.taxCents > 0L }
        if (activeByOrderType.isNotEmpty()) {
            contentTaxBreakdown.addView(makeTaxSectionCard("Taxes by Order Type") { container ->
                for (item in activeByOrderType) {
                    container.addView(
                        makeTaxBreakdownRow(
                            orderTypeLabel(item.orderType),
                            currencyFmt.format(item.taxCents / 100.0)
                        )
                    )
                }
                container.addView(makeDivider())
                val total = activeByOrderType.sumOf { it.taxCents }
                container.addView(makeTotalRow("Total", currencyFmt.format(total / 100.0)))
            })
        }

        val activeByTaxName = byTaxName.filter { it.taxCents > 0L }
        if (activeByTaxName.isNotEmpty()) {
            contentTaxBreakdown.addView(makeTaxSectionCard("Taxes by Tax Type") { container ->
                for (item in activeByTaxName) {
                    container.addView(
                        makeTaxBreakdownRow(
                            item.taxName,
                            currencyFmt.format(item.taxCents / 100.0)
                        )
                    )
                }
                container.addView(makeDivider())
                val total = activeByTaxName.sumOf { it.taxCents }
                container.addView(makeTotalRow("Total", currencyFmt.format(total / 100.0)))
            })
        }
    }

    private fun orderTypeLabel(raw: String): String = when (raw) {
        "DINE_IN" -> "Dine-In Tax"
        "TO_GO" -> "To-Go Tax"
        "BAR", "BAR_TAB" -> "Bar Tax"
        else -> "$raw Tax"
    }

    private fun orderTypeLabelSimple(raw: String): String = when (raw) {
        "DINE_IN" -> "Dine-In"
        "TO_GO" -> "To-Go"
        "BAR" -> "Bar"
        "BAR_TAB" -> "Bar Tab"
        else -> raw.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun bindDiscountBreakdown(breakdown: DiscountBreakdown) {
        contentDiscountBreakdown.removeAllViews()
        val hasData = breakdown.byOrderType.isNotEmpty() || breakdown.byName.isNotEmpty()
        arrowDiscount.visibility = if (hasData) View.VISIBLE else View.GONE
        contentDiscountBreakdown.visibility = View.GONE
        arrowDiscount.text = "▶"

        if (!hasData) {
            rowDiscounts.setOnClickListener(null)
            rowDiscounts.isClickable = false
            return
        }

        rowDiscounts.setOnClickListener {
            val expanding = contentDiscountBreakdown.visibility == View.GONE
            contentDiscountBreakdown.visibility = if (expanding) View.VISIBLE else View.GONE
            arrowDiscount.text = if (expanding) "▼" else "▶"
        }

        if (breakdown.byOrderType.isNotEmpty()) {
            contentDiscountBreakdown.addView(makeTaxSectionCard("Discounts by Order Type") { container ->
                for (item in breakdown.byOrderType) {
                    container.addView(
                        makeTaxBreakdownRow(
                            "${orderTypeLabelSimple(item.orderType)} (${item.orderCount} orders)",
                            "−${currencyFmt.format(item.discountCents / 100.0)}"
                        )
                    )
                }
                container.addView(makeDivider())
                val total = breakdown.byOrderType.sumOf { it.discountCents }
                container.addView(makeTotalRow("Total", "−${currencyFmt.format(total / 100.0)}", "#E65100"))
            })
        }

        if (breakdown.byName.isNotEmpty()) {
            contentDiscountBreakdown.addView(makeTaxSectionCard("Most Used Discounts") { container ->
                for (item in breakdown.byName) {
                    container.addView(
                        makeTaxBreakdownRow(
                            "${item.discountName} (${item.timesUsed}x)",
                            "−${currencyFmt.format(item.discountCents / 100.0)}"
                        )
                    )
                }
            })
        }

        if (breakdown.byPaymentMethod.totalCents > 0L) {
            val pm = breakdown.byPaymentMethod
            contentDiscountBreakdown.addView(makeTaxSectionCard("Discounts by Payment Method") { container ->
                if (pm.cashDiscountCents > 0L) {
                    container.addView(
                        makeTaxBreakdownRow(
                            "Cash (${pm.cashOrderCount} orders)",
                            "−${currencyFmt.format(pm.cashDiscountCents / 100.0)}"
                        )
                    )
                }
                if (pm.cardDiscountCents > 0L) {
                    container.addView(
                        makeTaxBreakdownRow(
                            "Card (${pm.cardOrderCount} orders)",
                            "−${currencyFmt.format(pm.cardDiscountCents / 100.0)}"
                        )
                    )
                }
                container.addView(makeDivider())
                container.addView(makeTotalRow("Total", "−${currencyFmt.format(pm.totalCents / 100.0)}", "#E65100"))
            })
        }
    }

    private fun makeTaxSectionCard(
        title: String,
        builder: (LinearLayout) -> Unit
    ): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        wrapper.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#424242"))
            letterSpacing = 0.03f
        })

        builder(wrapper)
        return wrapper
    }

    private fun makeTaxBreakdownRow(label: String, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#616161"))
            })
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = value
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
                gravity = Gravity.END
            })
        }

    // =========================================================
    // SALES BY ORDER TYPE
    // =========================================================

    private fun loadSalesByOrderType() {
        progressOrderType.visibility = View.VISIBLE
        cardOrderType.visibility = View.GONE

        val (start, end) = reportEngine.dateRange(selectedStartDate, selectedEndDate)

        reportEngine.getSalesByOrderType(start, end, globalEmployeeFilter,
            onSuccess = { data ->
                runOnUiThread {
                    progressOrderType.visibility = View.GONE
                    cardOrderType.visibility = View.VISIBLE
                    bindOrderTypeCard(data)
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    progressOrderType.visibility = View.GONE
                    Toast.makeText(this, "Failed to load order type report: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun bindOrderTypeCard(data: SalesByOrderType) {
        valDineIn.text = currencyFmt.format(data.dineInCents / 100.0)
        valToGo.text = currencyFmt.format(data.toGoCents / 100.0)
        valBar.text = currencyFmt.format(data.barCents / 100.0)
        valOrderTypeTotal.text = currencyFmt.format(data.totalCents / 100.0)

        if (data.totalCents > 0L) {
            valDineInPct.text = "(${data.percentOf(data.dineInCents)}%)"
            valToGoPct.text = "(${data.percentOf(data.toGoCents)}%)"
            valBarPct.text = "(${data.percentOf(data.barCents)}%)"
        }

        // Dine-In expandable section/table breakdown
        contentDineInTables.removeAllViews()
        val hasSections = data.dineInBySection.isNotEmpty()
        arrowDineIn.visibility = if (hasSections) View.VISIBLE else View.GONE
        contentDineInTables.visibility = View.GONE
        arrowDineIn.text = "▶"

        if (hasSections) {
            for (section in data.dineInBySection) {
                contentDineInTables.addView(makeDineInSectionHeader(section.sectionName))
                for (table in section.tables) {
                    contentDineInTables.addView(makeDineInTableRow(table))
                }
            }
            rowDineIn.setOnClickListener {
                val expanding = contentDineInTables.visibility == View.GONE
                contentDineInTables.visibility = if (expanding) View.VISIBLE else View.GONE
                arrowDineIn.text = if (expanding) "▼" else "▶"
            }
        } else {
            rowDineIn.setOnClickListener(null)
            rowDineIn.isClickable = false
        }
    }

    private fun makeDineInSectionHeader(sectionName: String): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        text = sectionName
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1565C0"))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun makeDineInTableRow(table: DineInTableSale): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), 0, dp(8))
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = table.tableName
                textSize = 14f
                setTextColor(Color.parseColor("#424242"))
            })
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = "${currencyFmt.format(table.totalCents / 100.0)} (${table.orderCount} orders)"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
                gravity = Gravity.END
            })
        }

    // =========================================================
    // HOURLY SALES BY ORDER TYPE
    // =========================================================

    private val orderTypeColors = mapOf(
        "DINE_IN" to "#1565C0",
        "TO_GO" to "#E65100",
        "BAR" to "#6A1B9A",
        "BAR_TAB" to "#6A1B9A"
    )

    private fun orderTypeDisplayName(raw: String): String = when (raw) {
        "DINE_IN" -> "Dine-In"
        "TO_GO" -> "To-Go"
        "BAR" -> "Bar"
        "BAR_TAB" -> "Bar Tab"
        else -> raw.replace("_", " ").lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    private fun hourLabel(hour: Int): String {
        return when (hour) {
            0 -> "12 AM"
            in 1..11 -> "$hour AM"
            12 -> "12 PM"
            else -> "${hour - 12} PM"
        }
    }

    private fun loadHourlySalesByOrderType() {
        progressHourlySales.visibility = View.VISIBLE
        cardHourlySales.visibility = View.GONE

        reportEngine.getHourlySalesByOrderType(selectedStartDate, selectedEndDate, globalEmployeeFilter,
            onSuccess = { data ->
                runOnUiThread {
                    progressHourlySales.visibility = View.GONE
                    cardHourlySales.visibility = View.VISIBLE
                    buildHourlySalesContent(data)
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    progressHourlySales.visibility = View.GONE
                    Toast.makeText(this, "Failed to load hourly sales: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun buildHourlySalesContent(data: List<HourlySale>) {
        contentHourlySales.removeAllViews()
        contentHourlySales.addView(makeDivider())

        if (data.isEmpty()) {
            contentHourlySales.addView(makeLabel("No sales data for this period", "#757575"))
            return
        }

        val maxCents = data.maxOf { it.totalCents }

        for (hourSale in data) {
            val arrow = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
                text = "▶"
                textSize = 11f
                setTextColor(Color.parseColor("#9E9E9E"))
            }

            val barFraction = if (maxCents > 0) (hourSale.totalCents.toFloat() / maxCents) else 0f

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.attr.selectableItemBackground.let {
                    val attrs = intArrayOf(it)
                    val ta = obtainStyledAttributes(attrs)
                    val res = ta.getResourceId(0, 0)
                    ta.recycle()
                    res
                })
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(4))
            }
            topRow.addView(arrow)
            topRow.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = hourLabel(hourSale.hour)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            })
            topRow.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
                text = "(${hourSale.totalOrders} orders)"
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
            })
            topRow.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = currencyFmt.format(hourSale.totalCents / 100.0)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#2E7D32"))
            })
            header.addView(topRow)

            val barContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply {
                    marginStart = dp(19); bottomMargin = dp(8)
                }
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#EEEEEE"))
                    cornerRadius = dp(3).toFloat()
                }
                background = bg
            }
            val barFill = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, barFraction).apply {}
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1565C0"))
                    cornerRadius = dp(3).toFloat()
                }
                background = bg
            }
            val barSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f - barFraction)
            }
            barContainer.addView(barFill)
            barContainer.addView(barSpacer)
            header.addView(barContainer)

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                visibility = View.GONE
                setPadding(dp(19), 0, 0, dp(4))
            }

            for (ot in hourSale.orderTypes) {
                val color = orderTypeColors[ot.orderType] ?: "#757575"
                val otRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                otRow.addView(makeDot(color))
                otRow.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    text = orderTypeDisplayName(ot.orderType)
                    textSize = 14f
                    setTextColor(Color.parseColor("#424242"))
                })
                otRow.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
                    text = "(${ot.orderCount} orders)"
                    textSize = 13f
                    setTextColor(Color.parseColor("#757575"))
                })
                otRow.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    text = currencyFmt.format(ot.totalCents / 100.0)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#212121"))
                })
                content.addView(otRow)
            }

            header.setOnClickListener {
                val expanding = content.visibility == View.GONE
                content.visibility = if (expanding) View.VISIBLE else View.GONE
                arrow.text = if (expanding) "▼" else "▶"
            }

            contentHourlySales.addView(header)
            contentHourlySales.addView(content)
        }

        contentHourlySales.addView(makeDivider())
        val grandTotalCents = data.sumOf { it.totalCents }
        val grandTotalOrders = data.sumOf { it.totalOrders }
        contentHourlySales.addView(makeTotalRow(
            "Total ($grandTotalOrders orders)",
            currencyFmt.format(grandTotalCents / 100.0)
        ))
    }

    // =========================================================
    // SALES BY CARD BRAND
    // =========================================================

    private val brandColors = mapOf(
        "Visa" to "#1A1F71",
        "Mastercard" to "#EB001B",
        "Amex" to "#006FCF",
        "Discover" to "#FF6000"
    )

    private fun loadSalesByCardBrand() {
        progressCardBrand.visibility = View.VISIBLE
        cardCardBrand.visibility = View.GONE

        reportEngine.getSalesByCardBrand(selectedStartDate, selectedEndDate, globalEmployeeFilter,
            onSuccess = { data ->
                runOnUiThread {
                    progressCardBrand.visibility = View.GONE
                    cardCardBrand.visibility = View.VISIBLE
                    buildCardBrandContent(data)
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    progressCardBrand.visibility = View.GONE
                    Toast.makeText(this, "Failed to load card brand report: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun buildCardBrandContent(data: List<CardBrandSale>) {
        contentCardBrand.removeAllViews()
        contentCardBrand.addView(makeDivider())

        if (data.isEmpty()) {
            contentCardBrand.addView(makeLabel("No card transactions for this period", "#757575"))
            return
        }

        val totalCents = data.sumOf { it.totalCents }

        for (sale in data) {
            val color = brandColors[sale.brand] ?: "#757575"
            val pct = if (totalCents > 0) ((sale.totalCents * 100.0) / totalCents).roundToInt() else 0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }

            row.addView(makeDot(color))

            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = sale.brand
                textSize = 15f
                setTextColor(Color.parseColor("#424242"))
            })

            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
                text = "($pct%)"
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
            })

            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = currencyFmt.format(sale.totalCents / 100.0)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            })

            contentCardBrand.addView(row)
        }

        contentCardBrand.addView(makeDivider())

        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
        }
        totalRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            text = "Total"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
        })
        totalRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            text = currencyFmt.format(totalCents / 100.0)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2E7D32"))
        })
        contentCardBrand.addView(totalRow)
    }

    // =========================================================
    // EMPLOYEE REPORT
    // =========================================================

    private fun loadEmployeeReport() {
        progressEmployee.visibility = View.VISIBLE
        cardEmployee.visibility = View.GONE
        selectedEmployeeFilter = null

        db.collection("Employees")
            .get()
            .addOnSuccessListener { empDocs ->
                val allNames = empDocs.mapNotNull { it.getString("name")?.takeIf { n -> n.isNotBlank() } }.distinct().sorted()
                reportEngine.getEmployeeReport(selectedStartDate, selectedEndDate, globalEmployeeFilter,
                    onSuccess = { reportData ->
                        runOnUiThread {
                            val reportMap = reportData.associateBy { it.employeeName }
                            val merged = mutableListOf<EmployeeReportData>()
                            for (name in allNames) {
                                merged.add(reportMap[name] ?: EmployeeReportData(employeeName = name))
                            }
                            for ((name, data) in reportMap) {
                                if (name !in allNames) merged.add(data)
                            }
                            allEmployeeData = merged.sortedByDescending { it.salesCents }
                            progressEmployee.visibility = View.GONE
                            cardEmployee.visibility = View.VISIBLE
                            buildEmployeeContent()
                        }
                    },
                    onFailure = { e ->
                        runOnUiThread {
                            progressEmployee.visibility = View.GONE
                            Toast.makeText(this, "Failed to load employee report: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    progressEmployee.visibility = View.GONE
                    Toast.makeText(this, "Failed to load employees: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun buildEmployeeContent() {
        val data = if (selectedEmployeeFilter == null) allEmployeeData
            else allEmployeeData.filter { it.employeeName == selectedEmployeeFilter }
        contentEmployee.removeAllViews()

        if (allEmployeeData.isNotEmpty()) {
            val filterRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(16))
            }
            filterRow.addView(TextView(this).apply {
                text = "Filter:"
                textSize = 14f
                setTextColor(Color.parseColor("#757575"))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) }
            })
            val spinner = Spinner(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                val options = listOf("All employees") + allEmployeeData.map { it.employeeName }
                adapter = ArrayAdapter(this@ReportsActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(if (selectedEmployeeFilter == null) 0 else options.indexOf(selectedEmployeeFilter).coerceAtLeast(0))
                setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val newFilter = if (pos == 0) null else allEmployeeData.getOrNull(pos - 1)?.employeeName
                        if (newFilter != selectedEmployeeFilter) {
                            selectedEmployeeFilter = newFilter
                            buildEmployeeContent()
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                })
            }
            filterRow.addView(spinner)
            contentEmployee.addView(filterRow)
        }

        contentEmployee.addView(makeDivider())

        if (data.isEmpty()) {
            contentEmployee.addView(makeLabel("No employee data for this period", "#757575"))
            return
        }

        addSubSection(contentEmployee, "General", expanded = true) { container ->
            for (emp in data) {
                container.addView(makeEmployeeHeader(emp.employeeName))
                container.addView(makeRow("Sales", "${currencyFmt.format(emp.salesCents / 100.0)}  (${emp.orderCount} orders)"))
                container.addView(makeRow("Tips", currencyFmt.format(emp.tipsCents / 100.0)))
                container.addView(makeRow("Refunds", currencyFmt.format(emp.refundsCents / 100.0), "#C62828"))
                container.addView(makeRow("Voids", emp.voidsCount.toString(), "#C62828"))
                container.addView(makeSpacing(8))
            }
        }

        addSubSection(contentEmployee, "Payments") { container ->
            var payCashCents = 0L
            var payCashTxCount = 0
            var payCardCents = 0L
            var payCardTxCount = 0
            for (emp in data) {
                payCashCents += emp.paymentsByMethod.cashCents
                payCashTxCount += emp.paymentsByMethod.cashTxCount
                payCardCents += emp.paymentsByMethod.cardCents
                payCardTxCount += emp.paymentsByMethod.cardTxCount
            }
            val pay = PaymentMethodBreakdown(payCashCents, payCashTxCount, payCardCents, payCardTxCount)
            if (pay.totalCents == 0L) {
                container.addView(makeLabel("No payments recorded", "#757575"))
                return@addSubSection
            }
            container.addView(makePaymentMethodRow("Cash", pay.cashCents, pay.cashTxCount))
            container.addView(makePaymentMethodRow("Card", pay.cardCents, pay.cardTxCount))
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total", "${currencyFmt.format(pay.totalCents / 100.0)}  (${pay.totalTxCount})"))
        }

        addSubSection(contentEmployee, "Tips") { container ->
            var tipCashCents = 0L
            var tipCardCents = 0L
            for (emp in data) {
                tipCashCents += emp.tipsByMethod.cashCents
                tipCardCents += emp.tipsByMethod.cardCents
            }
            val tips = PaymentMethodBreakdown(cashCents = tipCashCents, cardCents = tipCardCents)
            if (tips.totalCents == 0L) {
                container.addView(makeLabel("No tips recorded", "#757575"))
                return@addSubSection
            }
            container.addView(makeRightAlignedRow("Cash Tips", currencyFmt.format(tips.cashCents / 100.0)))
            container.addView(makeRightAlignedRow("Card Tips", currencyFmt.format(tips.cardCents / 100.0)))
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total Tips", currencyFmt.format(tips.totalCents / 100.0)))
        }

        addSubSection(contentEmployee, "Refunds") { container ->
            var refundCashCents = 0L
            var refundCardCents = 0L
            for (emp in data) {
                refundCashCents += emp.refundsByMethod.cashCents
                refundCardCents += emp.refundsByMethod.cardCents
            }
            val refunds = PaymentMethodBreakdown(cashCents = refundCashCents, cardCents = refundCardCents)
            if (refunds.totalCents == 0L) {
                container.addView(makeLabel("No refunds recorded", "#757575"))
                return@addSubSection
            }
            container.addView(makeRightAlignedRow("Cash Refunds", currencyFmt.format(refunds.cashCents / 100.0), "#C62828"))
            container.addView(makeRightAlignedRow("Card Refunds", currencyFmt.format(refunds.cardCents / 100.0), "#C62828"))
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total Refunds", currencyFmt.format(refunds.totalCents / 100.0), "#C62828"))
        }

        addSubSection(contentEmployee, "Voids") { container ->
            var total = 0
            val withVoids = data.filter { it.voidsCount > 0 }
            if (withVoids.isEmpty()) {
                container.addView(makeLabel("No voids recorded", "#757575"))
                return@addSubSection
            }
            for (emp in withVoids) {
                container.addView(makeRow(emp.employeeName, emp.voidsCount.toString(), "#C62828"))
                total += emp.voidsCount
            }
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total", total.toString(), "#C62828"))
        }

        addSubSection(contentEmployee, "Discounts") { container ->
            val merged = DiscountBreakdown(
                byOrderType = data.flatMap { it.discountBreakdown.byOrderType }
                    .groupBy { it.orderType }
                    .map { (ot, items) ->
                        com.ernesto.myapplication.engine.DiscountByOrderType(ot, items.sumOf { it.discountCents }, items.sumOf { it.orderCount })
                    }.sortedByDescending { it.discountCents },
                byName = data.flatMap { it.discountBreakdown.byName }
                    .groupBy { it.discountName }
                    .map { (name, items) ->
                        com.ernesto.myapplication.engine.DiscountByName(name, items.sumOf { it.discountCents }, items.sumOf { it.timesUsed })
                    }.sortedByDescending { it.timesUsed },
                byPaymentMethod = com.ernesto.myapplication.engine.DiscountByPaymentMethod(
                    cashDiscountCents = data.sumOf { it.discountBreakdown.byPaymentMethod.cashDiscountCents },
                    cashOrderCount = data.sumOf { it.discountBreakdown.byPaymentMethod.cashOrderCount },
                    cardDiscountCents = data.sumOf { it.discountBreakdown.byPaymentMethod.cardDiscountCents },
                    cardOrderCount = data.sumOf { it.discountBreakdown.byPaymentMethod.cardOrderCount }
                )
            )
            val totalDiscCents = merged.byOrderType.sumOf { it.discountCents }
            if (totalDiscCents == 0L) {
                container.addView(makeLabel("No discounts applied", "#757575"))
                return@addSubSection
            }

            if (merged.byOrderType.isNotEmpty()) {
                container.addView(makeEmployeeHeader("By Order Type"))
                for (item in merged.byOrderType) {
                    container.addView(makeRightAlignedRow(
                        "${orderTypeLabelSimple(item.orderType)} (${item.orderCount} orders)",
                        "−${currencyFmt.format(item.discountCents / 100.0)}",
                        "#E65100"
                    ))
                }
            }

            if (merged.byName.isNotEmpty()) {
                container.addView(makeSpacing(4))
                container.addView(makeEmployeeHeader("Most Used"))
                for (item in merged.byName) {
                    container.addView(makeRightAlignedRow(
                        "${item.discountName} (${item.timesUsed}x)",
                        "−${currencyFmt.format(item.discountCents / 100.0)}",
                        "#E65100"
                    ))
                }
            }

            if (merged.byPaymentMethod.totalCents > 0L) {
                val pm = merged.byPaymentMethod
                container.addView(makeSpacing(4))
                container.addView(makeEmployeeHeader("By Payment Method"))
                if (pm.cashDiscountCents > 0L) {
                    container.addView(makeRightAlignedRow(
                        "Cash (${pm.cashOrderCount} orders)",
                        "−${currencyFmt.format(pm.cashDiscountCents / 100.0)}",
                        "#E65100"
                    ))
                }
                if (pm.cardDiscountCents > 0L) {
                    container.addView(makeRightAlignedRow(
                        "Card (${pm.cardOrderCount} orders)",
                        "−${currencyFmt.format(pm.cardDiscountCents / 100.0)}",
                        "#E65100"
                    ))
                }
            }

            container.addView(makeDivider())
            container.addView(makeTotalRow("Total Discounts", "−${currencyFmt.format(totalDiscCents / 100.0)}", "#E65100"))
        }
    }

    /**
     * Adds a collapsible sub-section inside a parent container.
     * [builder] populates the section's content area.
     */
    private fun addSubSection(
        parent: LinearLayout,
        title: String,
        expanded: Boolean = false,
        builder: (LinearLayout) -> Unit
    ) {
        val arrow = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
            text = if (expanded) "▼" else "▶"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.attr.selectableItemBackground.let {
                val attrs = intArrayOf(it)
                val ta = obtainStyledAttributes(attrs)
                val res = ta.getResourceId(0, 0)
                ta.recycle()
                res
            })
        }

        header.addView(arrow)
        header.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#424242"))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(dp(4), 0, 0, dp(4))
        }

        builder(content)

        header.setOnClickListener {
            val expanding = content.visibility == View.GONE
            content.visibility = if (expanding) View.VISIBLE else View.GONE
            arrow.text = if (expanding) "▼" else "▶"
        }

        parent.addView(header)
        parent.addView(content)
    }

    // =========================================================
    // VIEW BUILDER HELPERS
    // =========================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        }
        setBackgroundColor(Color.parseColor("#E0E0E0"))
    }

    private fun makeDot(colorHex: String): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun makeLabel(text: String, colorHex: String): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setPadding(0, dp(12), 0, dp(12))
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor(colorHex))
    }

    private fun makeRow(label: String, value: String, valueColor: String = "#212121"): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#424242"))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = value
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(valueColor))
                gravity = Gravity.END
            })
        }
    }

    private fun makeRightAlignedRow(label: String, value: String, valueColor: String = "#212121"): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#424242"))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    gravity = Gravity.END
                }
                text = value
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(valueColor))
            })
        }
    }

    private fun makePaymentMethodRow(label: String, cents: Long, txCount: Int): LinearLayout {
        val value = "${currencyFmt.format(cents / 100.0)} ($txCount)"
        return makeRightAlignedRow(label, value)
    }

    private fun makeTotalRow(label: String, value: String, valueColor: String = "#2E7D32"): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = label
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = value
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(valueColor))
            })
        }
    }

    private fun makeEmployeeHeader(name: String): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        text = name
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1565C0"))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun makeSpacing(dpValue: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(dpValue))
    }

    // =========================================================
    // MENU PERFORMANCE (inline sub-reports)
    // =========================================================

    private var itemSalesData: List<ItemSalesRow> = emptyList()
    private var categorySalesData: List<CategorySalesRow> = emptyList()
    private var modifierSalesData: List<ModifierSalesRow> = emptyList()

    private fun loadMenuPerformanceReports() {
        progressMenuPerf.visibility = View.VISIBLE
        containerMenuPerf.visibility = View.GONE

        itemSalesData = emptyList()
        categorySalesData = emptyList()
        modifierSalesData = emptyList()

        var completed = 0
        val lock = Any()

        fun checkAllDone() {
            synchronized(lock) {
                completed++
                if (completed == 3) {
                    runOnUiThread {
                        progressMenuPerf.visibility = View.GONE
                        containerMenuPerf.visibility = View.VISIBLE
                        buildMenuPerformanceContent()
                    }
                }
            }
        }

        menuPerfEngine.getItemSalesReport(selectedStartDate, selectedEndDate, globalEmployeeFilter,
            onSuccess = { itemSalesData = it; checkAllDone() },
            onFailure = { checkAllDone() }
        )
        menuPerfEngine.getCategorySalesReport(selectedStartDate, selectedEndDate, globalEmployeeFilter,
            onSuccess = { categorySalesData = it; checkAllDone() },
            onFailure = { checkAllDone() }
        )
        menuPerfEngine.getModifierSalesReport(selectedStartDate, selectedEndDate, globalEmployeeFilter,
            onSuccess = { modifierSalesData = it; checkAllDone() },
            onFailure = { checkAllDone() }
        )
    }

    private fun buildMenuPerformanceContent() {
        containerMenuPerf.removeAllViews()

        addSubSection(containerMenuPerf, "Item Sales Report") { container ->
            if (itemSalesData.isEmpty()) {
                container.addView(makeLabel("No item sales for this period", "#757575"))
                return@addSubSection
            }
            val totalRevenue = itemSalesData.sumOf { it.totalRevenueCents }
            val totalQty = itemSalesData.sumOf { it.quantitySold }
            for (row in itemSalesData) {
                container.addView(makeMenuPerfRow(
                    row.itemName,
                    "${row.quantitySold} sold",
                    currencyFmt.format(row.totalRevenueCents / 100.0)
                ))
            }
            container.addView(makeDivider())
            container.addView(makeMenuPerfTotalRow("Total", "$totalQty sold", currencyFmt.format(totalRevenue / 100.0)))
        }

        addSubSection(containerMenuPerf, "Category Sales Report") { container ->
            if (categorySalesData.isEmpty()) {
                container.addView(makeLabel("No category sales for this period", "#757575"))
                return@addSubSection
            }
            val totalRevenue = categorySalesData.sumOf { it.totalRevenueCents }
            for (row in categorySalesData) {
                container.addView(makeMenuPerfRow(row.categoryName, null, currencyFmt.format(row.totalRevenueCents / 100.0)))
            }
            container.addView(makeDivider())
            container.addView(makeMenuPerfTotalRow("Total", null, currencyFmt.format(totalRevenue / 100.0)))
        }

        addSubSection(containerMenuPerf, "Modifier Sales Report") { container ->
            if (modifierSalesData.isEmpty()) {
                container.addView(makeLabel("No modifier usage for this period", "#757575"))
                return@addSubSection
            }
            val totalCount = modifierSalesData.sumOf { it.usageCount }
            val grouped = modifierSalesData.groupBy { it.itemName }

            for ((itemName, modifiers) in grouped) {
                container.addView(makeEmployeeHeader(itemName))
                for (row in modifiers) {
                    val isRemove = row.action == "REMOVE"
                    val displayName = if (isRemove) "REMOVE ${row.modifierName}" else row.modifierName
                    val nameColor = if (isRemove) "#C62828" else "#424242"
                    val extraCost = if (row.totalExtraCents > 0)
                        "+${currencyFmt.format(row.totalExtraCents / 100.0)}" else null
                    container.addView(makeModifierRow(displayName, row.usageCount.toString(), nameColor, extraCost))
                }
            }
            container.addView(makeDivider())
            container.addView(makeMenuPerfTotalRow("Total", null, totalCount.toString()))
        }
    }

    private fun makeMenuPerfRow(name: String, middle: String?, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = name
                textSize = 15f
                setTextColor(Color.parseColor("#424242"))
            })
            if (middle != null) {
                addView(TextView(this@ReportsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(16) }
                    text = middle
                    textSize = 14f
                    setTextColor(Color.parseColor("#757575"))
                })
            }
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = value
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
                gravity = Gravity.END
            })
        }

    private fun makeModifierRow(name: String, count: String, nameColor: String, extraCost: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = name
                textSize = 15f
                setTextColor(Color.parseColor(nameColor))
                if (nameColor == "#C62828") setTypeface(typeface, Typeface.BOLD)
            })
            if (extraCost != null) {
                addView(TextView(this@ReportsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) }
                    text = extraCost
                    textSize = 13f
                    setTextColor(Color.parseColor("#2E7D32"))
                })
            }
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = count
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
                gravity = Gravity.END
            })
        }

    private fun makeMenuPerfTotalRow(label: String, middle: String?, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = label
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            })
            if (middle != null) {
                addView(TextView(this@ReportsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(16) }
                    text = middle
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#424242"))
                })
            }
            addView(TextView(this@ReportsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = value
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#2E7D32"))
                gravity = Gravity.END
            })
        }
}
