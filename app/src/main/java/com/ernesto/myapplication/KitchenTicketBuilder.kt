package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds kitchen ticket content for thermal LAN printers.
 * Typography is applied when printing via [EscPosPrinter.Segment] + [KitchenTicketStyle].
 * Modifiers with [OrderModifier.action] `REMOVE` set [EscPosPrinter.Segment.red] for red ink on
 * Star SP700 (two-color ribbon); Epson paths ignore [EscPosPrinter.Segment.red].
 */
data class KitchenTicketLineInput(
    val quantity: Int,
    val itemName: String,
    val modifiers: List<OrderModifier>,
    /** Menu routing label; null/blank → station line shows [Kitchen] */
    val routingLabel: String?,
    /**
     * Dine-in only: resolved label for the seat (name from table flow, or "Guest N").
     * Null when not shown (non–dine-in or no guest index on the line).
     */
    val guestKitchenLabel: String? = null,
)

object KitchenTicketBuilder {

    const val LINE_WIDTH: Int = 32

    fun divider(): String = "-".repeat(LINE_WIDTH)

    /**
     * @param printerTitle Shown centered at top (e.g. saved printer name "BAR").
     */
    fun buildTicketSegments(
        printerTitle: String,
        orderNumber: Long?,
        /** Raw Firestore `orderType` (e.g. DINE_IN); used with [KitchenTicketStyle.showTableLineOnlyForDineIn]. */
        orderTypeRaw: String?,
        orderTypeDisplay: String,
        tableDisplay: String,
        timeFormatted: String,
        /** Order-level customer from cart; shown for all order types when set. */
        customerDisplayName: String? = null,
        /**
         * True when this chit lists only newly sent lines (e.g. after a prior Send to Kitchen).
         * For dine-in, shows a short banner so the kitchen knows the ticket is partial.
         */
        isKitchenDeltaChit: Boolean = false,
        orderNotes: String?,
        items: List<KitchenTicketLineInput>,
        style: KitchenTicketStyle,
    ): List<EscPosPrinter.Segment> {
        val out = mutableListOf<EscPosPrinter.Segment>()
        fun seg(
            text: String,
            bold: Boolean,
            fontSize: Int,
            centered: Boolean = false,
        ) {
            val t = EscPosPrinter.sanitizeForThermalText(text)
            out += EscPosPrinter.Segment(
                t,
                bold = bold,
                fontSize = fontSize.coerceIn(0, 2),
                centered = centered,
            )
        }
        fun dividerSeg() {
            val w = KitchenTicketStyle.lineWidthChars(style.dividerFontSize)
            seg("-".repeat(w), style.dividerBold, style.dividerFontSize)
        }
        fun blank() = seg("", false, 0)

        val title = printerTitle.trim().ifEmpty { "KITCHEN" }.uppercase(Locale.US)
        seg(title, style.titleBold, style.titleFontSize, centered = true)

        val metaW = KitchenTicketStyle.lineWidthChars(style.metaFontSize)
        val showTableLine = if (style.showTableLineOnlyForDineIn) {
            isDineInOrder(orderTypeRaw)
        } else {
            true
        }
        val metaLines = buildList {
            add(if (orderNumber != null && orderNumber > 0) "Order #$orderNumber" else "Order #-")
            add("Type: $orderTypeDisplay")
            if (showTableLine) add("Table: $tableDisplay")
            add("Time: $timeFormatted")
        }
        for (line in metaLines) {
            for (w in wrapLine(line, metaW)) {
                seg(w, style.metaBold, style.metaFontSize)
            }
        }
        customerDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { cn ->
            for (w in wrapLine("Customer: $cn", metaW)) {
                seg(w, style.metaBold, style.metaFontSize)
            }
        }
        if (isKitchenDeltaChit && isDineInOrder(orderTypeRaw)) {
            for (w in wrapLine("New items only (update)", metaW)) {
                seg(w, style.metaBold, style.metaFontSize)
            }
        }

        dividerSeg()
        blank()

        items.forEachIndexed { idx, item ->
            if (idx > 0) blank()
            out.addAll(segmentsForItem(item, style))
        }

        blank()
        dividerSeg()

        val notes = orderNotes?.trim()?.takeIf { it.isNotEmpty() }
        if (notes != null) {
            seg("Notes:", style.notesHeadingBold, style.notesHeadingFontSize)
            val noteW = KitchenTicketStyle.lineWidthChars(style.notesBodyFontSize)
            for (line in wrapParagraph(notes, noteW)) {
                seg(line, style.notesBodyBold, style.notesBodyFontSize)
            }
            blank()
        }

        dividerSeg()
        blank()
        dividerSeg()

        return out
    }

    /**
     * Sample data for style preview / test print. Must stay in sync with the dashboard
     * `KitchenTicketStyleModal` / `KitchenTicketPreview` (same order #, TO GO, Sprite, notes).
     */
    const val DEMO_KITCHEN_ORDER_NUMBER: Long = 265L
    private const val DEMO_KITCHEN_ORDER_TYPE_RAW = "TO_GO"
    private const val DEMO_KITCHEN_NOTES = "Extra napkins please"

    /**
     * Full demo chit using the same [buildTicketSegments] path as production prints.
     * Uses the same sample order as the web "Kitchen ticket style" preview so test print matches the modal.
     */
    fun buildSampleDemoTicketSegments(
        printerTitle: String,
        style: KitchenTicketStyle,
        /** When [KitchenTicketStyle.showRoutingTag] is true, used inside `[label]`; falls back to "drinks" like the dashboard preview. */
        demoRoutingLabel: String?,
    ): List<EscPosPrinter.Segment> {
        val timeFormatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val routingForTag = when {
            !style.showRoutingTag -> null
            else -> demoRoutingLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "drinks"
        }
        val sampleItems = listOf(
            KitchenTicketLineInput(
                quantity = 1,
                itemName = "Sprite",
                modifiers = listOf(OrderModifier(name = "Small")),
                routingLabel = routingForTag,
            ),
        )
        return buildTicketSegments(
            printerTitle = printerTitle,
            orderNumber = DEMO_KITCHEN_ORDER_NUMBER,
            orderTypeRaw = DEMO_KITCHEN_ORDER_TYPE_RAW,
            orderTypeDisplay = formatOrderTypeForTicket(DEMO_KITCHEN_ORDER_TYPE_RAW),
            tableDisplay = "-",
            timeFormatted = timeFormatted,
            customerDisplayName = null,
            isKitchenDeltaChit = false,
            orderNotes = DEMO_KITCHEN_NOTES,
            items = sampleItems,
            style = style,
        )
    }

    /**
     * Label for a line item on kitchen chits (dine-in + [guestNumber] > 0 only).
     * Uses [guestNames] index `guestNumber - 1` when non-blank; otherwise "Guest N".
     */
    fun guestKitchenLabelForLine(
        orderTypeRaw: String?,
        guestNumber: Int,
        guestNames: List<String>?,
    ): String? {
        if (guestNumber <= 0 || !isDineInOrder(orderTypeRaw)) return null
        val named = guestNames?.getOrNull(guestNumber - 1)?.trim()?.takeIf { it.isNotEmpty() }
        return named ?: "Guest $guestNumber"
    }

    fun formatOrderTypeForTicket(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        val u = raw.trim().uppercase(Locale.US).replace(" ", "_")
        return when (u) {
            "DINE_IN" -> "DINE IN"
            "TO_GO" -> "TO GO"
            "BAR_TAB", "BAR" -> "BAR"
            else -> raw.trim().uppercase(Locale.US).replace("_", " ")
        }
    }

    fun readOrderNotes(doc: DocumentSnapshot): String? {
        val keys = listOf("orderNotes", "notes", "kitchenNotes", "specialInstructions")
        for (k in keys) {
            val s = doc.getString(k)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return s
        }
        return null
    }

    private fun segmentsForItem(item: KitchenTicketLineInput, style: KitchenTicketStyle): List<EscPosPrinter.Segment> {
        val out = mutableListOf<EscPosPrinter.Segment>()
        fun seg(
            text: String,
            bold: Boolean,
            fontSize: Int,
            centered: Boolean = false,
            red: Boolean = false,
        ) {
            val t = EscPosPrinter.sanitizeForThermalText(text)
            out += EscPosPrinter.Segment(
                t,
                bold = bold,
                fontSize = fontSize.coerceIn(0, 2),
                centered = centered,
                red = red,
            )
        }
        val itemW = KitchenTicketStyle.lineWidthChars(style.itemFontSize)
        item.guestKitchenLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { gl ->
            for (w in wrapLine("Guest: $gl", itemW)) {
                seg(w, style.itemBold, style.itemFontSize)
            }
        }
        val qty = item.quantity.coerceAtLeast(1)
        val main = if (qty > 1) "${qty}x ${item.itemName}" else "1x ${item.itemName}"
        for (line in wrapLine(main, itemW)) {
            seg(line, style.itemBold, style.itemFontSize)
        }
        if (item.modifiers.isNotEmpty()) {
            if (style.itemFontSize >= 1) seg("", false, 0)
            val modW = KitchenTicketStyle.lineWidthChars(style.modifierFontSize)
            for (row in modifierLineRows(item.modifiers, baseIndent = 3)) {
                for (w in wrapLine(row.text, modW)) {
                    seg(w, style.modifierBold, style.modifierFontSize, red = row.redOnStar)
                }
            }
        }
        if (style.showRoutingTag) {
            val station = item.routingLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Kitchen"
            val tagW = KitchenTicketStyle.lineWidthChars(style.stationTagFontSize)
            for (w in wrapLine("[$station]", tagW)) {
                seg(w, style.stationTagBold, style.stationTagFontSize)
            }
        }
        return out
    }

    private fun isDineInOrder(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val u = raw.trim().uppercase(Locale.US).replace(" ", "_")
        return u == "DINE_IN"
    }

    private data class ModifierLineRow(val text: String, val redOnStar: Boolean)

    private fun modifierLineRows(mods: List<OrderModifier>, baseIndent: Int): List<ModifierLineRow> {
        val prefix = " ".repeat(baseIndent) + "- "
        val out = mutableListOf<ModifierLineRow>()
        for (m in mods) {
            val isRemove = m.action.trim().uppercase(Locale.US) == "REMOVE"
            val text = when {
                isRemove -> {
                    val n = m.name.trim()
                    if (n.uppercase(Locale.US).startsWith("NO")) n else "No $n"
                }
                else -> m.name.trim()
            }
            out.add(ModifierLineRow("$prefix$text", redOnStar = isRemove))
            if (m.children.isNotEmpty()) {
                out.addAll(modifierLineRows(m.children, baseIndent + 3))
            }
        }
        return out
    }

    private fun wrapParagraph(text: String, width: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            if (cur.isEmpty()) {
                cur.append(w)
            } else if (cur.length + 1 + w.length <= width) {
                cur.append(' ').append(w)
            } else {
                lines.add(cur.toString())
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }

    /** Word-wrap a single logical line to [width]. */
    private fun wrapLine(text: String, width: Int): List<String> {
        val t = text.trimEnd()
        if (t.length <= width) return listOf(t)
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                lines.add(cur.toString())
                cur = StringBuilder()
            }
        }
        for (w in words) {
            if (cur.isEmpty()) {
                if (w.length <= width) {
                    cur.append(w)
                } else {
                    var rest = w
                    while (rest.length > width) {
                        lines.add(rest.take(width))
                        rest = rest.drop(width)
                    }
                    cur.append(rest)
                }
            } else {
                val candidate = "${cur} $w"
                if (candidate.length <= width) {
                    cur.clear()
                    cur.append(candidate)
                } else {
                    flush()
                    if (w.length <= width) cur.append(w)
                    else {
                        var rest = w
                        while (rest.length > width) {
                            lines.add(rest.take(width))
                            rest = rest.drop(width)
                        }
                        cur.append(rest)
                    }
                }
            }
        }
        flush()
        return lines
    }

}
