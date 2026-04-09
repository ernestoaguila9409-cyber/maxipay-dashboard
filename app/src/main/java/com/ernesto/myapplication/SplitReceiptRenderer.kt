package com.ernesto.myapplication

import android.content.Context
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.SplitReceiptLine
import com.ernesto.myapplication.engine.SplitReceiptPayload
import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SplitReceiptRenderer {

    fun buildPlainTextBody(
        context: Context,
        orderDoc: DocumentSnapshot,
        payload: SplitReceiptPayload
    ): String {
        val rs = ReceiptSettings.load(context)
        val sb = StringBuilder()
        if (rs.businessName.isNotBlank()) sb.appendLine(rs.businessName.trim())
        if (rs.addressText.isNotBlank()) sb.appendLine(rs.addressText.trim())
        sb.appendLine()
        sb.appendLine("RECEIPT (SPLIT)")
        sb.appendLine()
        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        if (orderNumber > 0L) sb.appendLine("Order #$orderNumber")
        val orderType = orderDoc.getString("orderType") ?: ""
        if (orderType.isNotBlank()) {
            val typeLabel = when (orderType) {
                "DINE_IN" -> "Dine In"
                "TO_GO" -> "To Go"
                "BAR_TAB" -> "Bar Tab"
                else -> orderType
            }
            sb.appendLine("Type: $typeLabel")
        }
        val emp = orderDoc.getString("employeeName") ?: ""
        if (rs.showServerName && emp.isNotBlank()) sb.appendLine("Server: $emp")
        val cust = orderDoc.getString("customerName") ?: ""
        if (cust.isNotBlank()) sb.appendLine("Customer: $cust")
        if (rs.showDateTime) {
            val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
            sb.appendLine("Date: $dateStr")
        }
        sb.appendLine()
        sb.appendLine(payload.guestLabel)
        val tw = LINE_WIDTH
        sb.appendLine("-".repeat(tw))
        appendItemsText(sb, payload, tw)
        sb.appendLine("-".repeat(tw))
        sb.appendLine(formatLine("Subtotal", MoneyUtils.centsToDisplay(payload.subtotalInCents), tw))
        if (payload.discountInCents > 0L) {
            sb.appendLine(formatLine("Discount", "-${MoneyUtils.centsToDisplay(payload.discountInCents)}", tw))
        }
        if (payload.taxLines.isNotEmpty()) {
            for ((label, cents) in payload.taxLines) {
                if (cents > 0L) sb.appendLine(formatLine(label, MoneyUtils.centsToDisplay(cents), tw))
            }
        } else if (payload.taxInCents > 0L) {
            sb.appendLine(formatLine("Tax", MoneyUtils.centsToDisplay(payload.taxInCents), tw))
        }
        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(context, payload.tipInCents) && payload.tipInCents > 0L) {
            sb.appendLine(formatLine("Tip", MoneyUtils.centsToDisplay(payload.tipInCents), tw))
        }
        sb.appendLine("=".repeat(tw))
        sb.appendLine(formatLine("TOTAL", MoneyUtils.centsToDisplay(payload.totalInCents), tw))
        sb.appendLine()
        sb.appendLine("Payment: ${payload.paymentMethod}")
        sb.appendLine()
        sb.appendLine("Thank you!")
        return sb.toString()
    }

    private fun isEvenSplitShareLine(line: SplitReceiptLine): Boolean {
        val orig = line.originalLineTotalInCents
        return orig != null && orig > 0L &&
            line.splitIndex != null && line.totalSplits != null
    }

    private fun appendItemsText(sb: StringBuilder, payload: SplitReceiptPayload, textWidth: Int) {
        val note = payload.sharedItemsNote
        if (!note.isNullOrBlank()) {
            sb.appendLine(note)
            return
        }
        for (line in payload.items) {
            if (isEvenSplitShareLine(line)) {
                val label = line.originalItemName ?: line.name
                val si = line.splitIndex!!
                val ts = line.totalSplits!!
                sb.appendLine(
                    formatLine(
                        "$label ($si/$ts share)",
                        MoneyUtils.centsToDisplay(line.lineTotalInCents),
                        textWidth
                    )
                )
                for (m in line.modifierLines) {
                    sb.appendLine(m)
                }
                sb.appendLine("  Line total ${MoneyUtils.centsToDisplay(line.originalLineTotalInCents!!)}")
            } else {
                val label = if (line.quantity > 1) "${line.quantity}x ${line.name}" else line.name
                sb.appendLine(formatLine(label, MoneyUtils.centsToDisplay(line.lineTotalInCents), textWidth))
                for (m in line.modifierLines) {
                    sb.appendLine(m)
                }
            }
        }
    }

    fun buildEscPosSegments(
        context: Context,
        orderDoc: DocumentSnapshot,
        payload: SplitReceiptPayload
    ): List<EscPosPrinter.Segment> {
        val rs = ReceiptSettings.load(context)
        val segs = mutableListOf<EscPosPrinter.Segment>()
        val bo = rs.boldOrderInfo
        val fo = rs.fontSizeOrderInfo
        val bi = rs.boldItems
        val fi = rs.fontSizeItems
        val bt = rs.boldTotals
        val ft = rs.fontSizeTotals
        val bg = rs.boldGrandTotal
        val fg = rs.fontSizeGrandTotal
        val bf = rs.boldFooter
        val ff = rs.fontSizeFooter
        val lwi = ReceiptSettings.lineWidthForSize(fi)
        val lwt = ReceiptSettings.lineWidthForSize(ft)
        val lwg = ReceiptSettings.lineWidthForSize(fg)

        fun orderInfo(text: String) {
            segs += EscPosPrinter.Segment(text, bold = bo, fontSize = fo, centered = true)
        }
        fun item(text: String) {
            segs += EscPosPrinter.Segment(text, bold = bi, fontSize = fi)
        }
        fun total(text: String) {
            segs += EscPosPrinter.Segment(text, bold = bt, fontSize = ft)
        }
        fun grand(text: String) {
            segs += EscPosPrinter.Segment(text, bold = bg, fontSize = fg)
        }
        fun footer(text: String) {
            segs += EscPosPrinter.Segment(text, bold = bf, fontSize = ff, centered = true)
        }

        EscPosPrinter.appendHeaderSegments(segs, rs)
        segs += EscPosPrinter.Segment("")
        orderInfo("RECEIPT (SPLIT)")
        orderInfo("")

        val orderNumber = orderDoc.getLong("orderNumber") ?: 0L
        val orderType = orderDoc.getString("orderType") ?: ""
        if (orderNumber > 0L) orderInfo("Order #$orderNumber")
        if (orderType.isNotBlank()) {
            val typeLabel = when (orderType) {
                "DINE_IN" -> "Dine In"
                "TO_GO" -> "To Go"
                "BAR_TAB" -> "Bar Tab"
                else -> orderType
            }
            orderInfo("Type: $typeLabel")
        }
        val emp = orderDoc.getString("employeeName") ?: ""
        if (rs.showServerName && emp.isNotBlank()) orderInfo("Server: $emp")
        val cust = orderDoc.getString("customerName") ?: ""
        if (cust.isNotBlank()) orderInfo("Customer: $cust")
        if (rs.showDateTime) {
            val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
            orderInfo("Date: $dateStr")
        }
        segs += EscPosPrinter.Segment("")
        orderInfo(payload.guestLabel)
        segs += EscPosPrinter.Segment("")

        item("-".repeat(lwi))
        if (!payload.sharedItemsNote.isNullOrBlank()) {
            item(payload.sharedItemsNote)
        } else {
            for (line in payload.items) {
                if (isEvenSplitShareLine(line)) {
                    val label = line.originalItemName ?: line.name
                    val si = line.splitIndex!!
                    val ts = line.totalSplits!!
                    item(
                        formatLine(
                            "$label ($si/$ts share)",
                            MoneyUtils.centsToDisplay(line.lineTotalInCents),
                            lwi
                        )
                    )
                    for (m in line.modifierLines) {
                        item(EscPosPrinter.sanitizeForThermalText(m))
                    }
                    item("  Line total ${MoneyUtils.centsToDisplay(line.originalLineTotalInCents!!)}")
                } else {
                    val label = if (line.quantity > 1) "${line.quantity}x ${line.name}" else line.name
                    item(formatLine(label, MoneyUtils.centsToDisplay(line.lineTotalInCents), lwi))
                    for (m in line.modifierLines) {
                        item(EscPosPrinter.sanitizeForThermalText(m))
                    }
                }
            }
        }
        item("-".repeat(lwi))
        segs += EscPosPrinter.Segment("")

        total(formatLine("Subtotal", MoneyUtils.centsToDisplay(payload.subtotalInCents), lwt))
        if (payload.discountInCents > 0L) {
            total(formatLine("Discount", "-${MoneyUtils.centsToDisplay(payload.discountInCents)}", lwt))
        }
        if (payload.taxLines.isNotEmpty()) {
            for ((label, cents) in payload.taxLines) {
                if (cents > 0L) total(formatLine(label, MoneyUtils.centsToDisplay(cents), lwt))
            }
        } else if (payload.taxInCents > 0L) {
            total(formatLine("Tax", MoneyUtils.centsToDisplay(payload.taxInCents), lwt))
        }
        if (TipConfig.shouldIncludeTipLineOnPrintedReceipt(context, payload.tipInCents) && payload.tipInCents > 0L) {
            total(formatLine("Tip", MoneyUtils.centsToDisplay(payload.tipInCents), lwt))
        }
        total("=".repeat(lwt))
        grand(formatLine("TOTAL", MoneyUtils.centsToDisplay(payload.totalInCents), lwg))
        segs += EscPosPrinter.Segment("")
        footer("Payment: ${payload.paymentMethod}")
        segs += EscPosPrinter.Segment("")
        footer("Thank you for dining with us!")

        return segs
    }
}
