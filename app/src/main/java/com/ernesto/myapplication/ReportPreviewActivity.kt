package com.ernesto.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ernesto.myapplication.engine.MenuPerformanceEngine
import com.ernesto.myapplication.engine.ReportBuilder
import com.ernesto.myapplication.engine.ReportData
import com.ernesto.myapplication.engine.ReportEngine
import com.ernesto.myapplication.engine.ReportRow
import com.ernesto.myapplication.engine.ReportSection
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REPORT_TYPE = "reportType"
        const val EXTRA_START_DATE = "startDate"
        const val EXTRA_END_DATE = "endDate"
        const val EXTRA_EMPLOYEE_FILTER = "employeeFilter"

        const val TYPE_DAILY_SUMMARY = "daily_summary"
        const val TYPE_ORDER_TYPE = "order_type"
        const val TYPE_HOURLY_SALES = "hourly_sales"
        const val TYPE_CARD_BRAND = "card_brand"
        const val TYPE_EMPLOYEE = "employee"
        const val TYPE_MENU_PERFORMANCE = "menu_performance"

        private const val REQUEST_BT_CONNECT = 1001
    }

    private val db = FirebaseFirestore.getInstance()
    private val reportEngine = ReportEngine(db)
    private val menuPerfEngine = MenuPerformanceEngine(db)
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private lateinit var txtTitle: TextView
    private lateinit var txtDateRange: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtError: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var reportContainer: LinearLayout
    private lateinit var bottomBar: View
    private lateinit var btnPrint: MaterialButton

    private var reportData: ReportData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_preview)
        supportActionBar?.hide()

        txtTitle = findViewById(R.id.txtReportTitle)
        txtDateRange = findViewById(R.id.txtReportDateRange)
        progressBar = findViewById(R.id.progressBar)
        txtError = findViewById(R.id.txtError)
        scrollContent = findViewById(R.id.scrollContent)
        reportContainer = findViewById(R.id.reportContainer)
        bottomBar = findViewById(R.id.bottomBar)
        btnPrint = findViewById(R.id.btnPrint)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnPrint.setOnClickListener { handlePrint() }
        findViewById<MaterialButton>(R.id.btnEmail).setOnClickListener {
            Toast.makeText(this, "Email feature coming soon", Toast.LENGTH_SHORT).show()
        }

        val reportType = intent.getStringExtra(EXTRA_REPORT_TYPE) ?: ""
        val startDate = Date(intent.getLongExtra(EXTRA_START_DATE, System.currentTimeMillis()))
        val endDate = Date(intent.getLongExtra(EXTRA_END_DATE, System.currentTimeMillis()))
        val employeeFilter = intent.getStringExtra(EXTRA_EMPLOYEE_FILTER)

        val dateStr = if (dateFmt.format(startDate) == dateFmt.format(endDate)) {
            dateFmt.format(startDate)
        } else {
            "${dateFmt.format(startDate)}  –  ${dateFmt.format(endDate)}"
        }
        val filterSuffix = if (!employeeFilter.isNullOrBlank()) "  •  $employeeFilter" else ""
        txtDateRange.text = "$dateStr$filterSuffix"

        loadReport(reportType, startDate, endDate, employeeFilter)
    }

    private fun loadReport(type: String, startDate: Date, endDate: Date, employee: String?) {
        showLoading()
        txtTitle.text = titleForType(type)

        when (type) {
            TYPE_DAILY_SUMMARY -> loadDailySummary(startDate, endDate, employee)
            TYPE_ORDER_TYPE -> loadOrderType(startDate, endDate, employee)
            TYPE_HOURLY_SALES -> loadHourlySales(startDate, endDate, employee)
            TYPE_CARD_BRAND -> loadCardBrand(startDate, endDate, employee)
            TYPE_EMPLOYEE -> loadEmployeeReport(startDate, endDate, employee)
            TYPE_MENU_PERFORMANCE -> loadMenuPerformance(startDate, endDate, employee)
            else -> showError("Unknown report type")
        }
    }

    private fun titleForType(type: String): String = when (type) {
        TYPE_DAILY_SUMMARY -> "Daily Sales Summary"
        TYPE_ORDER_TYPE -> "Sales by Order Type"
        TYPE_HOURLY_SALES -> "Hourly Sales by Order Type"
        TYPE_CARD_BRAND -> "Sales by Card Brand"
        TYPE_EMPLOYEE -> "Employee Report"
        TYPE_MENU_PERFORMANCE -> "Menu Performance"
        else -> "Report"
    }

    // ── Data Loaders ─────────────────────────────────────────────

    private fun loadDailySummary(start: Date, end: Date, employee: String?) {
        reportEngine.getDailySalesSummary(start, end, employee,
            onSuccess = { summary ->
                val data = ReportBuilder.fromDailySalesSummary(summary)
                runOnUiThread { displayReport(data) }
            },
            onFailure = { e -> runOnUiThread { showError(e.message ?: "Load failed") } }
        )
    }

    private fun loadOrderType(start: Date, end: Date, employee: String?) {
        val (s, e) = reportEngine.dateRange(start, end)
        reportEngine.getSalesByOrderType(s, e, employee,
            onSuccess = { data ->
                val rd = ReportBuilder.fromSalesByOrderType(data)
                runOnUiThread { displayReport(rd) }
            },
            onFailure = { ex -> runOnUiThread { showError(ex.message ?: "Load failed") } }
        )
    }

    private fun loadHourlySales(start: Date, end: Date, employee: String?) {
        reportEngine.getHourlySalesByOrderType(start, end, employee,
            onSuccess = { data ->
                val rd = ReportBuilder.fromHourlySales(data)
                runOnUiThread { displayReport(rd) }
            },
            onFailure = { ex -> runOnUiThread { showError(ex.message ?: "Load failed") } }
        )
    }

    private fun loadCardBrand(start: Date, end: Date, employee: String?) {
        reportEngine.getSalesByCardBrand(start, end, employee,
            onSuccess = { data ->
                val rd = ReportBuilder.fromCardBrandSales(data)
                runOnUiThread { displayReport(rd) }
            },
            onFailure = { ex -> runOnUiThread { showError(ex.message ?: "Load failed") } }
        )
    }

    private fun loadEmployeeReport(start: Date, end: Date, employee: String?) {
        reportEngine.getEmployeeReport(start, end, employee,
            onSuccess = { data ->
                val rd = ReportBuilder.fromEmployeeReport(data)
                runOnUiThread { displayReport(rd) }
            },
            onFailure = { ex -> runOnUiThread { showError(ex.message ?: "Load failed") } }
        )
    }

    private fun loadMenuPerformance(start: Date, end: Date, employee: String?) {
        var items = emptyList<com.ernesto.myapplication.engine.ItemSalesRow>()
        var categories = emptyList<com.ernesto.myapplication.engine.CategorySalesRow>()
        var modifiers = emptyList<com.ernesto.myapplication.engine.ModifierSalesRow>()
        var completed = 0
        val lock = Any()

        fun checkDone() {
            synchronized(lock) {
                completed++
                if (completed == 3) {
                    val rd = ReportBuilder.fromMenuPerformance(items, categories, modifiers)
                    runOnUiThread { displayReport(rd) }
                }
            }
        }

        menuPerfEngine.getItemSalesReport(start, end, employee,
            onSuccess = { items = it; checkDone() },
            onFailure = { checkDone() }
        )
        menuPerfEngine.getCategorySalesReport(start, end, employee,
            onSuccess = { categories = it; checkDone() },
            onFailure = { checkDone() }
        )
        menuPerfEngine.getModifierSalesReport(start, end, employee,
            onSuccess = { modifiers = it; checkDone() },
            onFailure = { checkDone() }
        )
    }

    // ── UI State ─────────────────────────────────────────────────

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE
        bottomBar.visibility = View.GONE
        txtError.visibility = View.GONE
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        scrollContent.visibility = View.GONE
        bottomBar.visibility = View.GONE
        txtError.visibility = View.VISIBLE
        txtError.text = message
    }

    private fun displayReport(data: ReportData) {
        reportData = data
        progressBar.visibility = View.GONE
        txtError.visibility = View.GONE
        scrollContent.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE

        reportContainer.removeAllViews()

        if (data.sections.isEmpty()) {
            reportContainer.addView(TextView(this).apply {
                text = "No data available for this period."
                textSize = 15f
                setTextColor(Color.parseColor("#757575"))
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, dp(48))
            })
            return
        }

        for ((index, section) in data.sections.withIndex()) {
            if (index > 0) {
                reportContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(12)
                    )
                })
            }
            reportContainer.addView(buildSectionCard(section))
        }
    }

    // ── Section Card Builder ─────────────────────────────────────

    private fun buildSectionCard(section: ReportSection): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        container.addView(TextView(this).apply {
            text = section.title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, dp(12))
        })

        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        for (row in section.rows) {
            container.addView(buildRow(row))
        }

        card.addView(container)
        return card
    }

    private fun buildRow(row: ReportRow): View {
        if (row.isDivider) {
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { topMargin = dp(8); bottomMargin = dp(8) }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
        }

        if (row.isSectionHeader) {
            return TextView(this).apply {
                text = row.label
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#424242"))
                letterSpacing = 0.03f
                setPadding(dp(row.indent * 12), dp(14), 0, dp(4))
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(row.indent * 12), dp(10), 0, dp(10))
        }

        if (row.isTotal) {
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                cornerRadius = dp(6).toFloat()
            }
            layout.background = bg
            layout.setPadding(dp(row.indent * 12 + 8), dp(10), dp(8), dp(10))
        }

        layout.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = row.label
            textSize = 15f
            if (row.isBold || row.isTotal) setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(row.labelColor ?: if (row.isTotal) "#212121" else "#424242"))
        })

        if (row.value.isNotBlank()) {
            layout.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = row.value
                textSize = 15f
                if (row.isBold || row.isTotal) setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(row.valueColor ?: if (row.isTotal) "#2E7D32" else "#212121"))
                gravity = Gravity.END
            })
        }

        return layout
    }

    // ── Printing ─────────────────────────────────────────────────

    private fun handlePrint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_CONNECT
                )
                return
            }
        }
        executePrint()
    }

    private fun executePrint() {
        val data = reportData ?: return
        Toast.makeText(this, "Printing report…", Toast.LENGTH_SHORT).show()
        val segments = buildPrintSegments(data)
        EscPosPrinter.print(this, segments)
    }

    /**
     * Replace unicode characters that ESC/POS printers cannot render.
     * The multi-byte UTF-8 encoding also breaks formatLine padding calculations.
     */
    private fun sanitize(text: String): String = text
        .replace("\u2212", "-")   // MINUS SIGN → hyphen
        .replace("\u2013", "-")   // EN DASH
        .replace("\u2014", "-")   // EM DASH
        .replace("\u2018", "'")   // LEFT SINGLE QUOTE
        .replace("\u2019", "'")   // RIGHT SINGLE QUOTE
        .replace("\u201C", "\"")  // LEFT DOUBLE QUOTE
        .replace("\u201D", "\"")  // RIGHT DOUBLE QUOTE
        .replace("\u2022", "*")   // BULLET
        .replace("\u2026", "...") // ELLIPSIS

    private fun buildPrintSegments(data: ReportData): List<EscPosPrinter.Segment> {
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lw = LINE_WIDTH

        segs += EscPosPrinter.Segment(sanitize(data.title), bold = true, fontSize = 1, centered = true)
        val dateRangeText = sanitize(txtDateRange.text.toString())
        if (dateRangeText.isNotBlank()) {
            segs += EscPosPrinter.Segment(dateRangeText, centered = true)
        }
        segs += EscPosPrinter.Segment("")

        for (section in data.sections) {
            segs += EscPosPrinter.Segment("=".repeat(lw))
            segs += EscPosPrinter.Segment(sanitize(section.title), bold = true, fontSize = 1, centered = true)
            segs += EscPosPrinter.Segment("-".repeat(lw))

            for (row in section.rows) {
                val label = sanitize(row.label)
                val value = sanitize(row.value)
                val bold = row.isBold || row.isTotal

                when {
                    row.isDivider -> segs += EscPosPrinter.Segment("-".repeat(lw))
                    row.isSectionHeader -> segs += EscPosPrinter.Segment(label, bold = true)
                    value.isBlank() -> {
                        val indent = "  ".repeat(row.indent)
                        segs += EscPosPrinter.Segment("$indent$label", bold = bold)
                    }
                    else -> {
                        val indent = "  ".repeat(row.indent)
                        val left = "$indent$label"
                        if (left.length + value.length + 1 > lw) {
                            segs += EscPosPrinter.Segment(left, bold = bold)
                            segs += EscPosPrinter.Segment(formatLine("", value, lw), bold = bold)
                        } else {
                            segs += EscPosPrinter.Segment(formatLine(left, value, lw), bold = bold)
                        }
                    }
                }
            }
            segs += EscPosPrinter.Segment("")
        }

        segs += EscPosPrinter.Segment("=".repeat(lw))
        val printDate = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += EscPosPrinter.Segment("Printed: $printDate", centered = true)

        return segs
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_CONNECT && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            executePrint()
        }
    }

    // ── Utils ────────────────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
