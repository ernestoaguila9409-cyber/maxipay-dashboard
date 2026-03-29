package com.ernesto.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptOptionsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BT_CONNECT = 1002
    }

    private var orderId: String? = null
    private val db = FirebaseFirestore.getInstance()
    /** When customer email was passed in, merchant UI skips main options — don't mirror receipt grid on customer display. */
    private var skipCustomerReceiptMirror: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_options)

        orderId = intent.getStringExtra("ORDER_ID")
        val customerEmail = intent.getStringExtra("CUSTOMER_EMAIL")

        val optionsContainer = findViewById<LinearLayout>(R.id.optionsContainer)
        val emailFormContainer = findViewById<LinearLayout>(R.id.emailFormContainer)
        val etReceiptEmail = findViewById<EditText>(R.id.etReceiptEmail)

        if (!customerEmail.isNullOrBlank()) {
            skipCustomerReceiptMirror = true
            etReceiptEmail.setText(customerEmail)
            optionsContainer.visibility = View.GONE
            emailFormContainer.visibility = View.VISIBLE
        }

        findViewById<LinearLayout>(R.id.btnPrintReceipt).setOnClickListener {
            val oid = orderId
            if (oid.isNullOrBlank()) {
                Toast.makeText(this, "No order to print", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            printReceipt(oid)
        }

        findViewById<LinearLayout>(R.id.btnEmailReceipt).setOnClickListener {
            optionsContainer.visibility = View.GONE
            emailFormContainer.visibility = View.VISIBLE
            showEmailInputOnCustomerDisplay()
        }

        val smsFormContainer = findViewById<LinearLayout>(R.id.smsFormContainer)

        findViewById<LinearLayout>(R.id.btnSmsReceipt).setOnClickListener {
            optionsContainer.visibility = View.GONE
            smsFormContainer.visibility = View.VISIBLE
        }

        findViewById<LinearLayout>(R.id.btnSkipReceipt).setOnClickListener {
            goToMainScreen()
        }

        findViewById<Button>(R.id.btnSendReceipt).setOnClickListener {
            val email = findViewById<EditText>(R.id.etReceiptEmail).text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter an email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val oid = orderId ?: ""
            sendReceiptEmail(email, oid)
        }

        findViewById<Button>(R.id.btnBackToOptions).setOnClickListener {
            emailFormContainer.visibility = View.GONE
            optionsContainer.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnSendSms).setOnClickListener {
            val phone = findViewById<EditText>(R.id.etReceiptPhone).text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val oid = orderId ?: ""
            sendReceiptSms(phone, oid)
        }

        findViewById<Button>(R.id.btnBackFromSms).setOnClickListener {
            smsFormContainer.visibility = View.GONE
            optionsContainer.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        CustomerDisplayManager.attach(this)
        if (!skipCustomerReceiptMirror && CustomerDisplayManager.getPaymentSuccessInfo() != null) {
            CustomerDisplayManager.showReceiptOptionsOnCustomerDisplay(this) { option ->
                runOnUiThread { handleCustomerReceiptChoice(option) }
            }
        }
    }

    private fun handleCustomerReceiptChoice(option: ReceiptOption) {
        when (option) {
            ReceiptOption.PRINT -> findViewById<LinearLayout>(R.id.btnPrintReceipt).performClick()
            ReceiptOption.EMAIL -> showEmailInputOnCustomerDisplay()
            ReceiptOption.SMS -> findViewById<LinearLayout>(R.id.btnSmsReceipt).performClick()
            ReceiptOption.SKIP -> findViewById<LinearLayout>(R.id.btnSkipReceipt).performClick()
        }
    }

    private fun showEmailInputOnCustomerDisplay() {
        CustomerDisplayManager.showEmailInputOnCustomerDisplay(
            this,
            onSubmit = { email ->
                runOnUiThread {
                    val oid = orderId ?: ""
                    sendReceiptEmail(email, oid)
                }
            },
            onCancel = {
                runOnUiThread {
                    if (CustomerDisplayManager.getPaymentSuccessInfo() != null) {
                        CustomerDisplayManager.showReceiptOptionsOnCustomerDisplay(this) { option ->
                            runOnUiThread { handleCustomerReceiptChoice(option) }
                        }
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        CustomerDisplayManager.clearReceiptOptionCallback()
        CustomerDisplayManager.clearEmailInputCallbacks()
        super.onDestroy()
    }

    private fun goToMainScreen() {
        CustomerDisplayManager.clearPaymentSuccessInfo()
        CustomerDisplayManager.clearReceiptOptionCallback()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ============================
    // PRINT RECEIPT (ESC/POS)
    // ============================

    private fun printReceipt(orderId: String) {
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
        loadAndPrint(orderId)
    }

    private fun loadAndPrint(orderId: String) {
        Toast.makeText(this, "Preparing receipt…", Toast.LENGTH_SHORT).show()

        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                if (!orderDoc.exists()) {
                    Toast.makeText(this, "Order not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                db.collection("Orders").document(orderId)
                    .collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val saleId = orderDoc.getString("saleTransactionId")
                        if (saleId != null) {
                            db.collection("Transactions").document(saleId).get()
                                .addOnSuccessListener { txDoc ->
                                    @Suppress("UNCHECKED_CAST")
                                    val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                    val rs = ReceiptSettings.load(this)
                                    val segments = buildReceiptSegments(orderDoc, itemsSnap.documents, payments)
                                    EscPosPrinter.print(this, segments, rs)
                                    goToMainScreen()
                                }
                                .addOnFailureListener {
                                    val rs = ReceiptSettings.load(this)
                                    val segments = buildReceiptSegments(orderDoc, itemsSnap.documents, emptyList())
                                    EscPosPrinter.print(this, segments, rs)
                                    goToMainScreen()
                                }
                        } else {
                            val rs = ReceiptSettings.load(this)
                            val segments = buildReceiptSegments(orderDoc, itemsSnap.documents, emptyList())
                            EscPosPrinter.print(this, segments, rs)
                            goToMainScreen()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("PrintReceipt", "Failed to load items", e)
                        Toast.makeText(this, "Failed to load order items", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { e ->
                Log.e("PrintReceipt", "Failed to load order", e)
                Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildReceiptSegments(
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        payments: List<Map<String, Any>>
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(this)
        val segs = mutableListOf<EscPosPrinter.Segment>()

        val bn = rs.boldBizName;      val fn = rs.fontSizeBizName
        val ba = rs.boldAddress;      val fa = rs.fontSizeAddress
        val bo = rs.boldOrderInfo;    val fo = rs.fontSizeOrderInfo
        val bi = rs.boldItems;        val fi = rs.fontSizeItems
        val bt = rs.boldTotals;       val ft = rs.fontSizeTotals
        val bg = rs.boldGrandTotal;   val fg = rs.fontSizeGrandTotal
        val bf = rs.boldFooter;       val ff = rs.fontSizeFooter

        val lwi = ReceiptSettings.lineWidthForSize(fi)
        val lwt = ReceiptSettings.lineWidthForSize(ft)
        val lwg = ReceiptSettings.lineWidthForSize(fg)

        fun bizName(text: String) { segs += EscPosPrinter.Segment(text, bold = bn, fontSize = fn, centered = true) }
        fun address(text: String) { segs += EscPosPrinter.Segment(text, bold = ba, fontSize = fa, centered = true) }
        fun orderInfo(text: String) { segs += EscPosPrinter.Segment(text, bold = bo, fontSize = fo, centered = true) }
        fun item(text: String) { segs += EscPosPrinter.Segment(text, bold = bi, fontSize = fi) }
        fun total(text: String) { segs += EscPosPrinter.Segment(text, bold = bt, fontSize = ft) }
        fun grand(text: String) { segs += EscPosPrinter.Segment(text, bold = bg, fontSize = fg) }
        fun footer(text: String) { segs += EscPosPrinter.Segment(text, bold = bf, fontSize = ff, centered = true) }

        // ── Business Name ──
        bizName(rs.businessName)

        // ── Address ──
        for (line in rs.addressText.split("\n")) address(line)
        if (rs.showEmail && rs.email.isNotBlank()) {
            segs += EscPosPrinter.Segment(rs.email, bold = ba, fontSize = 0, centered = true)
        }
        segs += EscPosPrinter.Segment("")

        // ── Order Info (includes RECEIPT label) ──
        orderInfo("RECEIPT")
        orderInfo("")

        // ── Order Details ──
        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        val employeeName = orderDoc.getString("employeeName") ?: ""
        val customerName = orderDoc.getString("customerName") ?: ""
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        orderInfo("Order #$orderNumber")
        if (orderType.isNotBlank()) {
            val typeLabel = when (orderType) {
                "DINE_IN" -> "Dine In"
                "TO_GO" -> "To Go"
                "BAR_TAB" -> "Bar Tab"
                else -> orderType
            }
            orderInfo("Type: $typeLabel")
        }
        if (rs.showServerName && employeeName.isNotBlank()) orderInfo("Server: $employeeName")
        if (customerName.isNotBlank()) orderInfo("Customer: $customerName")
        if (rs.showDateTime) orderInfo("Date: $dateStr")
        segs += EscPosPrinter.Segment("")

        // ── Items ──
        item("-".repeat(lwi))
        for (doc in items) {
            val name = doc.getString("name")
                ?: doc.getString("itemName")
                ?: "Item"
            val qty = (doc.getLong("qty")
                ?: doc.getLong("quantity")
                ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents
            val itemLabel = if (qty > 1) "${qty}x $name" else name

            if (basePriceCents > 0L) {
                item(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), lwi))
            } else {
                item(itemLabel)
            }

            @Suppress("UNCHECKED_CAST")
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            for (mod in mods) {
                val modName = mod["name"]?.toString() ?: continue
                val modAction = mod["action"]?.toString() ?: "ADD"
                val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                val modCents = kotlin.math.round(modPrice * 100).toLong()
                if (modAction == "REMOVE") {
                    item("  NO $modName")
                } else if (modCents > 0) {
                    item(formatLine("  + $modName", MoneyUtils.centsToDisplay(modCents), lwi))
                } else {
                    item("  + $modName")
                }
            }
        }
        item("-".repeat(lwi))
        segs += EscPosPrinter.Segment("")

        // ── Totals ──
        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L

        @Suppress("UNCHECKED_CAST")
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) {
            taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
        }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        total(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), lwt))

        @Suppress("UNCHECKED_CAST")
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val groupedDiscounts = DiscountDisplay.groupByName(appliedDiscounts)
        if (groupedDiscounts.isNotEmpty()) {
            for (gd in groupedDiscounts) {
                val label = DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                total(formatLine(label, "-${MoneyUtils.centsToDisplay(gd.totalCents)}", lwt))
            }
        } else if (discountInCents > 0L) {
            total(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", lwt))
        }

        for (entry in taxBreakdown) {
            val name = entry["name"]?.toString() ?: "Tax"
            val amountCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val tRate = (entry["rate"] as? Number)?.toDouble()
            val tType = entry["taxType"]?.toString()
            val tLabel = DiscountDisplay.formatTaxLabel(name, tType, tRate)
            total(formatLine(tLabel, MoneyUtils.centsToDisplay(amountCents), lwt))
        }
        if (tipAmountInCents > 0L) {
            total(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt))
        }
        total("=".repeat(lwt))

        // ── Grand Total ──
        grand(formatLine("TOTAL", MoneyUtils.centsToDisplay(totalInCents), lwg))
        segs += EscPosPrinter.Segment("")

        // ── Payment Info ──
        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                footer("Paid with Cash")
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val last4 = p["last4"]?.toString() ?: ""
                val authCode = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || last4.isNotBlank()) {
                    val cardLine = buildString {
                        if (brand.isNotBlank()) append(brand)
                        if (last4.isNotBlank()) {
                            if (isNotEmpty()) append(" ")
                            append("**** $last4")
                        }
                    }
                    footer(cardLine)
                }
                if (authCode.isNotBlank()) {
                    footer("Auth: $authCode")
                }
                if (pType.isNotBlank()) {
                    footer("Type: $pType")
                }
            }
            segs += EscPosPrinter.Segment("")
        }

        // ── Footer ──
        footer("Thank you for dining with us!")

        return segs
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_CONNECT && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val oid = orderId
            if (!oid.isNullOrBlank()) loadAndPrint(oid)
        }
    }

    // ============================
    // EMAIL RECEIPT
    // ============================

    // ============================
    // SMS RECEIPT
    // ============================

    private fun sendReceiptSms(phone: String, orderId: String) {
        var cleaned = phone.replace(Regex("[\\s\\-()]"), "")
        if (cleaned.length == 10 && !cleaned.startsWith("+")) {
            cleaned = "+1$cleaned"
        } else if (cleaned.startsWith("1") && cleaned.length == 11) {
            cleaned = "+$cleaned"
        }

        if (!cleaned.matches(Regex("^\\+1\\d{10}$"))) {
            Toast.makeText(this, "Invalid phone number. Use format: +1XXXXXXXXXX", Toast.LENGTH_LONG).show()
            return
        }

        val functions = FirebaseFunctions.getInstance()
        val data = hashMapOf(
            "phone" to cleaned,
            "orderId" to orderId
        )

        val btnSend = findViewById<Button>(R.id.btnSendSms)
        btnSend.isEnabled = false
        btnSend.text = "Sending…"

        functions
            .getHttpsCallable("sendReceiptSms")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Log.d("Receipt", "SMS sent successfully")
                    Toast.makeText(this, "Receipt texted to $cleaned", Toast.LENGTH_SHORT).show()
                    goToMainScreen()
                } else {
                    val errorMsg = response?.get("error")?.toString() ?: "Unknown error"
                    Log.e("Receipt", "SMS function returned failure: $response")
                    Toast.makeText(this, "Failed to send SMS: $errorMsg", Toast.LENGTH_LONG).show()
                    btnSend.isEnabled = true
                    btnSend.text = "Send Text"
                }
            }
            .addOnFailureListener { e ->
                Log.e("Receipt", "Error calling sendReceiptSms", e)
                Toast.makeText(this, "Failed to send SMS: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                btnSend.isEnabled = true
                btnSend.text = "Send Text"
            }
    }

    // ============================
    // EMAIL RECEIPT
    // ============================

    private fun sendReceiptEmail(email: String, orderId: String) {
        val functions = FirebaseFunctions.getInstance()

        val data = hashMapOf(
            "email" to email,
            "orderId" to orderId
        )

        val btnSend = findViewById<Button>(R.id.btnSendReceipt)
        btnSend.isEnabled = false
        btnSend.text = "Sending…"

        functions
            .getHttpsCallable("sendReceiptEmail")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Log.d("Receipt", "Email sent successfully")
                    Toast.makeText(this, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                    goToMainScreen()
                } else {
                    val errorMsg = response?.get("error")?.toString() ?: "Unknown error"
                    Log.e("Receipt", "Cloud function returned failure: $response")
                    Toast.makeText(this, "Failed to send receipt: $errorMsg", Toast.LENGTH_LONG).show()
                    btnSend.isEnabled = true
                    btnSend.text = "Send Receipt"
                }
            }
            .addOnFailureListener { e ->
                Log.e("Receipt", "Error calling sendReceiptEmail", e)
                Toast.makeText(this, "Failed to send receipt: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                btnSend.isEnabled = true
                btnSend.text = "Send Receipt"
            }
    }
}
