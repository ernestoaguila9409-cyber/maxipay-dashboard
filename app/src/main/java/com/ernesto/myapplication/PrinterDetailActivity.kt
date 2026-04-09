package com.ernesto.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrinterDetailActivity : AppCompatActivity() {

    private lateinit var txtDetailType: TextView
    private lateinit var txtDetailStatus: TextView
    private lateinit var txtDetailName: TextView
    private lateinit var txtDetailIp: TextView
    private lateinit var txtDetailModel: TextView
    private lateinit var cardLabelsSection: MaterialCardView
    private lateinit var chipGroup: com.google.android.material.chip.ChipGroup

    private var deviceType: PrinterDeviceType = PrinterDeviceType.KITCHEN
    private var ipAddress: String = ""
    private var printerName: String = ""
    private var modelLine: String = ""

    private val labelList: MutableList<String> = mutableListOf()
    private var statusJob: Job? = null
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val printerPrefsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SelectedPrinterPrefs.ACTION_PRINTERS_PREFS_CHANGED) return
            if (isFinishing) return
            val removed = intent.getStringExtra(SelectedPrinterPrefs.EXTRA_REMOVED_PRINTER_IP) ?: return
            if (removed == ipAddress.trim()) {
                Toast.makeText(this@PrinterDetailActivity, R.string.printer_removed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.printer_detail_title)

        deviceType = when (intent.getStringExtra(EXTRA_DEVICE_TYPE)) {
            PrinterDeviceType.RECEIPT.name -> PrinterDeviceType.RECEIPT
            else -> PrinterDeviceType.KITCHEN
        }
        ipAddress = intent.getStringExtra(EXTRA_IP)?.trim().orEmpty()
        printerName = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty()
        modelLine = intent.getStringExtra(EXTRA_MODEL)?.trim().orEmpty()

        if (ipAddress.isEmpty()) {
            finish()
            return
        }

        val saved = SelectedPrinterPrefs.getAll(this, deviceType)
            .find { it.ipAddress.trim() == ipAddress }
        if (saved != null) {
            printerName = saved.name
            modelLine = saved.modelLine
            labelList.clear()
            labelList.addAll(saved.labels)
        }

        txtDetailType = findViewById(R.id.txtDetailType)
        txtDetailStatus = findViewById(R.id.txtDetailStatus)
        txtDetailName = findViewById(R.id.txtDetailName)
        txtDetailIp = findViewById(R.id.txtDetailIp)
        txtDetailModel = findViewById(R.id.txtDetailModel)
        cardLabelsSection = findViewById(R.id.cardLabelsSection)
        chipGroup = findViewById(R.id.chipGroupPrinterLabels)

        txtDetailType.setText(
            when (deviceType) {
                PrinterDeviceType.RECEIPT -> R.string.receipt_printer
                PrinterDeviceType.KITCHEN -> R.string.kitchen_printer
            },
        )
        txtDetailStatus.setText(R.string.printer_status_checking)
        txtDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.printer_status_checking))
        txtDetailName.text = printerName.ifBlank { getString(R.string.unknown_printer) }
        txtDetailIp.text = getString(R.string.printer_detail_ip_line, ipAddress)
        if (modelLine.isNotBlank()) {
            txtDetailModel.text = modelLine
            txtDetailModel.visibility = android.view.View.VISIBLE
        }

        val kitchen = deviceType == PrinterDeviceType.KITCHEN
        cardLabelsSection.visibility = android.view.View.VISIBLE

        findViewById<MaterialButton>(R.id.btnDetailTestPrint).setOnClickListener {
            if (kitchen) {
                val style = PrinterKitchenStyleCache.styleForIp(ipAddress)
                val cmdSet = PrinterKitchenStyleCache.commandSetForIp(ipAddress)
                val demoLabel = SelectedPrinterPrefs.getAll(this, deviceType)
                    .find { it.ipAddress.trim() == ipAddress.trim() }
                    ?.labels
                    ?.firstOrNull()
                val title = printerName.trim().ifBlank { getString(R.string.unknown_printer) }
                val segments = KitchenTicketBuilder.buildSampleDemoTicketSegments(
                    printerTitle = title,
                    style = style,
                    demoRoutingLabel = demoLabel,
                )
                EscPosPrinter.printKitchenChitToLan(
                    this,
                    ipAddress = ipAddress,
                    segments = segments,
                    commandSet = cmdSet,
                    showSuccessToast = true,
                )
            } else {
                EscPosPrinter.printLanTestPrint(
                    this,
                    ipAddress = ipAddress,
                    kitchenPrinter = false,
                )
            }
        }

        findViewById<ImageButton>(R.id.btnEditName).setOnClickListener {
            showEditNameDialog()
        }

        findViewById<MaterialButton>(R.id.btnDetailDeletePrinter).setOnClickListener {
            showDeletePrinterConfirm()
        }

        findViewById<MaterialButton>(R.id.btnAddPrinterLabel).setOnClickListener {
            showAddLabelDialog()
        }

        bindLabelChips()
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
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            while (isActive) {
                val online = withContext(Dispatchers.IO) { isPrinterOnline(ipAddress) }
                if (!isFinishing) applyStatus(online)
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        super.onPause()
    }

    private fun applyStatus(online: Boolean) {
        if (online) {
            txtDetailStatus.setText(R.string.printer_status_online)
            txtDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.printer_status_online))
        } else {
            txtDetailStatus.setText(R.string.printer_status_offline)
            txtDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.printer_status_offline))
        }
    }

    private fun bindLabelChips() {
        chipGroup.removeAllViews()
        for (label in labelList.toList()) {
            val chip = Chip(this).apply {
                text = label
                isCloseIconVisible = true
            }
            chip.setOnCloseIconClickListener {
                labelList.remove(label)
                persistLabels()
                bindLabelChips()
            }
            chipGroup.addView(chip)
        }
    }

    private fun showAddLabelDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.printer_detail_label_hint)
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.printer_detail_add_label)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val key = PrinterLabelKey.normalize(raw)
                if (labelList.any { PrinterLabelKey.normalize(it) == key }) {
                    android.widget.Toast.makeText(
                        this,
                        R.string.printer_detail_label_duplicate,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                labelList.add(raw)
                persistLabels()
                bindLabelChips()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persistLabels() {
        SelectedPrinterPrefs.updateLabelsForIp(this, deviceType, ipAddress, labelList)
        KitchenRoutingLabelsFirestore.mergeLabelsIntoFirestore(
            FirebaseFirestore.getInstance(),
            labelList,
        )
        val display = SelectedPrinterDisplay(
            name = printerName,
            ipAddress = ipAddress,
            modelLine = modelLine,
            labels = labelList.toList(),
        )
        PrinterFirestoreSync.mergeRegistration(db, deviceType, display, includeLabels = true)
    }

    private fun showEditNameDialog() {
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.printer_name)
        }
        val edit = TextInputEditText(til.context).apply {
            setText(printerName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        til.addView(edit)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.printer_detail_edit_name_title)
            .setView(til)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            edit.selectAll()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val newName = edit.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.printer_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                applyNewName(newName)
            }
        }
        dialog.show()
    }

    private fun applyNewName(newName: String) {
        printerName = newName
        txtDetailName.text = newName

        SelectedPrinterPrefs.add(
            this, deviceType,
            name = newName,
            ipAddress = ipAddress,
            modelLine = modelLine,
        )

        val display = SelectedPrinterDisplay(
            name = newName,
            ipAddress = ipAddress,
            modelLine = modelLine,
            labels = labelList.toList(),
        )
        PrinterFirestoreSync.mergeRegistration(db, deviceType, display, includeLabels = true)

        Toast.makeText(this, R.string.printer_name_updated, Toast.LENGTH_SHORT).show()
    }

    private fun showDeletePrinterConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.printer_detail_delete)
            .setMessage(R.string.printer_detail_delete_confirm)
            .setPositiveButton(R.string.printer_detail_delete_action) { _, _ -> deletePrinterFromDeviceAndCloud() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePrinterFromDeviceAndCloud() {
        PrinterFirestoreSync.deleteAllDocumentsForIp(db, ipAddress) { err ->
            if (isFinishing) return@deleteAllDocumentsForIp
            if (err != null) {
                Toast.makeText(
                    this,
                    getString(R.string.printer_detail_delete_failed, err.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
                return@deleteAllDocumentsForIp
            }
            SelectedPrinterPrefs.removePrinterByIp(this, ipAddress, broadcastChange = false)
            Toast.makeText(this, R.string.printer_removed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_IP = "ip"
        const val EXTRA_NAME = "name"
        const val EXTRA_MODEL = "model"

        private const val STATUS_INTERVAL_MS = 5_000L
    }
}
