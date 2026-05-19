package com.volt.maximobile

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.volt.maximobile.dvpaylite.P8ReceiptPrinter
import com.volt.maximobile.dvpaylite.P8ReceiptPrinter.ReceiptSegment
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds receipt segments for the Dejavoo P8 thermal printer matching the
 * main POS receipt design (see `OrderDetailActivity.buildOriginalSegments`).
 *
 * Line width is fixed at 24 chars (P8 printer SDK limit).
 */
object MaxiReceiptBuilder {

    private const val W = P8ReceiptPrinter.LINE_WIDTH

    data class ReceiptData(
        val businessName: String,
        val addressText: String,
        val orderNumber: Long,
        val orderType: String,
        val employeeName: String,
        val lines: List<CartLine>,
        val subtotalCents: Long,
        val taxCents: Long,
        val tipCents: Long = 0L,
        val totalCents: Long,
        val paymentType: String,
        val txnResult: JSONObject?,
    )

    /** Builds a customer receipt for the P8 built-in printer from a paid Firestore order. */
    @Suppress("UNCHECKED_CAST")
    fun buildFromPaidOrder(
        context: Context,
        orderDoc: DocumentSnapshot,
        items: List<DocumentSnapshot>,
        payments: List<Map<String, Any>>,
    ): List<ReceiptSegment> {
        val rs = ReceiptSettings.load(context)
        val cartLines = items.map { doc ->
            val name = doc.getString("name")
                ?: doc.getString("itemName")
                ?: "Item"
            val qty = (doc.getLong("qty")
                ?: doc.getLong("quantity")
                ?: 1L).toInt().coerceAtLeast(1)
            val lineTotalCents = doc.getLong("lineTotalInCents") ?: 0L
            CartLine(
                menuItemId = doc.id,
                name = name,
                basePriceDollars = lineTotalCents / 100.0 / qty,
                quantity = qty,
            )
        }

        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) {
            taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
        }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents

        val firstPayment = payments.firstOrNull()
        val paymentType = firstPayment?.get("paymentType")?.toString() ?: "Cash"

        return build(
            ReceiptData(
                businessName = rs.businessName,
                addressText = rs.addressText,
                orderNumber = orderDoc.getLong("orderNumber") ?: 0L,
                orderType = orderDoc.getString("orderType") ?: "",
                employeeName = if (rs.showServerName) {
                    orderDoc.getString("employeeName") ?: ""
                } else {
                    ""
                },
                lines = cartLines,
                subtotalCents = subtotalCents,
                taxCents = taxTotalCents,
                tipCents = if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(context, tipAmountInCents)) {
                    tipAmountInCents
                } else {
                    0L
                },
                totalCents = totalInCents,
                paymentType = paymentType,
                txnResult = firstPayment?.let { paymentToTxnJson(it) },
            ),
        )
    }

    private fun paymentToTxnJson(payment: Map<String, Any>): JSONObject {
        val paymentType = payment["paymentType"]?.toString().orEmpty()
        if (paymentType.equals("Cash", ignoreCase = true)) {
            return JSONObject().put("card_type", "Cash")
        }
        val last4 = payment["last4"]?.toString().orEmpty()
        val maskPan = if (last4.isNotBlank()) "************$last4" else ""
        return JSONObject().apply {
            put("card_type", payment["cardBrand"]?.toString()?.ifBlank { paymentType } ?: paymentType)
            put("mask_pan", maskPan)
            put("authCode", payment["authCode"]?.toString().orEmpty())
            put(
                "transaction_mode",
                when (payment["entryType"]?.toString()?.lowercase(Locale.US)) {
                    "swipe" -> "1"
                    "chip", "insert" -> "2"
                    "contactless", "tap" -> "3"
                    "manual", "keyed" -> "4"
                    else -> ""
                },
            )
        }
    }

    fun build(data: ReceiptData): List<ReceiptSegment> {
        val segs = mutableListOf<ReceiptSegment>()
        val fl = P8ReceiptPrinter::formatLine

        // ── Header ──
        if (data.businessName.isNotBlank()) {
            for (line in wrapText(data.businessName, W)) {
                segs += ReceiptSegment(line, bold = true, centered = true)
            }
        }
        if (data.addressText.isNotBlank()) {
            for (line in data.addressText.split("\n")) {
                for (wrapped in wrapText(line.trim(), W)) {
                    segs += ReceiptSegment(wrapped, centered = true)
                }
            }
        }
        segs += ReceiptSegment("")

        // ── Title ──
        segs += ReceiptSegment("RECEIPT", bold = true, centered = true)
        segs += ReceiptSegment("")

        // ── Order info ──
        if (data.orderNumber > 0) {
            segs += ReceiptSegment("Order #${data.orderNumber}", bold = true, centered = true)
        }
        val typeLabel = when (data.orderType) {
            "TO_GO" -> "To Go"
            "DINE_IN" -> "Dine In"
            "BAR_TAB" -> "Bar Tab"
            else -> data.orderType.ifBlank { null }
        }
        if (typeLabel != null) {
            segs += ReceiptSegment("Type: $typeLabel", centered = true)
        }
        if (data.employeeName.isNotBlank()) {
            segs += ReceiptSegment("Server: ${data.employeeName}", centered = true)
        }
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += ReceiptSegment(receiptOrderInfoDateLine(dateStr), centered = true)
        segs += ReceiptSegment("")

        // ── Items ──
        segs += ReceiptSegment("-".repeat(W))
        for (line in data.lines) {
            val label = if (line.quantity > 1) "${line.quantity}x ${line.name}" else line.name
            val priceCents = Math.round(line.unitPriceDollars * 100.0) * line.quantity
            segs += ReceiptSegment(fl(label, centsToDisplay(priceCents), W), bold = true)
        }
        segs += ReceiptSegment("-".repeat(W))
        segs += ReceiptSegment("")

        // ── Totals ──
        segs += ReceiptSegment(fl("Subtotal", centsToDisplay(data.subtotalCents), W), bold = true)
        if (data.taxCents > 0) {
            segs += ReceiptSegment(fl("Tax", centsToDisplay(data.taxCents), W), bold = true)
        }
        if (data.tipCents > 0) {
            segs += ReceiptSegment(fl("Tip", centsToDisplay(data.tipCents), W), bold = true)
        }
        segs += ReceiptSegment("=".repeat(W))
        segs += ReceiptSegment(fl("TOTAL", centsToDisplay(data.totalCents), W), bold = true)
        segs += ReceiptSegment("")

        // ── Payment ──
        val txn = data.txnResult
        if (txn != null) {
            segs += ReceiptSegment("Payments:")
            val cardType = txn.optString("card_type", "").ifBlank { data.paymentType }
            val last4 = txn.optString("mask_pan", "").takeLast(4)
            val authCode = txn.optString("authCode", "")
            val entryMode = txn.optString("transaction_mode", "")
            val entryLabel = when (entryMode) {
                "1" -> "Swipe"
                "2" -> "Chip"
                "3" -> "Contactless"
                "4" -> "Manual"
                else -> ""
            }

            val cardLine = buildString {
                append(cardType)
                if (last4.isNotBlank()) append(" **** $last4")
                if (entryLabel.isNotBlank()) append(" ($entryLabel)")
            }.trim()
            segs += ReceiptSegment(fl(cardLine, centsToDisplay(data.totalCents), W))
            if (authCode.isNotBlank()) {
                segs += ReceiptSegment("  Auth: $authCode")
            }
            segs += ReceiptSegment("-".repeat(W))
            segs += ReceiptSegment(fl("Paid:", centsToDisplay(data.totalCents), W))
            segs += ReceiptSegment(fl("Balance:", centsToDisplay(0), W))
            segs += ReceiptSegment("")
        }

        // ── Footer ──
        segs += ReceiptSegment("Thank you!", bold = true, centered = true)
        segs += ReceiptSegment("")

        return segs
    }

    private fun centsToDisplay(cents: Long): String {
        val abs = Math.abs(cents)
        val dollars = abs / 100
        val rem = abs % 100
        val formatted = "$${dollars}.${String.format(Locale.US, "%02d", rem)}"
        return if (cents < 0) "-$formatted" else formatted
    }

    private fun wrapText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                lines += remaining
                break
            }
            val chunk = remaining.take(maxChars)
            val lastSpace = chunk.lastIndexOf(' ')
            if (lastSpace > 0) {
                lines += chunk.take(lastSpace).trimEnd()
                remaining = remaining.drop(lastSpace + 1).trimStart()
            } else {
                lines += chunk
                remaining = remaining.drop(maxChars)
            }
        }
        return lines
    }
}
