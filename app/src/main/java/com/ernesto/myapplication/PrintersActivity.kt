package com.ernesto.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintersActivity : AppCompatActivity() {

    private lateinit var txtEmptyHint: TextView
    private lateinit var printerCardsContainer: LinearLayout

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val statusTargets = mutableListOf<PrinterStatusTarget>()

    /** Last reachability written to Firestore per IP (for ONLINE lastSeen throttling). */
    private val lastPrinterReachabilityWriteAt = mutableMapOf<String, Long>()
    private val lastPrinterOnline = mutableMapOf<String, Boolean>()

    private var statusRefreshJob: Job? = null
    private var hasReIdentified = false

    private val printerPrefsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SelectedPrinterPrefs.ACTION_PRINTERS_PREFS_CHANGED) return
            if (isFinishing) return
            restartPrinterListAndPolling()
        }
    }

    private data class PrinterStatusTarget(
        val statusView: TextView,
        val ip: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printers)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Printers"

        txtEmptyHint = findViewById(R.id.txtPrintersEmptyHint)
        printerCardsContainer = findViewById(R.id.printerCardsContainer)

        findViewById<FloatingActionButton>(R.id.fabPrinters).setOnClickListener {
            showPrinterTypePickerThenOpenAddPrinter()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            printerPrefsChangedReceiver,
            IntentFilter(SelectedPrinterPrefs.ACTION_PRINTERS_PREFS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(printerPrefsChangedReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        restartPrinterListAndPolling()
    }

    override fun onPause() {
        statusRefreshJob?.cancel()
        statusRefreshJob = null
        super.onPause()
    }

    private fun restartPrinterListAndPolling() {
        statusRefreshJob?.cancel()
        bindSelectedPrinters()
        statusRefreshJob = lifecycleScope.launch {
            if (!hasReIdentified) {
                hasReIdentified = true
                reIdentifySavedPrinters()
            }
            while (isActive) {
                refreshPrinterOnlineStatuses()
                delay(STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun bindSelectedPrinters() {
        statusTargets.clear()
        printerCardsContainer.removeAllViews()

        val receiptPrinters = SelectedPrinterPrefs.getAll(this, PrinterDeviceType.RECEIPT)
        val kitchenPrinters = SelectedPrinterPrefs.getAll(this, PrinterDeviceType.KITCHEN)
        val allEmpty = receiptPrinters.isEmpty() && kitchenPrinters.isEmpty()

        txtEmptyHint.visibility = if (allEmpty) View.VISIBLE else View.GONE
        if (allEmpty) {
            lastPrinterOnline.clear()
            lastPrinterReachabilityWriteAt.clear()
            return
        }

        val activeIps = LinkedHashSet<String>()
        for (p in receiptPrinters) activeIps.add(p.ipAddress.trim())
        for (p in kitchenPrinters) activeIps.add(p.ipAddress.trim())
        lastPrinterOnline.keys.retainAll(activeIps)
        lastPrinterReachabilityWriteAt.keys.retainAll(activeIps)

        for (p in receiptPrinters) {
            PrinterFirestoreSync.mergeRegistration(db, PrinterDeviceType.RECEIPT, p)
        }
        for (p in kitchenPrinters) {
            PrinterFirestoreSync.mergeRegistration(db, PrinterDeviceType.KITCHEN, p)
        }

        val inflater = LayoutInflater.from(this)
        for (p in receiptPrinters) {
            addPrinterCard(inflater, PrinterDeviceType.RECEIPT, p)
        }
        for (p in kitchenPrinters) {
            addPrinterCard(inflater, PrinterDeviceType.KITCHEN, p)
        }
    }

    private fun addPrinterCard(
        inflater: LayoutInflater,
        type: PrinterDeviceType,
        display: SelectedPrinterDisplay,
    ) {
        val card = inflater.inflate(R.layout.item_lan_printer_card, printerCardsContainer, false)

        card.setOnClickListener {
            startActivity(
                Intent(this, PrinterDetailActivity::class.java).apply {
                    putExtra(PrinterDetailActivity.EXTRA_DEVICE_TYPE, type.name)
                    putExtra(PrinterDetailActivity.EXTRA_IP, display.ipAddress.trim())
                    putExtra(PrinterDetailActivity.EXTRA_NAME, display.name)
                    putExtra(PrinterDetailActivity.EXTRA_MODEL, display.modelLine)
                },
            )
        }

        val typeLabel = card.findViewById<TextView>(R.id.txtPrinterCardTypeLabel)
        val statusView = card.findViewById<TextView>(R.id.txtPrinterCardStatus)
        val nameView = card.findViewById<TextView>(R.id.txtPrinterCardName)
        val ipView = card.findViewById<TextView>(R.id.txtPrinterCardIp)
        val infoView = card.findViewById<TextView>(R.id.txtPrinterCardInfo)
        val btnTest = card.findViewById<MaterialButton>(R.id.btnPrinterCardTest)

        typeLabel.setText(
            when (type) {
                PrinterDeviceType.RECEIPT -> R.string.receipt_printer
                PrinterDeviceType.KITCHEN -> R.string.kitchen_printer
            },
        )
        nameView.text = display.name
        ipView.text = display.ipAddress
        infoView.text = display.modelLine
        infoView.visibility =
            if (display.modelLine.isBlank()) View.GONE else View.VISIBLE

        val labelsView = card.findViewById<TextView>(R.id.txtPrinterCardLabels)
        if (display.labels.isNotEmpty()) {
            labelsView.visibility = View.VISIBLE
            labelsView.text = getString(
                R.string.printer_card_labels_line,
                display.labels.joinToString(", "),
            )
        } else {
            labelsView.visibility = View.GONE
        }

        applyCheckingStatus(statusView)
        val ip = display.ipAddress.trim()
        statusTargets.add(PrinterStatusTarget(statusView, ip))

        val kitchenPrinter = type == PrinterDeviceType.KITCHEN
        btnTest.setOnClickListener {
            if (kitchenPrinter) {
                val style = PrinterKitchenStyleCache.styleForIp(ip)
                val cmdSet = PrinterKitchenStyleCache.commandSetForKitchenLan(ip, display.modelLine)
                val demoLabel = display.labels.firstOrNull()
                val title = display.name.trim().ifBlank { getString(R.string.kitchen_printer) }
                val segments = KitchenTicketBuilder.buildSampleDemoTicketSegments(
                    printerTitle = title,
                    style = style,
                    demoRoutingLabel = demoLabel,
                )
                EscPosPrinter.printKitchenChitToLan(
                    this,
                    ipAddress = ip,
                    segments = segments,
                    commandSet = cmdSet,
                    showSuccessToast = true,
                )
            } else {
                EscPosPrinter.printLanTestPrint(
                    this,
                    ipAddress = ip,
                    kitchenPrinter = false,
                )
            }
        }

        printerCardsContainer.addView(card)
    }

    /**
     * Re-probes each saved printer once per activity lifecycle.  If the scanner now
     * identifies a different manufacturer/model (e.g. an Epson that was previously
     * stored as Star Micronics), updates SharedPreferences, Firestore, and refreshes
     * the cards.
     */
    private suspend fun reIdentifySavedPrinters() {
        val allPrinters = mutableListOf<Pair<PrinterDeviceType, SelectedPrinterDisplay>>()
        for (type in listOf(PrinterDeviceType.RECEIPT, PrinterDeviceType.KITCHEN)) {
            for (p in SelectedPrinterPrefs.getAll(this, type)) {
                allPrinters.add(type to p)
            }
        }
        if (allPrinters.isEmpty()) return

        var anyUpdated = false
        for ((type, saved) in allPrinters) {
            val ip = saved.ipAddress.trim()
            if (ip.isEmpty()) continue
            try {
                val detected = withContext(Dispatchers.IO) {
                    ThermalPrinterScanner.scanLastOctetRange(
                        ip.substringBeforeLast('.'),
                        ip.substringAfterLast('.').toInt()..ip.substringAfterLast('.').toInt(),
                    ).firstOrNull()
                } ?: continue

                val newLabel = detected.displayLabel
                if (newLabel == saved.modelLine) continue

                Log.d("PrintersActivity", "$ip re-identified: ${saved.modelLine} → $newLabel")
                SelectedPrinterPrefs.add(this, type, saved.name, ip, newLabel, saved.labels)
                PrinterFirestoreSync.mergeRegistration(
                    db, type,
                    SelectedPrinterDisplay(saved.name, ip, newLabel, labels = saved.labels),
                    model = detected.model,
                    manufacturer = detected.manufacturer,
                    includeCommandSet = true,
                )
                anyUpdated = true
            } catch (e: Exception) {
                Log.w("PrintersActivity", "Re-identify $ip failed: ${e.message}")
            }
        }

        if (anyUpdated && !isFinishing) {
            runOnUiThread { bindSelectedPrinters() }
        }
    }

    private suspend fun refreshPrinterOnlineStatuses() {
        val snapshot = statusTargets.toList()
        if (snapshot.isEmpty()) return

        val results = coroutineScope {
            snapshot.map { target ->
                async(Dispatchers.IO) {
                    target to isPrinterOnline(target.ip)
                }
            }.awaitAll()
        }

        if (isFinishing) return

        val now = System.currentTimeMillis()
        for ((target, online) in results) {
            applyOnlineStatus(target.statusView, online)
            val ip = target.ip
            val wasOnline = lastPrinterOnline[ip]
            if (wasOnline != online) {
                PrinterFirestoreSync.updateReachability(db, ip, online, now)
                lastPrinterOnline[ip] = online
                if (online) lastPrinterReachabilityWriteAt[ip] = now
            } else if (online) {
                val last = lastPrinterReachabilityWriteAt[ip] ?: 0L
                if (now - last >= LAST_SEEN_FIRESTORE_INTERVAL_MS) {
                    PrinterFirestoreSync.updateReachability(db, ip, true, now)
                    lastPrinterReachabilityWriteAt[ip] = now
                }
            }
        }
    }

    private fun applyCheckingStatus(textView: TextView) {
        textView.setText(R.string.printer_status_checking)
        textView.setTextColor(ContextCompat.getColor(this, R.color.printer_status_checking))
    }

    private fun applyOnlineStatus(textView: TextView, online: Boolean) {
        if (online) {
            textView.setText(R.string.printer_status_online)
            textView.setTextColor(ContextCompat.getColor(this, R.color.printer_status_online))
        } else {
            textView.setText(R.string.printer_status_offline)
            textView.setTextColor(ContextCompat.getColor(this, R.color.printer_status_offline))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val STATUS_REFRESH_INTERVAL_MS = 7_000L
        private const val LAST_SEEN_FIRESTORE_INTERVAL_MS = 12_000L
    }
}
