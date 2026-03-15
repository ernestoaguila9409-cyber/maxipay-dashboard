package com.ernesto.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.ReportEngine
import com.ernesto.myapplication.engine.SalesByOrderType
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportsActivity : AppCompatActivity() {

    private val reportEngine = ReportEngine(FirebaseFirestore.getInstance())
    private val currencyFmt = NumberFormat.getCurrencyInstance()

    private lateinit var progressBar: ProgressBar
    private lateinit var card: MaterialCardView
    private lateinit var valGross: TextView
    private lateinit var valTax: TextView
    private lateinit var valTips: TextView
    private lateinit var valNet: TextView
    private lateinit var valTxCount: TextView
    private lateinit var valAvg: TextView
    private lateinit var valRefunds: TextView

    private lateinit var progressOrderType: ProgressBar
    private lateinit var cardOrderType: MaterialCardView
    private lateinit var valDineIn: TextView
    private lateinit var valDineInPct: TextView
    private lateinit var valToGo: TextView
    private lateinit var valToGoPct: TextView
    private lateinit var valBar: TextView
    private lateinit var valBarPct: TextView
    private lateinit var valOrderTypeTotal: TextView

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
        valTax = findViewById(R.id.valTaxCollected)
        valTips = findViewById(R.id.valTips)
        valNet = findViewById(R.id.valNetSales)
        valTxCount = findViewById(R.id.valTransactions)
        valAvg = findViewById(R.id.valAvgTicket)
        valRefunds = findViewById(R.id.valRefunds)

        progressOrderType = findViewById(R.id.progressOrderType)
        cardOrderType = findViewById(R.id.cardSalesByOrderType)
        valDineIn = findViewById(R.id.valDineIn)
        valDineInPct = findViewById(R.id.valDineInPct)
        valToGo = findViewById(R.id.valToGo)
        valToGoPct = findViewById(R.id.valToGoPct)
        valBar = findViewById(R.id.valBar)
        valBarPct = findViewById(R.id.valBarPct)
        valOrderTypeTotal = findViewById(R.id.valOrderTypeTotal)

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

        loadDailySummary()
        loadSalesByOrderType()
    }

    /**
     * Reusable accordion toggle: tapping [header] shows/hides [content] and
     * rotates the [arrow] indicator between ▶ (collapsed) and ▼ (expanded).
     * Future report cards only need to call this once with their three views.
     */
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

    private fun loadDailySummary() {
        progressBar.visibility = View.VISIBLE
        card.visibility = View.GONE

        reportEngine.getDailySalesSummary(
            onSuccess = { summary ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    card.visibility = View.VISIBLE

                    valGross.text = currencyFmt.format(summary.grossSales)
                    valTax.text = currencyFmt.format(summary.taxCollected)
                    valTips.text = currencyFmt.format(summary.tipsCollected)
                    valNet.text = currencyFmt.format(summary.netSales)
                    valTxCount.text = summary.totalTransactions.toString()
                    valAvg.text = currencyFmt.format(summary.averageTicket)
                    valRefunds.text = currencyFmt.format(summary.refunds)
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
}
