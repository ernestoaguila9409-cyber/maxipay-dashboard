package com.ernesto.myapplication.engine

/**
 * Per-split receipt payload stored on each payment entry and used for print / email.
 */
data class SplitReceiptLine(
    val name: String,
    val quantity: Int,
    /** This guest's share of the line (even split) or full line total (split by items). */
    val lineTotalInCents: Long,
    /** Set for even split: display name of the source line (often includes qty). */
    val originalItemName: String? = null,
    /** Full line total before splitting (even split only). */
    val originalLineTotalInCents: Long? = null,
    val splitIndex: Int? = null,
    val totalSplits: Int? = null,
    /** Bullet lines from Firestore [modifiers] (split by guest / even split item rows). */
    val modifierLines: List<String> = emptyList()
)

data class SplitReceiptPayload(
    val splitIndex: Int,
    val totalSplits: Int,
    val guestLabel: String,
    val items: List<SplitReceiptLine>,
    /** When non-null, printed instead of listing every item (e.g. even split). */
    val sharedItemsNote: String?,
    val subtotalInCents: Long,
    val discountInCents: Long,
    val taxInCents: Long,
    val taxLines: List<Pair<String, Long>>,
    val tipInCents: Long,
    val totalInCents: Long,
    val paymentMethod: String
) {
    fun toFirestoreMap(): Map<String, Any> {
        val itemsList = items.map {
            val row = mutableMapOf<String, Any>(
                "name" to it.name,
                "quantity" to it.quantity,
                "lineTotalInCents" to it.lineTotalInCents
            )
            if (it.originalItemName != null) row["originalItemName"] = it.originalItemName
            if (it.originalLineTotalInCents != null) row["originalLineTotalInCents"] = it.originalLineTotalInCents
            if (it.splitIndex != null) row["splitIndex"] = it.splitIndex
            if (it.totalSplits != null) row["totalSplits"] = it.totalSplits
            if (it.modifierLines.isNotEmpty()) row["modifierLines"] = it.modifierLines
            row
        }
        val taxLinesList = taxLines.map { mapOf("label" to it.first, "amountInCents" to it.second) }
        return mapOf(
            "splitIndex" to splitIndex,
            "totalSplits" to totalSplits,
            "guestLabel" to guestLabel,
            "items" to itemsList,
            "sharedItemsNote" to (sharedItemsNote ?: ""),
            "subtotalInCents" to subtotalInCents,
            "discountInCents" to discountInCents,
            "taxInCents" to taxInCents,
            "taxLines" to taxLinesList,
            "tipInCents" to tipInCents,
            "totalInCents" to totalInCents,
            "paymentMethod" to paymentMethod
        )
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromFirestoreMap(m: Map<String, Any>): SplitReceiptPayload? {
            val splitIndex = (m["splitIndex"] as? Number)?.toInt() ?: return null
            val totalSplits = (m["totalSplits"] as? Number)?.toInt() ?: return null
            val guestLabel = m["guestLabel"]?.toString() ?: return null
            val itemsRaw = m["items"] as? List<Map<String, Any>> ?: emptyList()
            val items = itemsRaw.mapNotNull { row ->
                val name = row["name"]?.toString() ?: return@mapNotNull null
                val qty = (row["quantity"] as? Number)?.toInt() ?: 1
                val share = (row["lineTotalInCents"] as? Number)?.toLong() ?: 0L
                val origName = row["originalItemName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val origTotal = (row["originalLineTotalInCents"] as? Number)?.toLong()
                val sIdx = (row["splitIndex"] as? Number)?.toInt()
                val tSpl = (row["totalSplits"] as? Number)?.toInt()
                val modLines = (row["modifierLines"] as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: emptyList()
                SplitReceiptLine(
                    name = name,
                    quantity = qty,
                    lineTotalInCents = share,
                    originalItemName = origName,
                    originalLineTotalInCents = origTotal,
                    splitIndex = sIdx,
                    totalSplits = tSpl,
                    modifierLines = modLines
                )
            }
            val note = m["sharedItemsNote"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val subtotal = (m["subtotalInCents"] as? Number)?.toLong() ?: 0L
            val discount = (m["discountInCents"] as? Number)?.toLong() ?: 0L
            val tax = (m["taxInCents"] as? Number)?.toLong() ?: 0L
            val tip = (m["tipInCents"] as? Number)?.toLong() ?: 0L
            val total = (m["totalInCents"] as? Number)?.toLong() ?: 0L
            val method = m["paymentMethod"]?.toString() ?: ""
            val taxLinesRaw = m["taxLines"] as? List<Map<String, Any>> ?: emptyList()
            val taxLines = taxLinesRaw.map { row ->
                (row["label"]?.toString() ?: "Tax") to ((row["amountInCents"] as? Number)?.toLong() ?: 0L)
            }
            return SplitReceiptPayload(
                splitIndex, totalSplits, guestLabel, items, note,
                subtotal, discount, tax, taxLines, tip, total, method
            )
        }
    }
}
