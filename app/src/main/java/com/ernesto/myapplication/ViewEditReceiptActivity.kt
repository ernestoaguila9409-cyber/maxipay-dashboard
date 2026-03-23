package com.ernesto.myapplication

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewEditReceiptActivity : AppCompatActivity() {

    private lateinit var settings: ReceiptSettings

    private lateinit var etBusinessName: EditText
    private lateinit var etAddress: EditText
    private lateinit var switchShowServer: SwitchCompat
    private lateinit var switchShowDateTime: SwitchCompat

    private lateinit var tvBusinessName: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvReceiptTitle: TextView
    private lateinit var tvOrderInfo: TextView
    private lateinit var tvSep1: TextView
    private lateinit var tvItems: TextView
    private lateinit var tvSep2: TextView
    private lateinit var tvTotals: TextView
    private lateinit var tvSep3: TextView
    private lateinit var tvGrandTotal: TextView
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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindViews() {
        etBusinessName = findViewById(R.id.etBusinessName)
        etAddress = findViewById(R.id.etAddress)
        switchShowServer = findViewById(R.id.switchShowServer)
        switchShowDateTime = findViewById(R.id.switchShowDateTime)

        tvBusinessName = findViewById(R.id.tvBusinessName)
        tvAddress = findViewById(R.id.tvAddress)
        tvReceiptTitle = findViewById(R.id.tvReceiptTitle)
        tvOrderInfo = findViewById(R.id.tvOrderInfo)
        tvSep1 = findViewById(R.id.tvSep1)
        tvItems = findViewById(R.id.tvItems)
        tvSep2 = findViewById(R.id.tvSep2)
        tvTotals = findViewById(R.id.tvTotals)
        tvSep3 = findViewById(R.id.tvSep3)
        tvGrandTotal = findViewById(R.id.tvGrandTotal)
        tvFooter = findViewById(R.id.tvFooter)
    }

    private fun restoreInputs() {
        etBusinessName.setText(settings.businessName)
        etAddress.setText(settings.addressText)
        switchShowServer.isChecked = settings.showServerName
        switchShowDateTime.isChecked = settings.showDateTime
    }

    private fun setupListeners() {
        etBusinessName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settings = settings.copy(businessName = s?.toString() ?: "")
                populatePreview()
            }
        })

        etAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                settings = settings.copy(addressText = s?.toString() ?: "")
                populatePreview()
            }
        })

        switchShowServer.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(showServerName = isChecked)
            populatePreview()
        }

        switchShowDateTime.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(showDateTime = isChecked)
            populatePreview()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            ReceiptSettings.save(this, settings)
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTestReceipt).setOnClickListener {
            ReceiptSettings.save(this, settings)
            printTestReceipt()
        }

        findViewById<Button>(R.id.btnResetDefaults).setOnClickListener {
            settings = ReceiptSettings()
            restoreInputs()
            populatePreview()
            Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Preview ────────────────────────────────────────────

    private fun populatePreview() {
        tvBusinessName.text = settings.businessName
        tvAddress.text = settings.addressText

        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        val orderInfoLines = mutableListOf<String>()
        orderInfoLines.add("Order #1042")
        orderInfoLines.add("Type: Dine In")
        if (settings.showServerName) orderInfoLines.add("Server: Ernesto")
        if (settings.showDateTime) orderInfoLines.add("Date: $dateStr")
        tvOrderInfo.text = orderInfoLines.joinToString("\n")

        tvSep1.text = "-".repeat(LINE_WIDTH)

        tvItems.text = buildString {
            appendLine(formatLine("2x Burger", "$19.98", LINE_WIDTH))
            appendLine(formatLine("  + Extra Cheese", "$1.50", LINE_WIDTH))
            appendLine(formatLine("1x Caesar Salad", "$12.50", LINE_WIDTH))
            appendLine(formatLine("1x Fries", "$5.99", LINE_WIDTH))
            appendLine(formatLine("2x Iced Tea", "$7.98", LINE_WIDTH))
            append(formatLine("1x Chocolate Cake", "$8.50", LINE_WIDTH))
        }

        tvSep2.text = "-".repeat(LINE_WIDTH)

        tvTotals.text = buildString {
            appendLine(formatLine("Subtotal", "$56.45", LINE_WIDTH))
            appendLine(formatLine("Tax (8.25%)", "$4.66", LINE_WIDTH))
            append(formatLine("Tip", "$8.47", LINE_WIDTH))
        }

        tvSep3.text = "=".repeat(LINE_WIDTH)
        tvGrandTotal.text = formatLine("TOTAL", "$69.58", LINE_WIDTH)
        tvFooter.text = "Thank you for dining with us!"
    }

    // ── Test Print ──────────────────────────────────────────

    private data class PrintSegment(val text: String, val fontSize: Int, val alignment: Int)

    private fun printTestReceipt() {
        Toast.makeText(this, "Printing test receipt…", Toast.LENGTH_SHORT).show()
        val segments = buildTestSegments()
        sendToPrinter(segments)
    }

    private fun buildTestSegments(): List<PrintSegment> {
        val rs = settings
        val segments = mutableListOf<PrintSegment>()
        val c = ALIGN_CENTER

        fun centered(text: String) { segments.add(PrintSegment(text, 0, c)) }
        fun left(text: String) { segments.add(PrintSegment(text, 0, 0)) }

        centered(rs.businessName)
        for (line in rs.addressText.split("\n")) {
            centered(line)
        }

        centered("")
        centered("RECEIPT")
        centered("")

        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        centered("Order #1042")
        centered("Type: Dine In")
        if (rs.showServerName) centered("Server: Ernesto")
        if (rs.showDateTime) centered("Date: $dateStr")
        centered("")

        left("-".repeat(LINE_WIDTH))
        left(formatLine("2x Burger", "$19.98", LINE_WIDTH))
        left(formatLine("  + Extra Cheese", "$1.50", LINE_WIDTH))
        left(formatLine("1x Caesar Salad", "$12.50", LINE_WIDTH))
        left(formatLine("1x Fries", "$5.99", LINE_WIDTH))
        left(formatLine("2x Iced Tea", "$7.98", LINE_WIDTH))
        left(formatLine("1x Chocolate Cake", "$8.50", LINE_WIDTH))
        left("-".repeat(LINE_WIDTH))

        centered("")
        left(formatLine("Subtotal", "$56.45", LINE_WIDTH))
        left(formatLine("Tax (8.25%)", "$4.66", LINE_WIDTH))
        left(formatLine("Tip", "$8.47", LINE_WIDTH))
        left("=".repeat(LINE_WIDTH))

        centered("")
        left(formatLine("TOTAL", "$69.58", LINE_WIDTH))

        centered("")
        centered("Thank you for dining with us!")

        return segments
    }

    // OmniDriver addText API: addText(fontSize, alignment, text)
    // fontSize: 0=normal, 1=large, 2=x-large
    // alignment: 0=left, 1=center, 2=right
    private fun sendToPrinter(segments: List<PrintSegment>) {
        val omniApk = "/system_ext/app/omnidriver-service/omnidriver-service.apk"
        val loader = dalvik.system.DexClassLoader(omniApk, cacheDir.absolutePath, null, classLoader)

        val intent = android.content.Intent("sdksuite-omnidriver")
        intent.setPackage("com.sdksuite.omnidriver")

        bindService(intent, object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                try {
                    val omniStub = loader.loadClass("com.sdksuite.omnidriver.aidl.IOmniDriver\$Stub")
                    val omniProxy = omniStub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, service)

                    val initBundle = android.os.Bundle()
                    initBundle.putString("packageName", packageName)
                    omniProxy.javaClass.getMethod("init", android.os.Bundle::class.java, android.os.IBinder::class.java)
                        .invoke(omniProxy, initBundle, android.os.Binder())

                    val printerBinder = omniProxy.javaClass.getMethod("getPrinter", android.os.Bundle::class.java)
                        .invoke(omniProxy, android.os.Bundle()) as? android.os.IBinder

                    if (printerBinder == null) {
                        runOnUiThread { Toast.makeText(this@ViewEditReceiptActivity, "Printer not available", Toast.LENGTH_LONG).show() }
                        return
                    }

                    val printerStub = loader.loadClass("com.sdksuite.omnidriver.aidl.printer.IPrinter\$Stub")
                    val printerProxy = printerStub.getMethod("asInterface", android.os.IBinder::class.java)
                        .invoke(null, printerBinder)!!

                    printerProxy.javaClass.getMethod("openDevice", Int::class.javaPrimitiveType)
                        .invoke(printerProxy, 0)

                    val addText = printerProxy.javaClass.getMethod(
                        "addText", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
                    )

                    for (seg in segments) {
                        addText.invoke(printerProxy, seg.fontSize, seg.alignment, seg.text + "\n")
                    }

                    printerProxy.javaClass.getMethod("feedLine", Int::class.javaPrimitiveType)
                        .invoke(printerProxy, 10)

                    val listenerStubClass = loader.loadClass("com.sdksuite.omnidriver.aidl.printer.OnPrintListener\$Stub")
                    val listenerInstance = object : android.os.Binder() {
                        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                            Log.d("TestPrint", "PrintListener onTransact code=$code")
                            return try { super.onTransact(code, data, reply, flags) } catch (e: Exception) { reply?.writeNoException(); true }
                        }
                    }
                    val listenerInterface = loader.loadClass("com.sdksuite.omnidriver.aidl.printer.OnPrintListener")
                    val listenerProxy = listenerStubClass.getMethod("asInterface", android.os.IBinder::class.java)
                        .invoke(null, listenerInstance)

                    printerProxy.javaClass.getMethod("startPrint", listenerInterface)
                        .invoke(printerProxy, listenerProxy)

                    Log.d("TestPrint", "Test receipt print started")
                    runOnUiThread {
                        Toast.makeText(this@ViewEditReceiptActivity, "Test receipt printed!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("TestPrint", "Print error: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@ViewEditReceiptActivity, "Print failed: ${e.cause?.message ?: e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                Log.d("TestPrint", "OmniDriver disconnected")
            }
        }, android.content.Context.BIND_AUTO_CREATE)
    }
}
