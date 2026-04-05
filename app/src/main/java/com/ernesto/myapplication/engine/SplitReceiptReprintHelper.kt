package com.ernesto.myapplication.engine

/**
 * Detects stored per-person split receipts on sale transaction payments for reprint flows.
 */
object SplitReceiptReprintHelper {

    @Suppress("UNCHECKED_CAST")
    fun payloadsOrderedBySplitIndex(payments: List<Map<String, Any>>): List<SplitReceiptPayload>? {
        val payloads = mutableListOf<SplitReceiptPayload>()
        for (p in payments) {
            val sr = p["splitReceipt"] as? Map<String, Any> ?: continue
            val parsed = SplitReceiptPayload.fromFirestoreMap(sr) ?: continue
            payloads.add(parsed)
        }
        if (payloads.isEmpty()) return null
        val totalSplits = payloads.maxOfOrNull { it.totalSplits } ?: return null
        if (totalSplits < 2) return null
        return payloads
            .filter { it.totalSplits == totalSplits }
            .sortedBy { it.splitIndex }
            .distinctBy { it.splitIndex }
            .takeIf { it.isNotEmpty() }
    }
}
