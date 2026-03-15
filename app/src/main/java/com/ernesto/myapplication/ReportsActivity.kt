package com.ernesto.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.ReportEngine
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

        loadDailySummary()
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
}
