package com.ernesto.kds.data

import java.util.Date
import java.util.Locale

/**
 * Per-line [OrderItem.kdsStatus] drives START/READY on each tablet when tickets are split
 * (e.g. food vs drinks). Order-level [kitchenStatus] must not mirror one station for all.
 */
object KdsLineWorkflow {
    private const val PREPARING = "PREPARING"
    private const val READY = "READY"

    private fun norm(s: String?) = s?.trim()?.uppercase(Locale.US).orEmpty()

    /**
     * Drops lines already **READY** on KDS so a later kitchen send on the same order only shows
     * work still pending (incremental ticket, not the full history).
     */
    fun linesOmittingKitchenReady(lines: List<OrderItem>): List<OrderItem> =
        lines.filter { norm(it.kdsStatus) != READY }

    /** START vs READY for the lines currently shown on this tablet. */
    fun deriveCardStatusFromVisibleLines(lines: List<OrderItem>): String {
        if (lines.isEmpty()) return "OPEN"
        val st = lines.map { norm(it.kdsStatus) }
        if (st.any { it == PREPARING }) return PREPARING
        if (st.isNotEmpty() && st.all { it == READY }) return READY
        return "OPEN"
    }

    /** Elapsed timer: earliest line prep start among visible PREPARING lines. */
    fun derivePrepStartedAtFromVisibleLines(lines: List<OrderItem>, cardStatus: String): Date? {
        if (cardStatus != PREPARING) return null
        return lines
            .filter { norm(it.kdsStatus) == PREPARING }
            .mapNotNull { it.kdsStartedAt }
            .minByOrNull { it.time }
    }

    /**
     * After printer routing filter, merge line [kdsStatus] into the card model.
     * If no line has [OrderItem.kdsStatus] yet, use [legacyDisplayStatus] / order timestamps (older tickets).
     */
    fun deriveCardStateForPrinterFilteredItems(
        items: List<OrderItem>,
        orderKitchenStartedAt: Date?,
        legacyDisplayStatus: String,
    ): Pair<String, Date?> {
        val anyLineKds = items.any { norm(it.kdsStatus).isNotEmpty() }
        if (!anyLineKds) {
            val anchor = if (legacyDisplayStatus.equals(PREPARING, ignoreCase = true)) {
                orderKitchenStartedAt
            } else {
                null
            }
            return legacyDisplayStatus to anchor
        }
        val status = deriveCardStatusFromVisibleLines(items)
        val anchor = derivePrepStartedAtFromVisibleLines(items, status)
        return status to anchor
    }
}
