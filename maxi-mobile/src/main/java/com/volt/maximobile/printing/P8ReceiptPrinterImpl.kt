package com.volt.maximobile.printing

import com.volt.maximobile.dvpaylite.P8ReceiptPrinter
import com.volt.shared.printing.Alignment
import com.volt.shared.printing.KitchenTicketData
import com.volt.shared.printing.ReceiptPrinter
import com.volt.shared.printing.ReceiptSegment
import com.volt.shared.printing.SegmentType

/**
 * Adapts the P8 built-in thermal printer to the shared [ReceiptPrinter] interface.
 */
class P8ReceiptPrinterImpl : ReceiptPrinter {

    override fun printReceipt(segments: List<ReceiptSegment>) {
        val p8Segments = segments.mapNotNull { seg ->
            when (seg.type) {
                SegmentType.TEXT -> P8ReceiptPrinter.ReceiptSegment(
                    text = seg.text,
                    bold = seg.bold,
                    centered = seg.alignment == Alignment.CENTER,
                )
                SegmentType.SEPARATOR -> P8ReceiptPrinter.ReceiptSegment(
                    text = "-".repeat(P8ReceiptPrinter.LINE_WIDTH),
                    bold = false,
                    centered = false,
                )
                SegmentType.LINE_BREAK -> P8ReceiptPrinter.ReceiptSegment(
                    text = " ",
                    bold = false,
                    centered = false,
                )
                SegmentType.CUT -> null
                SegmentType.IMAGE -> null
            }
        }
        P8ReceiptPrinter.printReceipt(p8Segments)
    }

    override fun openCashDrawer() {
        // P8 has no external cash drawer
    }

    override fun printKitchenTicket(ticket: KitchenTicketData) {
        val segments = mutableListOf<P8ReceiptPrinter.ReceiptSegment>()
        segments.add(P8ReceiptPrinter.ReceiptSegment("KITCHEN", bold = true, centered = true))
        segments.add(P8ReceiptPrinter.ReceiptSegment(
            P8ReceiptPrinter.formatLine("Order #${ticket.orderNumber}", ticket.orderType),
            bold = true,
        ))
        if (ticket.tableName.isNotBlank()) {
            segments.add(P8ReceiptPrinter.ReceiptSegment(ticket.tableName))
        }
        segments.add(P8ReceiptPrinter.ReceiptSegment("-".repeat(P8ReceiptPrinter.LINE_WIDTH)))
        for (item in ticket.items) {
            segments.add(P8ReceiptPrinter.ReceiptSegment(
                P8ReceiptPrinter.formatLine("${item.quantity}x ${item.name}", ""),
            ))
            for (mod in item.modifiers) {
                segments.add(P8ReceiptPrinter.ReceiptSegment("  + $mod"))
            }
            if (item.notes.isNotBlank()) {
                segments.add(P8ReceiptPrinter.ReceiptSegment("  NOTE: ${item.notes}"))
            }
        }
        if (ticket.notes.isNotBlank()) {
            segments.add(P8ReceiptPrinter.ReceiptSegment("-".repeat(P8ReceiptPrinter.LINE_WIDTH)))
            segments.add(P8ReceiptPrinter.ReceiptSegment("NOTES: ${ticket.notes}"))
        }
        segments.add(P8ReceiptPrinter.ReceiptSegment(" "))
        P8ReceiptPrinter.printReceipt(segments)
    }
}
