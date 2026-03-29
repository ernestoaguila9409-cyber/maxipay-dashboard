package com.ernesto.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.RoundedCornersTransformation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewEditReceiptActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BT_CONNECT = 1001
    }

    private lateinit var settings: ReceiptSettings
    private var currentLogoUrl: String = ""

    private lateinit var etBusinessName: EditText
    private lateinit var etAddress: EditText
    private lateinit var switchShowServer: SwitchCompat
    private lateinit var switchShowDateTime: SwitchCompat
    private lateinit var switchShowLogo: SwitchCompat
    private lateinit var switchShowEmail: SwitchCompat

    private lateinit var switchBoldBizName: SwitchCompat
    private lateinit var switchBoldAddress: SwitchCompat
    private lateinit var switchBoldOrderInfo: SwitchCompat
    private lateinit var switchBoldItems: SwitchCompat
    private lateinit var switchBoldTotals: SwitchCompat
    private lateinit var switchBoldGrandTotal: SwitchCompat
    private lateinit var switchBoldFooter: SwitchCompat

    private lateinit var seekFontBizName: SeekBar
    private lateinit var seekFontAddress: SeekBar
    private lateinit var seekFontOrderInfo: SeekBar
    private lateinit var seekFontItems: SeekBar
    private lateinit var seekFontTotals: SeekBar
    private lateinit var seekFontGrandTotal: SeekBar
    private lateinit var seekFontFooter: SeekBar
    private lateinit var tvFontBizNameVal: TextView
    private lateinit var tvFontAddressVal: TextView
    private lateinit var tvFontOrderInfoVal: TextView
    private lateinit var tvFontItemsVal: TextView
    private lateinit var tvFontTotalsVal: TextView
    private lateinit var tvFontGrandTotalVal: TextView
    private lateinit var tvFontFooterVal: TextView

    private lateinit var ivLogo: ImageView
    private lateinit var tvBusinessName: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvReceiptTitle: TextView
    private lateinit var tvOrderInfo: TextView
    private lateinit var tvSep1: TextView
    private lateinit var tvItems: TextView
    private lateinit var tvSep2: TextView
    private lateinit var tvTotals: TextView
    private lateinit var tvSep3: TextView
    private lateinit var tvGrandTotal: TextView
    private lateinit var tvPaymentInfo: TextView
    private lateinit var tvFooter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_edit_receipt)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Receipt Settings"

        settings = ReceiptSettings.load(this)
        bindViews()
        restoreInputs()
        populatePreview()
        setupListeners()
        startFirestoreSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        ReceiptSettings.stopBusinessInfoSync()
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
                populatePreview()
            }
        }
        ReceiptSettings.startBusinessInfoSync(this)
    }

    private fun bindViews() {
        etBusinessName = findViewById(R.id.etBusinessName)
        etAddress = findViewById(R.id.etAddress)
        switchShowServer = findViewById(R.id.switchShowServer)
        switchShowDateTime = findViewById(R.id.switchShowDateTime)
        switchShowLogo = findViewById(R.id.switchShowLogo)
        switchShowEmail = findViewById(R.id.switchShowEmail)

        switchBoldBizName = findViewById(R.id.switchBoldBizName)
        switchBoldAddress = findViewById(R.id.switchBoldAddress)
        switchBoldOrderInfo = findViewById(R.id.switchBoldOrderInfo)
        switchBoldItems = findViewById(R.id.switchBoldItems)
        switchBoldTotals = findViewById(R.id.switchBoldTotals)
        switchBoldGrandTotal = findViewById(R.id.switchBoldGrandTotal)
        switchBoldFooter = findViewById(R.id.switchBoldFooter)

        seekFontBizName = findViewById(R.id.seekFontBizName)
        seekFontAddress = findViewById(R.id.seekFontAddress)
        seekFontOrderInfo = findViewById(R.id.seekFontOrderInfo)
        seekFontItems = findViewById(R.id.seekFontItems)
        seekFontTotals = findViewById(R.id.seekFontTotals)
        seekFontGrandTotal = findViewById(R.id.seekFontGrandTotal)
        seekFontFooter = findViewById(R.id.seekFontFooter)
        tvFontBizNameVal = findViewById(R.id.tvFontBizNameVal)
        tvFontAddressVal = findViewById(R.id.tvFontAddressVal)
        tvFontOrderInfoVal = findViewById(R.id.tvFontOrderInfoVal)
        tvFontItemsVal = findViewById(R.id.tvFontItemsVal)
        tvFontTotalsVal = findViewById(R.id.tvFontTotalsVal)
        tvFontGrandTotalVal = findViewById(R.id.tvFontGrandTotalVal)
        tvFontFooterVal = findViewById(R.id.tvFontFooterVal)

        ivLogo = findViewById(R.id.ivLogo)
        tvBusinessName = findViewById(R.id.tvBusinessName)
        tvAddress = findViewById(R.id.tvAddress)
        tvEmail = findViewById(R.id.tvEmail)
        tvReceiptTitle = findViewById(R.id.tvReceiptTitle)
        tvOrderInfo = findViewById(R.id.tvOrderInfo)
        tvSep1 = findViewById(R.id.tvSep1)
        tvItems = findViewById(R.id.tvItems)
        tvSep2 = findViewById(R.id.tvSep2)
        tvTotals = findViewById(R.id.tvTotals)
        tvSep3 = findViewById(R.id.tvSep3)
        tvGrandTotal = findViewById(R.id.tvGrandTotal)
        tvPaymentInfo = findViewById(R.id.tvPaymentInfo)
        tvFooter = findViewById(R.id.tvFooter)
    }

    private fun restoreInputs() {
        etBusinessName.setText(settings.businessName)
        etAddress.setText(settings.addressText)
        switchShowServer.isChecked = settings.showServerName
        switchShowDateTime.isChecked = settings.showDateTime
        switchShowLogo.isChecked = settings.showLogo
        switchShowEmail.isChecked = settings.showEmail

        switchBoldBizName.isChecked = settings.boldBizName
        switchBoldAddress.isChecked = settings.boldAddress
        switchBoldOrderInfo.isChecked = settings.boldOrderInfo
        switchBoldItems.isChecked = settings.boldItems
        switchBoldTotals.isChecked = settings.boldTotals
        switchBoldGrandTotal.isChecked = settings.boldGrandTotal
        switchBoldFooter.isChecked = settings.boldFooter

        seekFontBizName.progress = settings.fontSizeBizName
        seekFontAddress.progress = settings.fontSizeAddress
        seekFontOrderInfo.progress = settings.fontSizeOrderInfo
        seekFontItems.progress = settings.fontSizeItems
        seekFontTotals.progress = settings.fontSizeTotals
        seekFontGrandTotal.progress = settings.fontSizeGrandTotal
        seekFontFooter.progress = settings.fontSizeFooter
        tvFontBizNameVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeBizName]
        tvFontAddressVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeAddress]
        tvFontOrderInfoVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeOrderInfo]
        tvFontItemsVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeItems]
        tvFontTotalsVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeTotals]
        tvFontGrandTotalVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeGrandTotal]
        tvFontFooterVal.text = ReceiptSettings.FONT_SIZE_LABELS[settings.fontSizeFooter]
    }

    private fun setupListeners() {
        etBusinessName.addTextChangedListener(simpleWatcher {
            settings = settings.copy(businessName = it)
            populatePreview()
        })
        etAddress.addTextChangedListener(simpleWatcher {
            settings = settings.copy(addressText = it)
            populatePreview()
        })

        switchShowServer.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showServerName = c); populatePreview()
        }
        switchShowDateTime.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showDateTime = c); populatePreview()
        }
        switchShowLogo.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showLogo = c); populatePreview()
        }
        switchShowEmail.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(showEmail = c); populatePreview()
        }

        switchBoldBizName.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldBizName = c); populatePreview()
        }
        switchBoldAddress.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldAddress = c); populatePreview()
        }
        switchBoldOrderInfo.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldOrderInfo = c); populatePreview()
        }
        switchBoldItems.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldItems = c); populatePreview()
        }
        switchBoldTotals.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldTotals = c); populatePreview()
        }
        switchBoldGrandTotal.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldGrandTotal = c); populatePreview()
        }
        switchBoldFooter.setOnCheckedChangeListener { _, c ->
            settings = settings.copy(boldFooter = c); populatePreview()
        }

        fun fontListener(label: TextView, copy: (Int) -> ReceiptSettings) =
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    label.text = ReceiptSettings.FONT_SIZE_LABELS[p]
                    if (user) { settings = copy(p); populatePreview() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }

        seekFontBizName.setOnSeekBarChangeListener(
            fontListener(tvFontBizNameVal) { settings.copy(fontSizeBizName = it) })
        seekFontAddress.setOnSeekBarChangeListener(
            fontListener(tvFontAddressVal) { settings.copy(fontSizeAddress = it) })
        seekFontOrderInfo.setOnSeekBarChangeListener(
            fontListener(tvFontOrderInfoVal) { settings.copy(fontSizeOrderInfo = it) })
        seekFontItems.setOnSeekBarChangeListener(
            fontListener(tvFontItemsVal) { settings.copy(fontSizeItems = it) })
        seekFontTotals.setOnSeekBarChangeListener(
            fontListener(tvFontTotalsVal) { settings.copy(fontSizeTotals = it) })
        seekFontGrandTotal.setOnSeekBarChangeListener(
            fontListener(tvFontGrandTotalVal) { settings.copy(fontSizeGrandTotal = it) })
        seekFontFooter.setOnSeekBarChangeListener(
            fontListener(tvFontFooterVal) { settings.copy(fontSizeFooter = it) })

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ReceiptSettings.save(this, settings)
            ReceiptSettings.saveToFirestore(settings)
            Toast.makeText(this, "Settings saved & synced!", Toast.LENGTH_SHORT).show()
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
            populatePreview()
            Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    }

    private fun fontSizeToSp(fontSize: Int): Float = when (fontSize) {
        1 -> 16f
        2 -> 20f
        else -> 12f
    }

    private fun loadLogo() {
        val url = settings.logoUrl
        if (settings.showLogo && url.isNotBlank()) {
            ivLogo.visibility = View.VISIBLE
            if (url != currentLogoUrl) {
                currentLogoUrl = url
                ivLogo.load(url) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(8f))
                    listener(
                        onError = { _, _ -> ivLogo.visibility = View.GONE }
                    )
                }
            }
        } else {
            ivLogo.visibility = View.GONE
            if (!settings.showLogo) currentLogoUrl = ""
        }
    }

    private fun populatePreview() {
        loadLogo()

        val bizNameSp = fontSizeToSp(settings.fontSizeBizName)
        val addrSp = fontSizeToSp(settings.fontSizeAddress)
        val orderSp = fontSizeToSp(settings.fontSizeOrderInfo)
        val itemsSp = fontSizeToSp(settings.fontSizeItems)
        val totalsSp = fontSizeToSp(settings.fontSizeTotals)
        val grandTotalSp = fontSizeToSp(settings.fontSizeGrandTotal)
        val footerSp = fontSizeToSp(settings.fontSizeFooter)

        fun bold(on: Boolean) = if (on) Typeface.BOLD else Typeface.NORMAL

        fun monospaceStyle(isBold: Boolean) = Typeface.create(
            Typeface.MONOSPACE,
            if (isBold) Typeface.BOLD else Typeface.NORMAL
        )

        tvBusinessName.text = settings.businessName
        tvBusinessName.setTypeface(null, bold(settings.boldBizName))
        tvBusinessName.textSize = bizNameSp + 4f

        tvAddress.text = settings.addressText
        tvAddress.setTypeface(null, bold(settings.boldAddress))
        tvAddress.textSize = addrSp

        if (settings.showEmail && settings.email.isNotBlank()) {
            tvEmail.text = settings.email
            tvEmail.visibility = View.VISIBLE
        } else {
            tvEmail.visibility = View.GONE
        }

        tvReceiptTitle.setTypeface(null, bold(settings.boldOrderInfo))
        tvReceiptTitle.textSize = orderSp + 2f

        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        val orderInfoLines = mutableListOf("Order #1042", "Type: Dine In")
        if (settings.showServerName) orderInfoLines.add("Server: Ernesto")
        if (settings.showDateTime) orderInfoLines.add("Date: $dateStr")
        tvOrderInfo.text = orderInfoLines.joinToString("\n")
        tvOrderInfo.setTypeface(null, bold(settings.boldOrderInfo))
        tvOrderInfo.textSize = orderSp

        val lwi = ReceiptSettings.lineWidthForSize(settings.fontSizeItems)
        tvSep1.text = "-".repeat(lwi)
        tvSep1.typeface = monospaceStyle(settings.boldItems)

        tvItems.text = buildString {
            appendLine(formatLine("2x Burger", "$19.98", lwi))
            appendLine(formatLine("  + Extra Cheese", "$1.50", lwi))
            appendLine(formatLine("1x Caesar Salad", "$12.50", lwi))
            appendLine(formatLine("1x Fries", "$5.99", lwi))
            appendLine(formatLine("2x Iced Tea", "$7.98", lwi))
            append(formatLine("1x Chocolate Cake", "$8.50", lwi))
        }
        tvItems.typeface = monospaceStyle(settings.boldItems)
        tvItems.textSize = itemsSp

        val lwt = ReceiptSettings.lineWidthForSize(settings.fontSizeTotals)
        tvSep2.text = "-".repeat(lwt)
        tvSep2.typeface = monospaceStyle(settings.boldTotals)

        tvTotals.text = buildString {
            appendLine(formatLine("Subtotal", "$56.45", lwt))
            appendLine(formatLine("Tax (8.25%)", "$4.66", lwt))
            append(formatLine("Tip", "$8.47", lwt))
        }
        tvTotals.typeface = monospaceStyle(settings.boldTotals)
        tvTotals.textSize = totalsSp

        val lwg = ReceiptSettings.lineWidthForSize(settings.fontSizeGrandTotal)
        tvSep3.text = "=".repeat(lwg)
        tvSep3.typeface = monospaceStyle(settings.boldGrandTotal)

        tvGrandTotal.text = formatLine("TOTAL", "$69.58", lwg)
        tvGrandTotal.typeface = monospaceStyle(settings.boldGrandTotal)
        tvGrandTotal.textSize = grandTotalSp + 2f

        tvPaymentInfo.text = "Visa **** 1234\nAuth: 123456\nType: Credit"
        tvPaymentInfo.setTypeface(null, bold(settings.boldFooter))
        tvPaymentInfo.textSize = footerSp

        tvFooter.text = "Thank you for dining with us!"
        tvFooter.setTypeface(null, bold(settings.boldFooter))
        tvFooter.textSize = footerSp
    }

    private fun printTestReceipt() {
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
        EscPosPrinter.printTestReceipt(this, settings)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_CONNECT) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                EscPosPrinter.printTestReceipt(this, settings)
            } else {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_LONG).show()
            }
        }
    }
}
