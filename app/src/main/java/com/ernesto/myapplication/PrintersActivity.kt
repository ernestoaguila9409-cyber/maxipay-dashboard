package com.ernesto.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class PrintersActivity : AppCompatActivity() {

    private lateinit var txtEmptyHint: TextView
    private lateinit var cardReceipt: MaterialCardView
    private lateinit var cardKitchen: MaterialCardView
    private lateinit var txtReceiptName: TextView
    private lateinit var txtReceiptIp: TextView
    private lateinit var txtReceiptInfo: TextView
    private lateinit var txtKitchenName: TextView
    private lateinit var txtKitchenIp: TextView
    private lateinit var txtKitchenInfo: TextView
    private lateinit var btnTestReceipt: MaterialButton
    private lateinit var btnTestKitchen: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printers)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Printers"

        txtEmptyHint = findViewById(R.id.txtPrintersEmptyHint)
        cardReceipt = findViewById(R.id.cardReceiptPrinter)
        cardKitchen = findViewById(R.id.cardKitchenPrinter)
        txtReceiptName = findViewById(R.id.txtReceiptPrinterName)
        txtReceiptIp = findViewById(R.id.txtReceiptPrinterIp)
        txtReceiptInfo = findViewById(R.id.txtReceiptPrinterInfo)
        txtKitchenName = findViewById(R.id.txtKitchenPrinterName)
        txtKitchenIp = findViewById(R.id.txtKitchenPrinterIp)
        txtKitchenInfo = findViewById(R.id.txtKitchenPrinterInfo)
        btnTestReceipt = findViewById(R.id.btnTestReceiptPrinter)
        btnTestKitchen = findViewById(R.id.btnTestKitchenPrinter)

        findViewById<FloatingActionButton>(R.id.fabPrinters).setOnClickListener {
            showPrinterTypePickerThenOpenAddPrinter()
        }
    }

    override fun onResume() {
        super.onResume()
        bindSelectedPrinters()
    }

    private fun bindSelectedPrinters() {
        val receipt = SelectedPrinterPrefs.get(this, PrinterDeviceType.RECEIPT)
        val kitchen = SelectedPrinterPrefs.get(this, PrinterDeviceType.KITCHEN)

        if (receipt != null) {
            cardReceipt.visibility = View.VISIBLE
            txtReceiptName.text = receipt.name
            txtReceiptIp.text = receipt.ipAddress
            txtReceiptInfo.text = receipt.modelLine
            txtReceiptInfo.visibility =
                if (receipt.modelLine.isBlank()) View.GONE else View.VISIBLE
            btnTestReceipt.setOnClickListener {
                EscPosPrinter.printLanTestPrint(
                    this,
                    ipAddress = receipt.ipAddress,
                    kitchenPrinter = false,
                )
            }
        } else {
            cardReceipt.visibility = View.GONE
            btnTestReceipt.setOnClickListener(null)
        }

        if (kitchen != null) {
            cardKitchen.visibility = View.VISIBLE
            txtKitchenName.text = kitchen.name
            txtKitchenIp.text = kitchen.ipAddress
            txtKitchenInfo.text = kitchen.modelLine
            txtKitchenInfo.visibility =
                if (kitchen.modelLine.isBlank()) View.GONE else View.VISIBLE
            btnTestKitchen.setOnClickListener {
                EscPosPrinter.printLanTestPrint(
                    this,
                    ipAddress = kitchen.ipAddress,
                    kitchenPrinter = true,
                )
            }
        } else {
            cardKitchen.visibility = View.GONE
            btnTestKitchen.setOnClickListener(null)
        }

        txtEmptyHint.visibility =
            if (receipt == null && kitchen == null) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
