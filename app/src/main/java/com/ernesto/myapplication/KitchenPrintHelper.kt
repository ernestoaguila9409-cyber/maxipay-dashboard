package com.ernesto.myapplication

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves kitchen printers from [PrintingSettingsCache], groups [KitchenTicketLineInput] into
 * one ticket per printer, builds tickets with [KitchenTicketBuilder], and sends via
 * [EscPosPrinter.printKitchenChitToLan] (TCP port 9100). Typography comes from dashboard
 * `kitchenTicketStyle` on `Printers` docs, cached in [PrinterKitchenStyleCache]. Receipt printing is separate.
 */
object KitchenPrintHelper {

    /**
     * On `Orders/{id}`: map from item line doc id → cumulative quantity already printed to kitchen.
     * Stored on the order (not per item) so one atomic update works with typical Firestore rules.
     */
    const val KITCHEN_SENT_BY_LINE_MAP_FIELD = "kitchenSentQtyByLineKey"

    /**
     * On `Orders/{id}`: map printer LAN IP (or fallback key) → last resolved notes string printed for that printer.
     * Used so Send Update tickets omit the Notes block when order notes did not change since last print.
     */
    const val KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP = "kitchenNotesLastPrintedByPrinterIp"

    /** Legacy per-line field; still read as fallback when merging payment-triggered deltas. */
    private const val KITCHEN_SENT_QTY_LEGACY_FIELD = "kitchenSentQty"

    private data class KitchenPrintBatch(
        val printer: SelectedPrinterDisplay,
        val ticketItems: List<KitchenTicketLineInput>,
    )

    /**
     * When the order is fully paid: if [PrintingSettingsCache.printTriggerMode] is [PrintingSettingsFirestore.ON_PAYMENT]
     * or [PrintingSettingsFirestore.FIRST_EVENT] and kitchen chits were not printed yet, load lines from Firestore
     * and print, then set [PrintingSettingsFirestore.FIELD_KITCHEN_CHITS_PRINTED_AT].
     * [PrintingSettingsFirestore.ON_SEND] does nothing here (chits only on Send to Kitchen).
     */
    fun maybePrintKitchenTicketsAfterOrderFullyPaid(context: Context, orderId: String) {
        val trigger = PrintingSettingsCache.printTriggerMode
        if (trigger == PrintingSettingsFirestore.ON_SEND) return

        val db = FirebaseFirestore.getInstance()
        val orderRef = db.collection("Orders").document(orderId)
        orderRef.get().addOnSuccessListener { orderDoc ->
            if (!orderDoc.exists()) return@addOnSuccessListener
            if (orderDoc.getTimestamp(PrintingSettingsFirestore.FIELD_KITCHEN_CHITS_PRINTED_AT) != null) {
                return@addOnSuccessListener
            }
            orderRef.collection("items").get().addOnSuccessListener { itemsSnap ->
                val (lineItems, qtyUpdates) = kitchenDeltaFromOrderAndItemsSnapshot(orderDoc, itemsSnap)
                if (lineItems.isEmpty()) {
                    orderRef.update(
                        PrintingSettingsFirestore.FIELD_KITCHEN_CHITS_PRINTED_AT,
                        FieldValue.serverTimestamp(),
                    )
                    return@addOnSuccessListener
                }
                printKitchenTickets(context, orderId, lineItems) { notesPrintedByIp ->
                    val merged = kitchenSentByLineFromOrder(orderDoc).toMutableMap()
                    qtyUpdates.forEach { (k, v) -> merged[k] = v }
                    val update = mutableMapOf<String, Any>(
                        PrintingSettingsFirestore.FIELD_KITCHEN_CHITS_PRINTED_AT to FieldValue.serverTimestamp(),
                        KITCHEN_SENT_BY_LINE_MAP_FIELD to merged,
                    )
                    if (notesPrintedByIp != null) {
                        update[KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP] = notesPrintedByIp
                    }
                    orderRef.update(update)
                }
            }
        }
    }

    /**
     * Order-level sent map plus per-line [KITCHEN_SENT_QTY_LEGACY_FIELD] (max per line).
     * Used when migrating from item-doc tracking or when the order map is still empty.
     */
    fun effectiveSentWithLegacyOrderMap(
        orderDoc: DocumentSnapshot,
        itemsSnap: QuerySnapshot,
    ): Map<String, Int> {
        val merged = kitchenSentByLineFromOrder(orderDoc).toMutableMap()
        for (d in itemsSnap.documents) {
            val legacy = (d.getLong(KITCHEN_SENT_QTY_LEGACY_FIELD) ?: 0L).toInt().coerceAtLeast(0)
            val id = d.id
            merged[id] = kotlin.math.max(merged[id] ?: 0, legacy)
        }
        return merged
    }

    /** Last printed notes text per printer key (see [KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP]). */
    fun kitchenNotesLastPrintedFromOrder(orderDoc: DocumentSnapshot): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val raw = orderDoc.get(KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP) as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (k, v) ->
            val key = k?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to (v?.toString() ?: "")
        }.toMap()
    }

    /** Parses [KITCHEN_SENT_BY_LINE_MAP_FIELD] from an order document (line doc id → sent qty). */
    fun kitchenSentByLineFromOrder(orderDoc: DocumentSnapshot): Map<String, Int> {
        @Suppress("UNCHECKED_CAST")
        val raw = orderDoc.get(KITCHEN_SENT_BY_LINE_MAP_FIELD) as? Map<*, *> ?: return emptyMap()
        val out = mutableMapOf<String, Int>()
        for ((k, v) in raw) {
            val key = k?.toString() ?: continue
            val qty = when (v) {
                is Long -> v.toInt()
                is Int -> v
                is Number -> v.toInt()
                else -> continue
            }
            out[key] = qty.coerceAtLeast(0)
        }
        return out
    }

    /**
     * Delta from Firestore items vs order-level sent map (and legacy per-item [KITCHEN_SENT_QTY_LEGACY_FIELD]).
     * Returns line doc id → current line `quantity` to merge into [KITCHEN_SENT_BY_LINE_MAP_FIELD].
     */
    fun kitchenDeltaFromOrderAndItemsSnapshot(
        orderDoc: DocumentSnapshot,
        itemsSnap: QuerySnapshot,
    ): Pair<List<KitchenTicketLineInput>, Map<String, Int>> {
        val orderSent = kitchenSentByLineFromOrder(orderDoc)
        val orderTypeRaw = orderDoc.getString("orderType")
        val guestNames = guestNamesListFromOrderDoc(orderDoc)
        val lineItems = mutableListOf<KitchenTicketLineInput>()
        val qtyUpdates = mutableMapOf<String, Int>()
        for (doc in itemsSnap.documents) {
            val qty = (doc.getLong("quantity") ?: 1L).toInt()
            if (qty <= 0) continue
            val lineKey = doc.id
            val legacySent = (doc.getLong(KITCHEN_SENT_QTY_LEGACY_FIELD) ?: 0L).toInt().coerceAtLeast(0)
            val sent = (orderSent[lineKey] ?: legacySent).coerceAtLeast(0)
            val delta = qty - sent
            if (delta <= 0) continue
            val name = doc.getString("name") ?: continue
            val mods = parseModifiersFromFirestore(doc.get("modifiers"))
            val label = doc.getString("printerLabel")?.trim()?.takeIf { it.isNotEmpty() }
            val guestNum = (doc.getLong("guestNumber") ?: 0L).toInt()
            val guestLabel = KitchenTicketBuilder.guestKitchenLabelForLine(orderTypeRaw, guestNum, guestNames)
            lineItems.add(KitchenTicketLineInput(delta, name, mods, label, guestLabel))
            qtyUpdates[lineKey] = qty
        }
        return lineItems to qtyUpdates
    }

    /** Preserves indices for [KitchenTicketBuilder.guestKitchenLabelForLine] (empty strings allowed). */
    private fun guestNamesListFromOrderDoc(orderDoc: DocumentSnapshot): List<String>? {
        @Suppress("UNCHECKED_CAST")
        val raw = orderDoc.get("guestNames") as? List<*> ?: return null
        if (raw.isEmpty()) return null
        return raw.map { it?.toString().orEmpty() }
    }

    private fun printerNotesMapKey(printer: SelectedPrinterDisplay): String {
        val ip = printer.ipAddress.trim()
        if (ip.isNotEmpty()) return ip
        val name = printer.name.trim().ifEmpty { "printer" }
        return "nolan_$name"
    }

    /**
     * Prints kitchen tickets for the given line items (e.g. one new line or full cart).
     * Applies ALL_ITEMS / BY_LABEL routing only; ticket layout is entirely in [KitchenTicketBuilder].
     *
     * [onNotesPrintedMapReady] receives the full map to store on the order under [KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP]
     * (merge of previous + this run). Pass `null` if the order read failed or early-exit before printing.
     */
    fun printKitchenTickets(
        context: Context,
        orderId: String,
        lineItems: List<KitchenTicketLineInput>,
        onNotesPrintedMapReady: (Map<String, String>?) -> Unit = {},
    ) {
        if (lineItems.isEmpty()) {
            onNotesPrintedMapReady(null)
            return
        }
        val batches = buildBatches(context, lineItems)
        if (batches.isEmpty()) {
            onNotesPrintedMapReady(null)
            return
        }

        FirebaseFirestore.getInstance()
            .collection("Orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { doc ->
                val orderNum = doc.getLong("orderNumber")
                val tableRaw = doc.getString("tableName")?.trim()?.takeIf { it.isNotEmpty() }
                val tableDisplay = tableRaw ?: "-"
                val orderTypeRaw = doc.getString("orderType")
                val customerDisplayName = doc.getString("customerName")?.trim()?.takeIf { it.isNotEmpty() }
                val isKitchenDeltaChit = doc.getTimestamp("lastKitchenSentAt") != null
                val genericNotes = KitchenTicketBuilder.readOrderNotes(doc)
                @Suppress("UNCHECKED_CAST")
                val notesByLabel = (doc.get("kitchenNotesByLabel") as? Map<String, String>) ?: emptyMap()
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

                val mergeNotesPrinted = kitchenNotesLastPrintedFromOrder(doc).toMutableMap()

                for (batch in batches) {
                    if (batch.ticketItems.isEmpty()) continue
                    val printerTitle = batch.printer.name.trim().ifEmpty { "KITCHEN" }
                    val batchIp = batch.printer.ipAddress
                    val mapKey = printerNotesMapKey(batch.printer)
                    val style = PrinterKitchenStyleCache.styleForIp(batchIp)
                    val cmdSet = PrinterKitchenStyleCache.commandSetForKitchenLan(batchIp, batch.printer.modelLine)
                    val fullResolvedNotes = resolveBatchNotes(genericNotes, notesByLabel, batch.printer)
                    val normalized = fullResolvedNotes?.trim().orEmpty()
                    val lastPrinted = mergeNotesPrinted[mapKey]?.trim().orEmpty()
                    mergeNotesPrinted[mapKey] = normalized
                    val notesForTicket = if (normalized == lastPrinted) {
                        null
                    } else {
                        fullResolvedNotes?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    val segments = KitchenTicketBuilder.buildTicketSegments(
                        printerTitle = printerTitle,
                        orderNumber = orderNum,
                        orderTypeRaw = orderTypeRaw,
                        orderTypeDisplay = KitchenTicketBuilder.formatOrderTypeForTicket(orderTypeRaw),
                        tableDisplay = tableDisplay,
                        timeFormatted = timeStr,
                        customerDisplayName = customerDisplayName,
                        isKitchenDeltaChit = isKitchenDeltaChit,
                        orderNotes = notesForTicket,
                        items = batch.ticketItems,
                        style = style,
                    )
                    EscPosPrinter.printKitchenChitToLan(
                        context,
                        ipAddress = batchIp,
                        port = 9100,
                        segments = segments,
                        commandSet = cmdSet,
                    )
                }
                onNotesPrintedMapReady(mergeNotesPrinted)
            }
            .addOnFailureListener {
                onNotesPrintedMapReady(null)
            }
    }

    private fun buildBatches(
        context: Context,
        lineItems: List<KitchenTicketLineInput>,
    ): List<KitchenPrintBatch> {
        val mode = PrintingSettingsCache.printItemFilterMode
        val allTargets = kitchenRoutingPrinters(context)
        if (allTargets.isEmpty()) return emptyList()

        return when (mode) {
            PrintingSettingsFirestore.ALL_ITEMS -> {
                allTargets.map { p ->
                    KitchenPrintBatch(printer = p, ticketItems = lineItems.toList())
                }
            }
            else -> {
                val byIp = linkedMapOf<String, MutableList<KitchenTicketLineInput>>()
                for (line in lineItems) {
                    val label = line.routingLabel?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val key = PrinterLabelKey.normalize(label)
                    for (p in allTargets) {
                        if (p.labels.any { PrinterLabelKey.normalize(it) == key }) {
                            byIp.getOrPut(p.ipAddress.trim()) { mutableListOf() }.add(line)
                        }
                    }
                }
                byIp.mapNotNull { (ip, items) ->
                    val printer = allTargets.firstOrNull { it.ipAddress.trim() == ip } ?: return@mapNotNull null
                    KitchenPrintBatch(printer, items)
                }
            }
        }
    }

    /**
     * Kitchen-type printers plus receipt-type printers that have routing labels (same rule as label pickers).
     */
    private fun kitchenRoutingPrinters(context: Context): List<SelectedPrinterDisplay> {
        val byIp = linkedMapOf<String, SelectedPrinterDisplay>()
        for (p in SelectedPrinterPrefs.getAll(context, PrinterDeviceType.KITCHEN)) {
            byIp[p.ipAddress.trim()] = p
        }
        for (p in SelectedPrinterPrefs.getAll(context, PrinterDeviceType.RECEIPT)) {
            if (p.labels.isEmpty()) continue
            val ip = p.ipAddress.trim()
            if (ip.isEmpty()) continue
            if (!byIp.containsKey(ip)) {
                byIp[ip] = p
            }
        }
        return byIp.values.toList()
    }

    /**
     * Picks the notes relevant to a specific printer batch.
     * Label-specific notes ([kitchenNotesByLabel]) take priority; if none match the printer's
     * routing labels, falls back to the generic order-level notes.
     */
    private fun resolveBatchNotes(
        genericNotes: String?,
        notesByLabel: Map<String, String>,
        printer: SelectedPrinterDisplay,
    ): String? {
        if (notesByLabel.isNotEmpty()) {
            val printerLabelKeys = printer.labels.map { PrinterLabelKey.normalize(it) }.toSet()
            val matched = notesByLabel.entries
                .filter { (key, _) -> PrinterLabelKey.normalize(key) in printerLabelKeys }
                .map { it.value.trim() }
                .filter { it.isNotEmpty() }
            if (matched.isNotEmpty()) return matched.joinToString("\n")
        }
        return genericNotes
    }

    private fun parseModifiersFromFirestore(raw: Any?): List<OrderModifier> {
        val list = raw as? List<*> ?: return emptyList()
        val out = mutableListOf<OrderModifier>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val name = map["name"]?.toString()
                ?: map["first"]?.toString()
                ?: continue
            val action = map["action"]?.toString() ?: "ADD"
            val price = if (action == "REMOVE") {
                0.0
            } else {
                (map["price"] as? Number)?.toDouble()
                    ?: (map["second"] as? Number)?.toDouble()
                    ?: 0.0
            }
            val groupId = map["groupId"]?.toString() ?: ""
            val groupName = map["groupName"]?.toString() ?: ""
            val children = parseModifiersFromFirestore(map["children"])
            out.add(OrderModifier(name, action, price, groupId, groupName, children))
        }
        return out
    }
}
