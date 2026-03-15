package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.CardBrandSale
import com.ernesto.myapplication.engine.EmployeeMetrics
import com.ernesto.myapplication.engine.ReportEngine
import com.ernesto.myapplication.engine.SalesByOrderType
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ReportsActivity : AppCompatActivity() {

    private val reportEngine = ReportEngine(FirebaseFirestore.getInstance())
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

    private lateinit var progressOrderType: ProgressBar
    private lateinit var cardOrderType: MaterialCardView
    private lateinit var valDineIn: TextView
    private lateinit var valDineInPct: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)
        supportActionBar?.hide()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        findViewById<TextView>(R.id.txtReportDate).text = dateFmt.format(Date())

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

        progressOrderType = findViewById(R.id.progressOrderType)
        cardOrderType = findViewById(R.id.cardSalesByOrderType)
        valDineIn = findViewById(R.id.valDineIn)
        valDineInPct = findViewById(R.id.valDineInPct)
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

        loadDailySummary()
        loadSalesByOrderType()
        loadSalesByCardBrand()
        loadEmployeeReport()
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

        reportEngine.getDailySalesSummary(
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

    // =========================================================
    // SALES BY ORDER TYPE
    // =========================================================

    private fun loadSalesByOrderType() {
        progressOrderType.visibility = View.VISIBLE
        cardOrderType.visibility = View.GONE

        val (start, end) = reportEngine.dayRange()

        reportEngine.getSalesByOrderType(start, end,
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

        reportEngine.getSalesByCardBrand(
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
            contentCardBrand.addView(makeLabel("No card transactions today", "#757575"))
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

        reportEngine.getEmployeeReport(
            onSuccess = { data ->
                runOnUiThread {
                    progressEmployee.visibility = View.GONE
                    cardEmployee.visibility = View.VISIBLE
                    buildEmployeeContent(data)
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

    private fun buildEmployeeContent(data: List<EmployeeMetrics>) {
        contentEmployee.removeAllViews()
        contentEmployee.addView(makeDivider())

        if (data.isEmpty()) {
            contentEmployee.addView(makeLabel("No employee data today", "#757575"))
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

        addSubSection(contentEmployee, "Tips") { container ->
            var total = 0L
            val withTips = data.filter { it.tipsCents > 0L }
            if (withTips.isEmpty()) {
                container.addView(makeLabel("No tips recorded", "#757575"))
                return@addSubSection
            }
            for (emp in withTips) {
                container.addView(makeRow(emp.employeeName, currencyFmt.format(emp.tipsCents / 100.0)))
                total += emp.tipsCents
            }
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total", currencyFmt.format(total / 100.0)))
        }

        addSubSection(contentEmployee, "Payments") { container ->
            var total = 0L
            val withSales = data.filter { it.salesCents > 0L }
            if (withSales.isEmpty()) {
                container.addView(makeLabel("No payments recorded", "#757575"))
                return@addSubSection
            }
            for (emp in withSales) {
                container.addView(makeRow(emp.employeeName, "${currencyFmt.format(emp.salesCents / 100.0)}  (${emp.orderCount})"))
                total += emp.salesCents
            }
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total", currencyFmt.format(total / 100.0)))
        }

        addSubSection(contentEmployee, "Refunds") { container ->
            var total = 0L
            val withRefunds = data.filter { it.refundsCents > 0L }
            if (withRefunds.isEmpty()) {
                container.addView(makeLabel("No refunds recorded", "#757575"))
                return@addSubSection
            }
            for (emp in withRefunds) {
                container.addView(makeRow(emp.employeeName, currencyFmt.format(emp.refundsCents / 100.0), "#C62828"))
                total += emp.refundsCents
            }
            container.addView(makeDivider())
            container.addView(makeTotalRow("Total", currencyFmt.format(total / 100.0), "#C62828"))
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
            })
        }
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
}
