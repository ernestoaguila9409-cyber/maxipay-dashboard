package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptOptionsActivity : AppCompatActivity() {

    private var orderId: String? = null
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_options)

        orderId = intent.getStringExtra("ORDER_ID")
        val customerEmail = intent.getStringExtra("CUSTOMER_EMAIL")

        val optionsContainer = findViewById<LinearLayout>(R.id.optionsContainer)
        val emailFormContainer = findViewById<LinearLayout>(R.id.emailFormContainer)
        val etReceiptEmail = findViewById<EditText>(R.id.etReceiptEmail)

        if (!customerEmail.isNullOrBlank()) {
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
    }

    private fun goToMainScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ============================
    // PRINT RECEIPT
    // ============================

    private data class PrintSegment(val text: String, val fontSize: Int, val alignment: Int)

    private fun printReceipt(orderId: String) {
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
                        val segments = buildReceiptSegments(orderDoc, itemsSnap.documents)
                        sendToPrinter(segments)
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

    private fun buildReceiptSegments(
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>
    ): List<PrintSegment> {
        val rs = ReceiptSettings.load(this)
        val segments = mutableListOf<PrintSegment>()
        val c = ALIGN_CENTER

        fun centered(text: String) { segments.add(PrintSegment(text, 0, c)) }
        fun left(text: String) { segments.add(PrintSegment(text, 0, 0)) }

        // ── Header ──
        centered(rs.businessName)
        for (line in rs.addressText.split("\n")) {
            centered(line)
        }

        centered("")
        centered("RECEIPT")
        centered("")

        // ── Order Info ──
        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        val employeeName = orderDoc.getString("employeeName") ?: ""
        val customerName = orderDoc.getString("customerName") ?: ""
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        centered("Order #$orderNumber")
        if (orderType.isNotBlank()) {
            val typeLabel = when (orderType) {
                "DINE_IN" -> "Dine In"
                "TO_GO" -> "To Go"
                "BAR_TAB" -> "Bar Tab"
                else -> orderType
            }
            centered("Type: $typeLabel")
        }
        if (rs.showServerName && employeeName.isNotBlank()) centered("Server: $employeeName")
        if (customerName.isNotBlank()) centered("Customer: $customerName")
        if (rs.showDateTime) centered("Date: $dateStr")
        centered("")

        // ── Items ──
        left("-".repeat(LINE_WIDTH))
        for (doc in items) {
            val name = doc.getString("name")
                ?: doc.getString("itemName")
                ?: "Item"
            val qty = (doc.getLong("qty")
                ?: doc.getLong("quantity")
                ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val itemLabel = if (qty > 1) "${qty}x $name" else name
            left(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), LINE_WIDTH))

            @Suppress("UNCHECKED_CAST")
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            for (mod in mods) {
                val modName = mod["name"]?.toString() ?: continue
                val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                val modCents = kotlin.math.round(modPrice * 100).toLong()
                val modLabel = "  + $modName"
                if (modCents > 0) {
                    left(formatLine(modLabel, MoneyUtils.centsToDisplay(modCents), LINE_WIDTH))
                } else {
                    left(modLabel)
                }
            }
        }
        left("-".repeat(LINE_WIDTH))

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

        centered("")
        left(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), LINE_WIDTH))
        if (discountInCents > 0L) {
            left(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", LINE_WIDTH))
        }
        for (entry in taxBreakdown) {
            val name = entry["name"]?.toString() ?: "Tax"
            val amountCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            left(formatLine(name, MoneyUtils.centsToDisplay(amountCents), LINE_WIDTH))
        }
        if (tipAmountInCents > 0L) {
            left(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), LINE_WIDTH))
        }
        left("=".repeat(LINE_WIDTH))

        // ── Grand Total ──
        centered("")
        left(formatLine("TOTAL", MoneyUtils.centsToDisplay(totalInCents), LINE_WIDTH))

        // ── Footer ──
        centered("")
        centered("Thank you for dining with us!")

        return segments
    }

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
                        runOnUiThread { Toast.makeText(this@ReceiptOptionsActivity, "Printer not available", Toast.LENGTH_LONG).show() }
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

                    // OmniDriver addText: addText(fontSize, alignment, text)
                    for (seg in segments) {
                        addText.invoke(printerProxy, seg.fontSize, seg.alignment, seg.text + "\n")
                    }

                    printerProxy.javaClass.getMethod("feedLine", Int::class.javaPrimitiveType)
                        .invoke(printerProxy, 10)

                    val listenerStubClass = loader.loadClass("com.sdksuite.omnidriver.aidl.printer.OnPrintListener\$Stub")
                    val listenerInstance = object : android.os.Binder() {
                        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                            Log.d("PrintReceipt", "PrintListener onTransact code=$code")
                            return try { super.onTransact(code, data, reply, flags) } catch (e: Exception) { reply?.writeNoException(); true }
                        }
                    }
                    val listenerInterface = loader.loadClass("com.sdksuite.omnidriver.aidl.printer.OnPrintListener")
                    val listenerProxy = listenerStubClass.getMethod("asInterface", android.os.IBinder::class.java)
                        .invoke(null, listenerInstance)

                    printerProxy.javaClass.getMethod("startPrint", listenerInterface)
                        .invoke(printerProxy, listenerProxy)

                    Log.d("PrintReceipt", "Receipt print started")
                    runOnUiThread {
                        Toast.makeText(this@ReceiptOptionsActivity, "Receipt printed!", Toast.LENGTH_SHORT).show()
                        goToMainScreen()
                    }
                } catch (e: Exception) {
                    Log.e("PrintReceipt", "Print error: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@ReceiptOptionsActivity, "Print failed: ${e.cause?.message ?: e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                Log.d("PrintReceipt", "OmniDriver disconnected")
            }
        }, android.content.Context.BIND_AUTO_CREATE)
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
                    Log.e("Receipt", "Cloud function returned failure: $response")
                    Toast.makeText(this, "Failed to send receipt", Toast.LENGTH_SHORT).show()
                    btnSend.isEnabled = true
                    btnSend.text = "Send Receipt"
                }
            }
            .addOnFailureListener { e ->
                Log.e("Receipt", "Error sending email", e)
                Toast.makeText(this, "Failed to send receipt. Please try again.", Toast.LENGTH_SHORT).show()
                btnSend.isEnabled = true
                btnSend.text = "Send Receipt"
            }
    }
}
