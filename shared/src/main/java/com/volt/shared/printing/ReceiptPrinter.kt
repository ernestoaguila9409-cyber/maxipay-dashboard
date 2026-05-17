package com.volt.shared.printing

data class ReceiptSegment(
    val type: SegmentType,
    val text: String = "",
    val alignment: Alignment = Alignment.LEFT,
    val bold: Boolean = false,
    val doubleWidth: Boolean = false,
    val doubleHeight: Boolean = false,
)

enum class SegmentType {
    TEXT, LINE_BREAK, SEPARATOR, CUT, IMAGE
}

enum class Alignment {
    LEFT, CENTER, RIGHT
}

data class KitchenTicketData(
    val orderNumber: Long,
    val orderType: String,
    val tableName: String = "",
    val items: List<KitchenTicketItem>,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

data class KitchenTicketItem(
    val name: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String = "",
)

interface ReceiptPrinter {
    fun printReceipt(segments: List<ReceiptSegment>)
    fun openCashDrawer()
    fun printKitchenTicket(ticket: KitchenTicketData)
}
