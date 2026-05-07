package com.ernesto.myapplication

/**
 * Normalizes the Firestore `refundedBy` string for UI and receipts.
 * Web/server refunds store `Dashboard: {email or uid}`; POS stores the employee display name.
 */
object RefundAttributionFormat {
    private val dashboardColonPrefix = Regex("^Dashboard:\\s*", RegexOption.IGNORE_CASE)

    fun forDisplay(refundedByFirestore: String): String {
        val raw = refundedByFirestore.trim()
        if (raw.isEmpty()) return raw
        val match = dashboardColonPrefix.find(raw) ?: return raw
        val detail = raw.substring(match.range.last + 1).trim()
        return if (detail.isEmpty()) "Dashboard" else "Dashboard — $detail"
    }
}
