package com.volt.maximobile

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.volt.maximobile.dvpaylite.P8LogoHelper
import com.volt.maximobile.dvpaylite.P8ReceiptPrinter
import com.volt.maximobile.dvpaylite.P8ReceiptPrinter.ReceiptSegment
import com.google.android.gms.tasks.Tasks
import com.volt.shared.MerchantFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewEditReceiptActivity : AppCompatActivity() {

    private lateinit var settings: ReceiptSettings

    private lateinit var etBusinessName: EditText
    private lateinit var etAddress: EditText
    private lateinit var tvBizNameLineHint: TextView
    private lateinit var tvAddressLineHint: TextView
    private lateinit var switchShowServer: SwitchCompat
    private lateinit var switchShowDateTime: SwitchCompat
    private lateinit var switchShowEmail: SwitchCompat

    private lateinit var switchBoldBizName: SwitchCompat
    private lateinit var switchBoldAddress: SwitchCompat
    private lateinit var switchBoldOrderInfo: SwitchCompat
    private lateinit var switchBoldItems: SwitchCompat
    private lateinit var switchBoldTotals: SwitchCompat
    private lateinit var switchBoldGrandTotal: SwitchCompat
    private lateinit var switchBoldFooter: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_edit_receipt)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Receipt Settings"

        val mid = PosDeviceIdentity.getMerchantId(this).trim()
        if (mid.isNotEmpty()) {
            MerchantFirestore.init(mid)
        }

        settings = ReceiptSettings.load(this)
        bindViews()
        restoreInputs()
        updateLineLimitHints()
        setupListeners()
        startFirestoreSync()
        refreshLogoFromFirestore()
    }

    /** One-shot pull so logoUrl from maxipaypos.com is on-device before printing. */
    private fun refreshLogoFromFirestore() {
        if (!MerchantFirestore.isInitialized) return
        MerchantFirestore.doc("Settings", "businessInfo").get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) return@addOnSuccessListener
                val url = snap.getString("logoUrl")?.trim().orEmpty()
                if (url.isNotEmpty() && url != settings.logoUrl) {
                    settings = settings.copy(logoUrl = url)
                    ReceiptSettings.save(this, settings)
                    P8LogoHelper.clearCache()
                }
            }
    }

    override fun onDestroy() {
        ReceiptSettings.setOnSettingsChangedListener(null)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun startFirestoreSync() {
        ReceiptSettings.setOnSettingsChangedListener { updated ->
            runOnUiThread {
                settings = updated
                restoreInputs()
                updateLineLimitHints()
            }
        }
        ReceiptSettings.startBusinessInfoSync(this)
    }

    private fun bindViews() {
        etBusinessName = findViewById(R.id.etBusinessName)
        etAddress = findViewById(R.id.etAddress)
        tvBizNameLineHint = findViewById(R.id.tvBizNameLineHint)
        tvAddressLineHint = findViewById(R.id.tvAddressLineHint)
        switchShowServer = findViewById(R.id.switchShowServer)
        switchShowDateTime = findViewById(R.id.switchShowDateTime)
        switchShowEmail = findViewById(R.id.switchShowEmail)

        switchBoldBizName = findViewById(R.id.switchBoldBizName)
        switchBoldAddress = findViewById(R.id.switchBoldAddress)
        switchBoldOrderInfo = findViewById(R.id.switchBoldOrderInfo)
        switchBoldItems = findViewById(R.id.switchBoldItems)
        switchBoldTotals = findViewById(R.id.switchBoldTotals)
        switchBoldGrandTotal = findViewById(R.id.switchBoldGrandTotal)
        switchBoldFooter = findViewById(R.id.switchBoldFooter)
    }

    private fun restoreInputs() {
        etBusinessName.setText(settings.businessName)
        etAddress.setText(settings.addressText)
        switchShowServer.isChecked = settings.showServerName
        switchShowDateTime.isChecked = settings.showDateTime
        switchShowEmail.isChecked = settings.showEmail

        switchBoldBizName.isChecked = settings.boldBizName
        switchBoldAddress.isChecked = settings.boldAddress
        switchBoldOrderInfo.isChecked = settings.boldOrderInfo
        switchBoldItems.isChecked = settings.boldItems
        switchBoldTotals.isChecked = settings.boldTotals
        switchBoldGrandTotal.isChecked = settings.boldGrandTotal
        switchBoldFooter.isChecked = settings.boldFooter
    }

    private fun setupListeners() {
        etBusinessName.addTextChangedListener(simpleWatcher {
            settings = settings.copy(businessName = it)
            updateLineLimitHints()
        })
        etAddress.addTextChangedListener(simpleWatcher {
            settings = settings.copy(addressText = it)
            updateLineLimitHints()
        })

        switchShowServer.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showServerName = c)
        }
        switchShowDateTime.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showDateTime = c)
        }
        switchShowEmail.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showEmail = c)
        }

        switchBoldBizName.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldBizName = c)
        }
        switchBoldAddress.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldAddress = c)
        }
        switchBoldOrderInfo.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldOrderInfo = c)
        }
        switchBoldItems.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldItems = c)
        }
        switchBoldTotals.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldTotals = c)
        }
        switchBoldGrandTotal.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldGrandTotal = c)
        }
        switchBoldFooter.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldFooter = c)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ReceiptSettings.save(this, settings)
            ReceiptSettings.saveToFirestore(settings)
            Toast.makeText(this, "Settings saved & synced!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnViewReceipt).setOnClickListener {
            showReceiptPreviewDialog()
        }

        findViewById<Button>(R.id.btnTestReceipt).setOnClickListener {
            ReceiptSettings.save(this, settings)
            printTestReceipt()
        }

        findViewById<Button>(R.id.btnResetDefaults).setOnClickListener {
            settings = ReceiptSettings(
                businessName = settings.businessName,
                addressText = settings.addressText,
                email = settings.email,
                logoUrl = settings.logoUrl
            )
            restoreInputs()
            updateLineLimitHints()
            Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    }

    private val p8Width = P8ReceiptPrinter.LINE_WIDTH

    private fun updateLineLimitHints() {
        val gray = 0xFF888888.toInt()
        val warn = 0xFFE65100.toInt()

        val bizMax = maxPhysicalLineLength(settings.businessName)
        tvBizNameLineHint.setTextColor(if (bizMax > p8Width) warn else gray)
        tvBizNameLineHint.text = if (bizMax > p8Width)
            "Longest line is $bizMax chars — wraps at $p8Width on P8 printer."
        else
            "Max $p8Width chars per line (P8 fixed width)."

        val addrMax = maxPhysicalLineLength(settings.addressText)
        tvAddressLineHint.setTextColor(if (addrMax > p8Width) warn else gray)
        tvAddressLineHint.text = if (addrMax > p8Width)
            "Longest line is $addrMax chars — wraps at $p8Width on P8 printer."
        else
            "Max $p8Width chars per line (P8 fixed width)."
    }

    private fun showReceiptPreviewDialog() {
        val mono = Typeface.MONOSPACE
        fun boldMono(on: Boolean) = Typeface.create(mono, if (on) Typeface.BOLD else Typeface.NORMAL)
        fun bold(on: Boolean) = if (on) Typeface.BOLD else Typeface.NORMAL
        val w = p8Width

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        fun addText(
            text: String,
            isBold: Boolean = false,
            centered: Boolean = false,
            useMono: Boolean = false,
            sizeSp: Float = 12f
        ) {
            val tv = TextView(this).apply {
                this.text = text
                textSize = sizeSp
                typeface = if (useMono) boldMono(isBold) else null
                if (!useMono) setTypeface(null, bold(isBold))
                gravity = if (centered) Gravity.CENTER else Gravity.START
            }
            content.addView(tv)
        }

        fun addSpacer(heightDp: Int = 4) {
            val v = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (heightDp * resources.displayMetrics.density).toInt()
                )
            }
            content.addView(v)
        }

        addText(
            wrapThermalText(settings.businessName, w).joinToString("\n"),
            isBold = settings.boldBizName,
            centered = true,
            sizeSp = 14f
        )

        addText(
            wrapThermalText(settings.addressText, w).joinToString("\n"),
            isBold = settings.boldAddress,
            centered = true,
            sizeSp = 12f
        )

        if (settings.showEmail && settings.email.isNotBlank()) {
            addText(
                wrapThermalText(settings.email.trim(), w).joinToString("\n"),
                centered = true,
                sizeSp = 11f
            )
        }

        addSpacer(8)
        addText("RECEIPT", isBold = settings.boldOrderInfo, centered = true, sizeSp = 13f)
        addSpacer(4)

        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        val orderLines = mutableListOf("Order #1042", "Type: Dine In")
        if (settings.showServerName) orderLines.add("Server: Ernesto")
        if (settings.showDateTime) orderLines.add(receiptOrderInfoDateLine(dateStr))
        addText(orderLines.joinToString("\n"), isBold = settings.boldOrderInfo, centered = true, sizeSp = 12f)

        addSpacer(4)
        addText("-".repeat(w), useMono = true, isBold = settings.boldItems)

        val fl = P8ReceiptPrinter::formatLine
        val items = buildString {
            appendLine(fl("2x Burger", "$19.98", w))
            appendLine(fl("  + Extra Cheese", "$1.50", w))
            appendLine(fl("1x Caesar Salad", "$12.50", w))
            appendLine(fl("1x Fries", "$5.99", w))
            appendLine(fl("2x Iced Tea", "$7.98", w))
            append(fl("1x Chocolate Cake", "$8.50", w))
        }
        addText(items, isBold = settings.boldItems, useMono = true)

        addSpacer(4)
        addText("-".repeat(w), useMono = true, isBold = settings.boldTotals)

        val totals = buildString {
            appendLine(fl("Subtotal", "$56.45", w))
            appendLine(fl("Tax (8.25%)", "$4.66", w))
            append(fl("Tip", "$8.47", w))
        }
        addText(totals, isBold = settings.boldTotals, useMono = true)

        addSpacer(2)
        addText("=".repeat(w), useMono = true, isBold = settings.boldGrandTotal)
        addText(fl("TOTAL", "$69.58", w), isBold = settings.boldGrandTotal, useMono = true, sizeSp = 13f)

        addSpacer(8)
        addText("Visa **** 1234\nAuth: 123456\nType: Credit", isBold = settings.boldFooter, centered = true, sizeSp = 12f)

        addSpacer(8)
        addText("Thank you for dining with us!", isBold = settings.boldFooter, centered = true, sizeSp = 12f)

        val scrollView = ScrollView(this).apply {
            addView(content)
        }

        AlertDialog.Builder(this)
            .setTitle("Receipt Preview (P8 — 24 chars)")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun printTestReceipt() {
        settings = ReceiptSettings.load(this)
        if (MerchantFirestore.isInitialized) {
            try {
                val snap = Tasks.await(MerchantFirestore.doc("Settings", "businessInfo").get())
                val url = snap.getString("logoUrl")?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    settings = settings.copy(logoUrl = url)
                    ReceiptSettings.save(this, settings)
                    P8LogoHelper.clearCache()
                }
            } catch (_: Exception) {
                // Fall back to cached settings.
            }
        }
        P8ReceiptPrinter.init(applicationContext)

        val logoUrl = settings.logoUrl.trim()
        if (logoUrl.isEmpty()) {
            Toast.makeText(
                this,
                "No logo on device. Upload and save on maxipaypos.com, then reopen this screen.",
                Toast.LENGTH_LONG,
            ).show()
        }

        P8ReceiptPrinter.printReceipt(
            segments = buildTestReceiptSegments(),
            settings = settings,
            onSuccess = {
                Toast.makeText(this, "Test receipt printed", Toast.LENGTH_SHORT).show()
            },
            onFailure = { msg ->
                Toast.makeText(this, "Print failed: $msg", Toast.LENGTH_LONG).show()
            },
            onLogoSkipped = { _ ->
                Toast.makeText(
                    this,
                    "Receipt printed without logo (could not download image). Check Wi‑Fi.",
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    private fun buildTestReceiptSegments(): List<ReceiptSegment> {
        val w = p8Width
        val fl = P8ReceiptPrinter::formatLine
        val segs = mutableListOf<ReceiptSegment>()

        fun seg(text: String, bold: Boolean = false, centered: Boolean = false) {
            segs += ReceiptSegment(text, bold = bold, centered = centered)
        }

        for (line in wrapThermalText(settings.businessName, w)) {
            seg(line, bold = settings.boldBizName, centered = true)
        }
        for (line in settings.addressText.split("\n")) {
            for (wrapped in wrapThermalText(line.trim(), w)) {
                if (wrapped.isNotEmpty()) {
                    seg(wrapped, bold = settings.boldAddress, centered = true)
                }
            }
        }
        if (settings.showEmail && settings.email.isNotBlank()) {
            for (line in wrapThermalText(settings.email.trim(), w)) {
                seg(line, centered = true)
            }
        }
        seg("")

        seg("RECEIPT", bold = settings.boldOrderInfo, centered = true)
        seg("")

        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        seg("Order #1042", bold = settings.boldOrderInfo, centered = true)
        seg("Type: Dine In", bold = settings.boldOrderInfo, centered = true)
        if (settings.showServerName) {
            seg("Server: Ernesto", bold = settings.boldOrderInfo, centered = true)
        }
        if (settings.showDateTime) {
            seg(receiptOrderInfoDateLine(dateStr), bold = settings.boldOrderInfo, centered = true)
        }
        seg("")

        seg("-".repeat(w), bold = settings.boldItems)
        seg(fl("2x Burger", "$19.98", w), bold = settings.boldItems)
        seg(fl("  + Extra Cheese", "$1.50", w), bold = settings.boldItems)
        seg(fl("1x Caesar Salad", "$12.50", w), bold = settings.boldItems)
        seg(fl("1x Fries", "$5.99", w), bold = settings.boldItems)
        seg(fl("2x Iced Tea", "$7.98", w), bold = settings.boldItems)
        seg(fl("1x Chocolate Cake", "$8.50", w), bold = settings.boldItems)
        seg("")

        seg("-".repeat(w), bold = settings.boldTotals)
        seg(fl("Subtotal", "$56.45", w), bold = settings.boldTotals)
        seg(fl("Tax (8.25%)", "$4.66", w), bold = settings.boldTotals)
        seg(fl("Tip", "$8.47", w), bold = settings.boldTotals)
        seg("")

        seg("=".repeat(w), bold = settings.boldGrandTotal)
        seg(fl("TOTAL", "$69.58", w), bold = settings.boldGrandTotal)
        seg("")

        seg("Visa **** 1234", bold = settings.boldFooter, centered = true)
        seg("Auth: 123456", bold = settings.boldFooter, centered = true)
        seg("Type: Credit", bold = settings.boldFooter, centered = true)
        seg("")
        seg("Thank you for dining with us!", bold = settings.boldFooter, centered = true)
        seg("")

        return segs
    }
}
