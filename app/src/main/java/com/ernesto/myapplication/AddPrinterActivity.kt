package com.ernesto.myapplication

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddPrinterActivity : AppCompatActivity() {

    private lateinit var printerType: PrinterDeviceType
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var txtScanning: TextView
    private lateinit var progressScan: ProgressBar
    private lateinit var txtResultsHeader: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var recyclerDiscovered: RecyclerView
    private lateinit var btnAddManually: MaterialButton
    private lateinit var btnAddInternalKitchen: MaterialButton

    private val adapter by lazy {
        DiscoveredIpAdapter(this) { printer -> showNameDialogAndSave(printer) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val type = PrinterDeviceType.fromIntentExtra(intent.getStringExtra(EXTRA_PRINTER_TYPE))
        if (type == null) {
            finish()
            return
        }
        printerType = type

        setContentView(R.layout.activity_add_printer)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_printer_screen_title)

        txtScanning = findViewById(R.id.txtScanning)
        progressScan = findViewById(R.id.progressScan)
        txtResultsHeader = findViewById(R.id.txtResultsHeader)
        txtEmpty = findViewById(R.id.txtEmpty)
        recyclerDiscovered = findViewById(R.id.recyclerDiscovered)
        btnAddManually = findViewById(R.id.btnAddManually)
        btnAddInternalKitchen = findViewById(R.id.btnAddInternalKitchen)

        recyclerDiscovered.layoutManager = LinearLayoutManager(this)
        recyclerDiscovered.adapter = adapter

        btnAddManually.setOnClickListener { showManualIpDialog() }
        btnAddInternalKitchen.setOnClickListener { showInternalKitchenNameDialog() }
        updateInternalKitchenButtonVisibility()

        lifecycleScope.launch {
            ThermalPrinterScanner.clearCache()
            val found = try {
                ThermalPrinterScanner.scanSubnet10_0_0(context = applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
            showResults(filterOutAlreadyConfiguredPrinters(found))
        }
    }

    private fun hasInternalKitchenSaved(): Boolean =
        SelectedPrinterPrefs.getAll(this, PrinterDeviceType.KITCHEN)
            .any { InternalKitchenPrinter.isInternalAddress(it.ipAddress) }

    private fun updateInternalKitchenButtonVisibility() {
        if (printerType != PrinterDeviceType.KITCHEN) {
            btnAddInternalKitchen.visibility = View.GONE
            return
        }
        if (hasInternalKitchenSaved() || !InternalKitchenPrinter.isBluetoothPathAvailable()) {
            btnAddInternalKitchen.visibility = View.GONE
            return
        }
        btnAddInternalKitchen.visibility = View.VISIBLE
    }

    private fun showInternalKitchenNameDialog() {
        showPrinterNameKeypadDialog(
            title = getString(R.string.internal_kitchen_printer_dialog_title),
            message = null,
            initialName = InternalKitchenPrinter.defaultDisplayName(this),
        ) { name -> persistInternalKitchenPrinter(name) }
    }

    private fun persistInternalKitchenPrinter(name: String) {
        lifecycleScope.launch {
            try {
                SelectedPrinterPrefs.add(
                    this@AddPrinterActivity,
                    PrinterDeviceType.KITCHEN,
                    name = name,
                    ipAddress = InternalKitchenPrinter.ADDRESS_KEY,
                    modelLine = InternalKitchenPrinter.modelLine(this@AddPrinterActivity),
                )
                Toast.makeText(this@AddPrinterActivity, R.string.printer_saved, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@AddPrinterActivity,
                    getString(R.string.printer_save_failed, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** IPs already saved on this device (receipt and kitchen); exclude from scan so users do not re-add the same printer. */
    private fun configuredPrinterIpSet(): Set<String> {
        val ips = LinkedHashSet<String>()
        for (type in PrinterDeviceType.values()) {
            for (p in SelectedPrinterPrefs.getAll(this, type)) {
                p.ipAddress.trim().takeIf { it.isNotEmpty() }?.let { ips.add(it) }
            }
        }
        return ips
    }

    private fun filterOutAlreadyConfiguredPrinters(found: List<DetectedPrinter>): List<DetectedPrinter> {
        val used = configuredPrinterIpSet()
        if (used.isEmpty()) return found
        return found.filterNot { used.contains(it.ipAddress.trim()) }
    }

    private fun showResults(found: List<DetectedPrinter>) {
        updateInternalKitchenButtonVisibility()
        txtScanning.visibility = View.GONE
        progressScan.visibility = View.GONE
        txtResultsHeader.visibility = View.VISIBLE
        if (found.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            recyclerDiscovered.visibility = View.GONE
        } else {
            txtEmpty.visibility = View.GONE
            recyclerDiscovered.visibility = View.VISIBLE
            adapter.submitList(found)
        }
    }

    private fun defaultPrinterName(printer: DetectedPrinter): String {
        val snmpName = printer.name
        if (!snmpName.isNullOrBlank()) return snmpName

        val label = when (printerType) {
            PrinterDeviceType.RECEIPT -> getString(R.string.receipt_printer)
            PrinterDeviceType.KITCHEN -> getString(R.string.kitchen_printer)
        }
        return getString(R.string.default_printer_name_template, label, printer.ipAddress)
    }

    private fun showManualIpDialog() {
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.ip_address)
        }
        val edit = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        til.addView(edit)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_manually)
            .setView(til)
            .setPositiveButton(R.string.continue_label, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val ip = edit.text?.toString()?.trim().orEmpty()
                if (!isValidIpv4(ip)) {
                    Toast.makeText(this, R.string.invalid_ip_address, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                showNameDialogAndSave(DetectedPrinter(ipAddress = ip))
            }
        }
        dialog.show()
    }

    private fun showNameDialogAndSave(printer: DetectedPrinter) {
        val infoLine = buildString {
            append(getString(R.string.save_printer_ip_message, printer.ipAddress))
            if (!printer.model.isNullOrBlank() || !printer.manufacturer.isNullOrBlank()) {
                append("\n")
                append(printer.displayLabel)
            }
        }
        showPrinterNameKeypadDialog(
            title = getString(R.string.save_printer),
            message = infoLine,
            initialName = defaultPrinterName(printer),
        ) { name -> persistPrinter(name, printer) }
    }

    private fun showPrinterNameKeypadDialog(
        title: String,
        message: String? = null,
        initialName: String,
        onSave: (String) -> Unit,
    ) {
        val dp = { v: Float ->
            (v * resources.displayMetrics.density).toInt()
        }

        val buffer = StringBuilder(initialName)

        val display = TextView(this).apply {
            text = buffer.toString()
            hint = getString(R.string.printer_name)
            textSize = 17f
            setPadding(dp(14f), dp(14f), dp(14f), dp(10f))
            setBackgroundResource(android.R.drawable.edit_text)
            minHeight = dp(48f)
        }

        val keypad = ReceiptEmailKeypadDialog.buildKeypadView(
            this,
            ReceiptEmailKeypadDialog.KeypadVariant.GUEST_NAME,
        ) { token ->
            when (token) {
                "⌫" -> if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                "SPACE" -> buffer.append(' ')
                else -> buffer.append(token)
            }
            display.text = buffer.toString()
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(4f))
            addView(display, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(keypad, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10f) })
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(inner, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .apply { if (!message.isNullOrBlank()) setMessage(message) }
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = buffer.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.printer_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSave(name)
            }
        }
        dialog.show()
    }

    private fun persistPrinter(name: String, printer: DetectedPrinter) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrinterFirestoreSync.mergeRegistrationFromDetected(db, printerType, name, printer)
                }
                SelectedPrinterPrefs.add(
                    this@AddPrinterActivity,
                    printerType,
                    name = name,
                    ipAddress = printer.ipAddress,
                    modelLine = printer.displayLabel,
                )
                Toast.makeText(this@AddPrinterActivity, R.string.printer_saved, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@AddPrinterActivity,
                    getString(R.string.printer_save_failed, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_PRINTER_TYPE = "printer_type"

        fun createIntent(context: Context, type: PrinterDeviceType): Intent =
            Intent(context, AddPrinterActivity::class.java).putExtra(EXTRA_PRINTER_TYPE, type.name)
    }
}

private fun isValidIpv4(s: String): Boolean {
    val parts = s.trim().split('.')
    if (parts.size != 4) return false
    for (p in parts) {
        if (p.isEmpty() || p.length > 3) return false
        val n = p.toIntOrNull() ?: return false
        if (n !in 0..255) return false
    }
    return true
}
