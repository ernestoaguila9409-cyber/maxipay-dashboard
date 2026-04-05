package com.ernesto.myapplication

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.tasks.await
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

    private val adapter by lazy {
        DiscoveredIpAdapter { printer -> showNameDialogAndSave(printer) }
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

        recyclerDiscovered.layoutManager = LinearLayoutManager(this)
        recyclerDiscovered.adapter = adapter

        btnAddManually.setOnClickListener { showManualIpDialog() }

        lifecycleScope.launch {
            ThermalPrinterScanner.clearCache()
            val found = try {
                ThermalPrinterScanner.scanSubnet10_0_0(context = applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
            showResults(found)
        }
    }

    private fun showResults(found: List<DetectedPrinter>) {
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
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.printer_name)
        }
        val edit = TextInputEditText(til.context).apply {
            setText(defaultPrinterName(printer))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        til.addView(edit)

        val infoLine = buildString {
            append(getString(R.string.save_printer_ip_message, printer.ipAddress))
            if (!printer.model.isNullOrBlank() || !printer.manufacturer.isNullOrBlank()) {
                append("\n")
                append(printer.displayLabel)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_printer)
            .setMessage(infoLine)
            .setView(til)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = edit.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.printer_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                persistPrinter(name, printer)
            }
        }
        dialog.show()
    }

    private fun persistPrinter(name: String, printer: DetectedPrinter) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = mutableMapOf<String, Any>(
                        "name" to name,
                        "ipAddress" to printer.ipAddress,
                        "type" to printerType.firestoreValue,
                        "isActive" to true,
                    )
                    printer.model?.let { data["model"] = it }
                    printer.manufacturer?.let { data["manufacturer"] = it }

                    db.collection(FIRESTORE_PRINTERS).add(data).await()
                }
                SelectedPrinterPrefs.save(
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
        private const val FIRESTORE_PRINTERS = "Printers"

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
