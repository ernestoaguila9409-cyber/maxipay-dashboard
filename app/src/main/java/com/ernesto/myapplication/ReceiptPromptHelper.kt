package com.ernesto.myapplication

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptPromptHelper {

    private const val REQUEST_BT_CONNECT_PROMPT = 1099

    enum class ReceiptType(
        val title: String,
        val message: String,
        val cloudFunction: String,
        val label: String
    ) {
        VOID(
            "Transaction Voided",
            "The transaction was voided successfully.\nSend a VOID receipt?",
            "sendVoidReceiptEmail",
            "VOID"
        ),
        REFUND(
            "Refund Completed",
            "The refund was processed successfully.\nSend a REFUND receipt?",
            "sendRefundReceiptEmail",
            "REFUND"
        )
    }

    fun promptForReceipt(
        activity: Activity,
        type: ReceiptType,
        orderId: String,
        transactionId: String = "",
        onDismiss: (() -> Unit)? = null
    ) {
        val options = arrayOf(
            "\uD83D\uDDA8\uFE0F  Print Receipt",
            "\u2709\uFE0F  Email Receipt",
            "No"
        )
        AlertDialog.Builder(activity)
            .setTitle(type.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> printReceiptFromPrompt(activity, type, orderId, transactionId, onDismiss)
                    1 -> showEmailInput(activity, type, orderId, transactionId, onDismiss)
                    else -> onDismiss?.invoke()
                }
            }
            .setCancelable(false)
            .show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun printReceiptFromPrompt(
        activity: Activity,
        type: ReceiptType,
        orderId: String,
        transactionId: String,
        onDismiss: (() -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_CONNECT_PROMPT
                )
                onDismiss?.invoke()
                return
            }
        }
        Toast.makeText(activity, "Preparing receipt\u2026", Toast.LENGTH_SHORT).show()
        val db = FirebaseFirestore.getInstance()

        if (type == ReceiptType.REFUND && orderId.isNotBlank() && transactionId.isNotBlank()) {
            db.collection("Orders").document(orderId).get()
                .addOnSuccessListener { orderDoc ->
                    if (!orderDoc.exists()) { onDismiss?.invoke(); return@addOnSuccessListener }
                    db.collection("Orders").document(orderId).collection("items").get()
                        .addOnSuccessListener { itemsSnap ->
                            db.collection("Transactions")
                                .whereEqualTo("type", "REFUND")
                                .whereEqualTo("originalReferenceId", transactionId)
                                .get()
                                .addOnSuccessListener { refundSnap ->
                                    val rs = ReceiptSettings.load(activity)
                                    db.collection("Transactions").document(transactionId).get()
                                        .addOnSuccessListener { txDoc ->
                                            @Suppress("UNCHECKED_CAST")
                                            val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                            val segs = buildDetailedRefundSegments(activity, orderDoc, itemsSnap.documents, refundSnap.documents, payments)
                                            EscPosPrinter.print(activity, segs, rs)
                                            onDismiss?.invoke()
                                        }
                                        .addOnFailureListener {
                                            val segs = buildDetailedRefundSegments(activity, orderDoc, itemsSnap.documents, refundSnap.documents, emptyList())
                                            EscPosPrinter.print(activity, segs, rs)
                                            onDismiss?.invoke()
                                        }
                                }
                                .addOnFailureListener {
                                    printSimpleLabelReceipt(activity, type.label, orderId, onDismiss)
                                }
                        }
                        .addOnFailureListener { printSimpleLabelReceipt(activity, type.label, orderId, onDismiss) }
                }
                .addOnFailureListener { printSimpleLabelReceipt(activity, type.label, orderId, onDismiss) }
        } else if (type == ReceiptType.VOID && orderId.isNotBlank()) {
            db.collection("Orders").document(orderId).get()
                .addOnSuccessListener { orderDoc ->
                    if (!orderDoc.exists()) { printSimpleLabelReceipt(activity, type.label, orderId, onDismiss); return@addOnSuccessListener }
                    db.collection("Orders").document(orderId).collection("items").get()
                        .addOnSuccessListener { itemsSnap ->
                            val rsVoid = ReceiptSettings.load(activity)
                            val fetchPayments: (List<Map<String, Any>>) -> Unit = { payments ->
                                val segs = buildDetailedVoidSegments(activity, orderDoc, itemsSnap.documents, payments)
                                EscPosPrinter.print(activity, segs, rsVoid)
                                onDismiss?.invoke()
                            }
                            if (transactionId.isNotBlank()) {
                                db.collection("Transactions").document(transactionId).get()
                                    .addOnSuccessListener { txDoc ->
                                        @Suppress("UNCHECKED_CAST")
                                        val payments = txDoc?.get("payments") as? List<Map<String, Any>> ?: emptyList()
                                        fetchPayments(payments)
                                    }
                                    .addOnFailureListener { fetchPayments(emptyList()) }
                            } else {
                                fetchPayments(emptyList())
                            }
                        }
                        .addOnFailureListener { printSimpleLabelReceipt(activity, type.label, orderId, onDismiss) }
                }
                .addOnFailureListener { printSimpleLabelReceipt(activity, type.label, orderId, onDismiss) }
        } else {
            printSimpleLabelReceipt(activity, type.label, orderId, onDismiss)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedRefundSegments(
        activity: Activity,
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        refundDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        payments: List<Map<String, Any>> = emptyList()
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(activity)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("REFUND RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)

        val refundedByName = refundDocs.firstOrNull()?.getString("refundedBy")?.trim()?.takeIf { it.isNotBlank() }
        if (refundedByName != null) {
            segs += EscPosPrinter.Segment("Refunded by: $refundedByName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        segs += EscPosPrinter.Segment("")

        val itemById = items.associateBy { it.id }
        val itemByName = items.groupBy { (it.getString("name") ?: it.getString("itemName") ?: "").trim() }

        data class RefundedItem(val name: String, val qty: Int, val amountCents: Long, val baseCents: Long, val taxBreakdown: List<Map<String, Any>>)
        val refundedItems = mutableListOf<RefundedItem>()
        var totalRefundCents = 0L

        for (refDoc in refundDocs) {
            val refAmountCents = refDoc.getLong("amountInCents")
                ?: ((refDoc.getDouble("amount") ?: 0.0) * 100).toLong()
            totalRefundCents += refAmountCents
            val lineKey = refDoc.getString("refundedLineKey")?.takeIf { it.isNotBlank() }
            val itemName = refDoc.getString("refundedItemName")?.trim()?.takeIf { it.isNotBlank() }

            val matchedItem = if (lineKey != null) itemById[lineKey]
            else if (itemName != null) itemByName[itemName]?.firstOrNull()
            else null

            if (matchedItem != null) {
                val name = matchedItem.getString("name") ?: matchedItem.getString("itemName") ?: "Item"
                val qty = (matchedItem.getLong("qty") ?: matchedItem.getLong("quantity") ?: 1L).toInt()
                val storedTaxBreakdown = matchedItem.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                val lineTotalInCents = matchedItem.getLong("lineTotalInCents") ?: refAmountCents
                refundedItems.add(RefundedItem(name, qty, refAmountCents, lineTotalInCents, storedTaxBreakdown))
            } else if (itemName != null) {
                refundedItems.add(RefundedItem(itemName, 1, refAmountCents, refAmountCents, emptyList()))
            } else {
                for (item in items) {
                    val name = item.getString("name") ?: item.getString("itemName") ?: "Item"
                    val qty = (item.getLong("qty") ?: item.getLong("quantity") ?: 1L).toInt()
                    val lineCents = item.getLong("lineTotalInCents") ?: 0L
                    val storedTaxBreakdown = item.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
                    refundedItems.add(RefundedItem(name, qty, lineCents, lineCents, storedTaxBreakdown))
                }
            }
        }

        segs += EscPosPrinter.Segment("Refunded Items:", bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (ri in refundedItems) {
            val label = if (ri.qty > 1) "${ri.name} x${ri.qty}" else ri.name
            segs += EscPosPrinter.Segment(
                formatLine(label, MoneyUtils.centsToDisplay(ri.baseCents), lwi),
                bold = rs.boldItems, fontSize = rs.fontSizeItems
            )
        }
        segs += EscPosPrinter.Segment("")

        val taxGroupMap = mutableMapOf<String, Triple<String, Double, Long>>()
        for (ri in refundedItems) {
            for (tax in ri.taxBreakdown) {
                val taxName = tax["name"]?.toString() ?: continue
                val taxRate = (tax["rate"] as? Number)?.toDouble() ?: 0.0
                val taxAmount = (tax["amountInCents"] as? Number)?.toLong() ?: 0L
                val existing = taxGroupMap[taxName]
                if (existing != null) {
                    taxGroupMap[taxName] = Triple(taxName, existing.second, existing.third + taxAmount)
                } else {
                    taxGroupMap[taxName] = Triple(taxName, taxRate, taxAmount)
                }
            }
        }

        if (taxGroupMap.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val orderTaxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
            val orderSubtotalCents = items.sumOf { it.getLong("lineTotalInCents") ?: 0L }
            val refundedBaseCents = refundedItems.sumOf { it.baseCents }
            for (tax in orderTaxBreakdown) {
                val taxName = tax["name"]?.toString() ?: continue
                val taxRate = (tax["rate"] as? Number)?.toDouble() ?: 0.0
                val orderTaxAmount = (tax["amountInCents"] as? Number)?.toLong() ?: 0L
                val prorated = if (taxRate > 0) {
                    Math.round(refundedBaseCents * taxRate / 100.0)
                } else if (orderSubtotalCents > 0) {
                    Math.round(orderTaxAmount.toDouble() * refundedBaseCents / orderSubtotalCents)
                } else {
                    orderTaxAmount
                }
                if (prorated > 0L) taxGroupMap[taxName] = Triple(taxName, taxRate, prorated)
            }
        }

        if (taxGroupMap.isNotEmpty()) {
            segs += EscPosPrinter.Segment("Taxes Refunded:", bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            segs += EscPosPrinter.Segment("-".repeat(lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            for ((_, group) in taxGroupMap) {
                val (name, rate, totalAmount) = group
                val pctStr = if (rate > 0) {
                    if (rate % 1.0 == 0.0) "${rate.toInt()}%" else String.format(Locale.US, "%.2f%%", rate)
                } else ""
                val taxLabel = if (pctStr.isNotBlank()) "$name ($pctStr)" else name
                segs += EscPosPrinter.Segment(
                    formatLine(taxLabel, MoneyUtils.centsToDisplay(totalAmount), lwt),
                    bold = rs.boldTotals, fontSize = rs.fontSizeTotals
                )
            }
            segs += EscPosPrinter.Segment("")
        }

        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("TOTAL REFUND", "-${MoneyUtils.centsToDisplay(totalRefundCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val l4 = p["last4"]?.toString() ?: ""
                val auth = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || l4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (brand.isNotBlank()) append(brand); if (l4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** $l4") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (auth.isNotBlank()) segs += EscPosPrinter.Segment("Auth: $auth", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDetailedVoidSegments(
        activity: Activity,
        orderDoc: com.google.firebase.firestore.DocumentSnapshot,
        items: List<com.google.firebase.firestore.DocumentSnapshot>,
        payments: List<Map<String, Any>>
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(activity)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val lwi = ReceiptSettings.lineWidthForSize(rs.fontSizeItems)
        val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
        val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

        segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        for (line in rs.addressText.split("\n")) {
            segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        segs += EscPosPrinter.Segment("")
        segs += EscPosPrinter.Segment("VOID RECEIPT", bold = true, fontSize = 2, centered = true)
        segs += EscPosPrinter.Segment("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        val empName = orderDoc.getString("employeeName") ?: ""
        val custName = orderDoc.getString("customerName") ?: ""
        val voidedBy = orderDoc.getString("voidedBy")?.trim()?.takeIf { it.isNotBlank() }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())

        if (orderNumber > 0L) {
            segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (orderType.isNotBlank()) {
            val label = when (orderType) { "DINE_IN" -> "Dine In"; "TO_GO" -> "To Go"; "BAR_TAB" -> "Bar Tab"; else -> orderType }
            segs += EscPosPrinter.Segment("Type: $label", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        }
        if (rs.showServerName && empName.isNotBlank()) segs += EscPosPrinter.Segment("Server: $empName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (custName.isNotBlank()) segs += EscPosPrinter.Segment("Customer: $custName", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (rs.showDateTime) segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        if (voidedBy != null) segs += EscPosPrinter.Segment("Voided by: $voidedBy", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
        segs += EscPosPrinter.Segment("")

        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        for (doc in items) {
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            val basePriceCents = doc.getLong("basePriceInCents") ?: lineTotalCents
            val itemLabel = if (qty > 1) "${qty}x $name" else name
            if (basePriceCents > 0L) {
                segs += EscPosPrinter.Segment(formatLine(itemLabel, MoneyUtils.centsToDisplay(lineTotalCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
            } else {
                segs += EscPosPrinter.Segment(itemLabel, bold = rs.boldItems, fontSize = rs.fontSizeItems)
            }
            val mods = doc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
            for (mod in mods) {
                val modName = mod["name"]?.toString() ?: continue
                val modAction = mod["action"]?.toString() ?: "ADD"
                val modPrice = (mod["price"] as? Number)?.toDouble() ?: 0.0
                val modCents = kotlin.math.round(modPrice * 100).toLong()
                when {
                    modAction == "REMOVE" -> segs += EscPosPrinter.Segment("  NO $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    modCents > 0 -> segs += EscPosPrinter.Segment(formatLine("  + $modName", MoneyUtils.centsToDisplay(modCents), lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
                    else -> segs += EscPosPrinter.Segment("  + $modName", bold = rs.boldItems, fontSize = rs.fontSizeItems)
                }
            }
        }
        segs += EscPosPrinter.Segment("-".repeat(lwi), bold = rs.boldItems, fontSize = rs.fontSizeItems)
        segs += EscPosPrinter.Segment("")

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) { taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        segs += EscPosPrinter.Segment(formatLine("Subtotal", MoneyUtils.centsToDisplay(subtotalCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)

        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        val groupedDiscounts = DiscountDisplay.groupByName(appliedDiscounts)
        if (groupedDiscounts.isNotEmpty()) {
            for (gd in groupedDiscounts) {
                val discLabel = DiscountDisplay.formatReceiptLabel(gd.name, gd.type, gd.value)
                segs += EscPosPrinter.Segment(formatLine(discLabel, "-${MoneyUtils.centsToDisplay(gd.totalCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
            }
        } else if (discountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Discount", "-${MoneyUtils.centsToDisplay(discountInCents)}", lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }

        for (entry in taxBreakdown) {
            val tName = entry["name"]?.toString() ?: "Tax"
            val aCents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
            val tRate = (entry["rate"] as? Number)?.toDouble()
            val tType = entry["taxType"]?.toString()
            val tLabel = DiscountDisplay.formatTaxLabel(tName, tType, tRate)
            segs += EscPosPrinter.Segment(formatLine(tLabel, MoneyUtils.centsToDisplay(aCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        if (tipAmountInCents > 0L) {
            segs += EscPosPrinter.Segment(formatLine("Tip", MoneyUtils.centsToDisplay(tipAmountInCents), lwt), bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        }
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment(
            formatLine("VOID TOTAL", "-${MoneyUtils.centsToDisplay(totalInCents)}", lwg),
            bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
        )
        segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
        segs += EscPosPrinter.Segment("")

        for (p in payments) {
            val pType = p["paymentType"]?.toString() ?: ""
            if (pType.equals("Cash", ignoreCase = true)) {
                segs += EscPosPrinter.Segment("Paid with Cash", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            } else {
                val brand = p["cardBrand"]?.toString() ?: ""
                val l4 = p["last4"]?.toString() ?: ""
                val auth = p["authCode"]?.toString() ?: ""
                if (brand.isNotBlank() || l4.isNotBlank()) {
                    segs += EscPosPrinter.Segment(buildString { if (brand.isNotBlank()) append(brand); if (l4.isNotBlank()) { if (isNotEmpty()) append(" "); append("**** $l4") } }, bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                }
                if (auth.isNotBlank()) segs += EscPosPrinter.Segment("Auth: $auth", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
                if (pType.isNotBlank()) segs += EscPosPrinter.Segment("Type: $pType", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
            }
            segs += EscPosPrinter.Segment("")
        }
        segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)
        return segs
    }

    private fun printSimpleLabelReceipt(activity: Activity, label: String, orderId: String, onDismiss: (() -> Unit)?) {
        val db = FirebaseFirestore.getInstance()
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { orderDoc ->
                val rs = ReceiptSettings.load(activity)
                val segs = mutableListOf<EscPosPrinter.Segment>()
                val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

                segs += EscPosPrinter.Segment(rs.businessName, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
                for (line in rs.addressText.split("\n")) {
                    segs += EscPosPrinter.Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
                }
                segs += EscPosPrinter.Segment("")
                segs += EscPosPrinter.Segment("$label RECEIPT", bold = true, fontSize = 2, centered = true)
                segs += EscPosPrinter.Segment("")

                val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
                if (orderNumber > 0L) {
                    segs += EscPosPrinter.Segment("Order #$orderNumber", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
                }
                val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
                segs += EscPosPrinter.Segment("Date: $dateStr", bold = rs.boldOrderInfo, fontSize = rs.fontSizeOrderInfo, centered = true)
                segs += EscPosPrinter.Segment("")

                val amountCents = if (label == "REFUND") {
                    orderDoc.getLong("totalRefundedInCents") ?: 0L
                } else {
                    orderDoc.getLong("totalInCents") ?: 0L
                }
                segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
                segs += EscPosPrinter.Segment(
                    formatLine("$label TOTAL", "-${MoneyUtils.centsToDisplay(amountCents)}", lwg),
                    bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal
                )
                segs += EscPosPrinter.Segment("=".repeat(lwg), bold = rs.boldGrandTotal, fontSize = rs.fontSizeGrandTotal)
                segs += EscPosPrinter.Segment("")
                segs += EscPosPrinter.Segment("Thank you", bold = rs.boldFooter, fontSize = rs.fontSizeFooter, centered = true)

                EscPosPrinter.print(activity, segs, rs)
                onDismiss?.invoke()
            }
            .addOnFailureListener { onDismiss?.invoke() }
    }

    private fun showEmailInput(
        activity: Activity,
        type: ReceiptType,
        orderId: String,
        transactionId: String,
        onDismiss: (() -> Unit)?
    ) {
        val input = EditText(activity).apply {
            hint = "Enter email address"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(activity)
            .setTitle("Email Receipt")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(activity, "Please enter an email", Toast.LENGTH_SHORT).show()
                    onDismiss?.invoke()
                    return@setPositiveButton
                }
                sendReceipt(activity, type, email, orderId, transactionId, onDismiss)
            }
            .setNegativeButton("Cancel") { _, _ -> onDismiss?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun sendReceipt(
        activity: Activity,
        type: ReceiptType,
        email: String,
        orderId: String,
        transactionId: String,
        onDismiss: (() -> Unit)?
    ) {
        val data = hashMapOf(
            "email" to email,
            "orderId" to orderId,
            "transactionId" to transactionId
        )

        FirebaseFunctions.getInstance()
            .getHttpsCallable(type.cloudFunction)
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Toast.makeText(activity, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = (response?.get("error") as? String)?.takeIf { it.isNotBlank() }
                    val msg = if (errorMsg != null) "Failed to send receipt: $errorMsg" else "Failed to send receipt"
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
                onDismiss?.invoke()
            }
            .addOnFailureListener { e ->
                android.util.Log.e("ReceiptPromptHelper", "Receipt send failed", e)
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Please try again."
                Toast.makeText(activity, "Failed to send receipt. $msg", Toast.LENGTH_LONG).show()
                onDismiss?.invoke()
            }
    }
}
