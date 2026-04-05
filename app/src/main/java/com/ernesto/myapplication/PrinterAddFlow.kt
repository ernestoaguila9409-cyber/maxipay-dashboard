package com.ernesto.myapplication

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun AppCompatActivity.showPrinterTypePickerThenOpenAddPrinter() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.select_printer_type)
        .setItems(
            arrayOf(
                getString(R.string.receipt_printer),
                getString(R.string.kitchen_printer),
            ),
        ) { _, which ->
            val type = if (which == 0) PrinterDeviceType.RECEIPT else PrinterDeviceType.KITCHEN
            startActivity(AddPrinterActivity.createIntent(this, type))
        }
        .show()
}
