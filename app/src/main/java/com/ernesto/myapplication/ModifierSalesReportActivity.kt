package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.ernesto.myapplication.engine.MenuPerformanceEngine
import kotlin.math.roundToInt

class ModifierSalesReportActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val engine = MenuPerformanceEngine(db)
    private val currencyFmt = NumberFormat.getCurrencyInstance()
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private var selectedDate: Date = Date()

    private lateinit var progressBar: ProgressBar
    private lateinit var cardContent: MaterialCardView
    private lateinit var contentReport: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modifier_sales_report)
        supportActionBar?.hide()

        progressBar = findViewById(R.id.progressBar)
        cardContent = findViewById(R.id.cardContent)
        contentReport = findViewById(R.id.contentReport)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowDateFilter).setOnClickListener { showDatePicker() }

        updateDateDisplay()
        loadReport()
    }

    private fun localDateToUtcMillis(d: Date): Long {
        val cal = Calendar.getInstance().apply { time = d }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun utcMillisToLocalDate(ms: Long): Date {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return Calendar.getInstance().apply {
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun updateDateDisplay() {
        findViewById<TextView>(R.id.txtReportDate).text = dateFmt.format(selectedDate)
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select date")
            .setSelection(localDateToUtcMillis(selectedDate))
            .build()
        picker.addOnPositiveButtonClickListener { ms ->
            selectedDate = utcMillisToLocalDate(ms)
            updateDateDisplay()
            loadReport()
        }
        picker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun loadReport() {
        progressBar.visibility = View.VISIBLE
        cardContent.visibility = View.GONE

        engine.getModifierSalesReport(
            startDate = selectedDate,
            endDate = selectedDate,
            onSuccess = { rows ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    cardContent.visibility = View.VISIBLE
                    buildContent(rows)
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun buildContent(rows: List<com.ernesto.myapplication.engine.ModifierSalesRow>) {
        contentReport.removeAllViews()

        if (rows.isEmpty()) {
            contentReport.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(0, dp(24), 0, dp(24))
                text = "No modifier usage for this date"
                textSize = 15f
                setTextColor(Color.parseColor("#757575"))
            })
            return
        }

        val totalCount = rows.sumOf { it.usageCount }
        val grouped = rows.groupBy { it.itemName }

        for ((itemName, modifiers) in grouped) {
            contentReport.addView(makeItemHeader(itemName))
            for (row in modifiers) {
                val isRemove = row.action == "REMOVE"
                val displayName = if (isRemove) "REMOVE ${row.modifierName}" else row.modifierName
                val nameColor = if (isRemove) "#C62828" else "#424242"
                val extraCost = if (row.totalExtraCents > 0)
                    "+${currencyFmt.format(row.totalExtraCents / 100.0)}" else null
                contentReport.addView(makeRow(displayName, row.usageCount.toString(), nameColor, extraCost))
            }
        }

        contentReport.addView(makeDivider())
        contentReport.addView(makeTotalRow("Total", totalCount.toString()))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
        setBackgroundColor(Color.parseColor("#E0E0E0"))
    }

    private fun makeItemHeader(name: String): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        text = name
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1565C0"))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun makeRow(name: String, count: String, nameColor: String = "#424242", extraCost: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(this@ModifierSalesReportActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = name
                textSize = 15f
                setTextColor(Color.parseColor(nameColor))
                if (nameColor == "#C62828") setTypeface(typeface, Typeface.BOLD)
            })
            if (extraCost != null) {
                addView(TextView(this@ModifierSalesReportActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) }
                    text = extraCost
                    textSize = 13f
                    setTextColor(Color.parseColor("#2E7D32"))
                })
            }
            addView(TextView(this@ModifierSalesReportActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = count
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
                gravity = Gravity.END
            })
        }

    private fun makeTotalRow(label: String, count: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(TextView(this@ModifierSalesReportActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                text = label
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#212121"))
            })
            addView(TextView(this@ModifierSalesReportActivity).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                text = count
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#2E7D32"))
                gravity = Gravity.END
            })
        }
}
