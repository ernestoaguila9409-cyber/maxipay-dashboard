package com.ernesto.myapplication.engine

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Builds per-split subtotal, discount, tax, and total from order data.
 */
object SplitReceiptCalculator {

    data class OrderMoney(
        val subtotalCents: Long,
        val discountTotalCents: Long,
        val taxTotalCents: Long,
        val tipCents: Long,
        val orderTotalCents: Long,
        val taxBreakdown: List<Map<String, Any>>,
        val appliedDiscounts: List<Map<String, Any>>
    )

    @Suppress("UNCHECKED_CAST")
    fun readOrderMoney(orderDoc: DocumentSnapshot): OrderMoney {
        val totalInCents = orderDoc.getLong("totalInCents") ?: 0L
        val tipAmountInCents = orderDoc.getLong("tipAmountInCents") ?: 0L
        val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
        val taxBreakdown = orderDoc.get("taxBreakdown") as? List<Map<String, Any>> ?: emptyList()
        var taxTotalCents = 0L
        for (entry in taxBreakdown) {
            taxTotalCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
        }
        val subtotalCents = totalInCents + discountInCents - taxTotalCents - tipAmountInCents
        val appliedDiscounts = orderDoc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
        return OrderMoney(
            subtotalCents = subtotalCents,
            discountTotalCents = discountInCents,
            taxTotalCents = taxTotalCents,
            tipCents = tipAmountInCents,
            orderTotalCents = totalInCents,
            taxBreakdown = taxBreakdown,
            appliedDiscounts = appliedDiscounts
        )
    }

    /** Even split of [total] across [parts]; last index receives remainder. */
    private fun shareWithLastCorrection(total: Long, parts: Int, index0Based: Int): Long {
        if (parts <= 0) return 0L
        val base = total / parts
        val last = total - base * (parts - 1)
        return if (index0Based == parts - 1) last else base
    }

    /**
     * Allocate [pool] across [parts] buckets proportionally to [weights]; last bucket gets remainder.
     * If all weights are zero, falls back to even split of [pool].
     */
    private fun allocateProportional(pool: Long, weights: LongArray, index0Based: Int): Long {
        if (pool == 0L) return 0L
        val sumW = weights.sum()
        if (sumW <= 0L) return shareWithLastCorrection(pool, weights.size, index0Based)
        val parts = weights.size
        if (index0Based == parts - 1) {
            var acc = 0L
            for (i in 0 until parts - 1) {
                acc += kotlin.math.round(pool * weights[i].toDouble() / sumW).toLong()
            }
            return pool - acc
        }
        return kotlin.math.round(pool * weights[index0Based].toDouble() / sumW).toLong()
    }

    private fun taxLinesForAmounts(
        om: OrderMoney,
        guestTaxCents: Long
    ): List<Pair<String, Long>> {
        if (guestTaxCents <= 0L || om.taxBreakdown.isEmpty()) {
            return if (guestTaxCents > 0) listOf("Tax" to guestTaxCents) else emptyList()
        }
        val weights = DoubleArray(om.taxBreakdown.size)
        var sum = 0.0
        for (i in om.taxBreakdown.indices) {
            val a = (om.taxBreakdown[i]["amountInCents"] as? Number)?.toLong() ?: 0L
            weights[i] = a.toDouble()
            sum += weights[i]
        }
        if (sum <= 0.0) return listOf("Tax" to guestTaxCents)
        val lines = mutableListOf<Pair<String, Long>>()
        var allocated = 0L
        for (i in om.taxBreakdown.indices) {
            val name = om.taxBreakdown[i]["name"]?.toString() ?: "Tax"
            val tRate = (om.taxBreakdown[i]["rate"] as? Number)?.toDouble()
            val tType = om.taxBreakdown[i]["taxType"]?.toString()
            val label = DiscountDisplay.formatTaxLabel(name, tType, tRate)
            val share = if (i == om.taxBreakdown.lastIndex) {
                guestTaxCents - allocated
            } else {
                val raw = kotlin.math.round(guestTaxCents * weights[i] / sum).toLong()
                allocated += raw
                raw
            }
            if (share > 0L) lines.add(label to share)
        }
        return lines
    }

    /**
     * Even split when there are no positive line items: fall back to aggregate subtotal only.
     */
    private fun computeEvenSplitAggregateOnly(
        orderDoc: DocumentSnapshot,
        splitIndex1Based: Int,
        totalSplits: Int,
        chargedAmountCents: Long,
        paymentMethod: String
    ): SplitReceiptPayload {
        val om = readOrderMoney(orderDoc)
        val n = totalSplits.coerceAtLeast(1)
        val idx0 = (splitIndex1Based - 1).coerceIn(0, n - 1)

        val subParts = LongArray(n) { shareWithLastCorrection(om.subtotalCents, n, it) }
        val discParts = LongArray(n) {
            allocateProportional(om.discountTotalCents, subParts, it)
        }
        val taxParts = LongArray(n) {
            allocateProportional(om.taxTotalCents, subParts, it)
        }
        val tipParts = LongArray(n) {
            allocateProportional(om.tipCents, subParts, it)
        }

        var sub = subParts[idx0]
        var disc = discParts[idx0]
        var tax = taxParts[idx0]
        var tip = tipParts[idx0]
        val calcTotal = sub - disc + tax + tip
        val delta = chargedAmountCents - calcTotal
        if (delta != 0L) {
            tax += delta
        }

        val guestLabel = "Guest $splitIndex1Based"
        val note = "Shared items (split $splitIndex1Based of $totalSplits)"
        val taxLines = taxLinesForAmounts(om, tax)

        return SplitReceiptPayload(
            splitIndex = splitIndex1Based,
            totalSplits = n,
            guestLabel = guestLabel,
            items = emptyList(),
            sharedItemsNote = note,
            subtotalInCents = sub,
            discountInCents = disc,
            taxInCents = tax,
            taxLines = taxLines,
            tipInCents = tip,
            totalInCents = chargedAmountCents,
            paymentMethod = paymentMethod
        )
    }

    /**
     * Each order line is split across [totalSplits] guests; guest [splitIndex1Based] gets
     * [shareWithLastCorrection] per line (last guest absorbs line remainder).
     * Discount / tax / tip use weights = each guest's sum of line shares so totals stay consistent.
     */
    fun computeEvenSplit(
        orderDoc: DocumentSnapshot,
        itemDocs: List<DocumentSnapshot>,
        splitIndex1Based: Int,
        totalSplits: Int,
        chargedAmountCents: Long,
        paymentMethod: String
    ): SplitReceiptPayload {
        val itemLines = itemDocs.filter { (it.getLong("lineTotalInCents") ?: 0L) > 0L }
        if (itemLines.isEmpty()) {
            return computeEvenSplitAggregateOnly(
                orderDoc, splitIndex1Based, totalSplits, chargedAmountCents, paymentMethod
            )
        }

        val om = readOrderMoney(orderDoc)
        val n = totalSplits.coerceAtLeast(1)
        val idx0 = (splitIndex1Based - 1).coerceIn(0, n - 1)

        val guestSubs = LongArray(n) { g ->
            itemLines.sumOf { doc ->
                val lt = doc.getLong("lineTotalInCents") ?: 0L
                shareWithLastCorrection(lt, n, g)
            }
        }

        val items = mutableListOf<SplitReceiptLine>()
        for (doc in itemLines) {
            val lt = doc.getLong("lineTotalInCents") ?: 0L
            val share = shareWithLastCorrection(lt, n, idx0)
            val baseName = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val displayName = if (qty > 1) "$baseName (Qty: $qty)" else baseName
            items.add(
                SplitReceiptLine(
                    name = displayName,
                    quantity = 1,
                    lineTotalInCents = share,
                    originalItemName = displayName,
                    originalLineTotalInCents = lt,
                    splitIndex = splitIndex1Based,
                    totalSplits = n
                )
            )
        }

        var sub = guestSubs[idx0]
        var disc = allocateProportional(om.discountTotalCents, guestSubs, idx0)
        var tax = allocateProportional(om.taxTotalCents, guestSubs, idx0)
        var tip = allocateProportional(om.tipCents, guestSubs, idx0)
        val calcTotal = sub - disc + tax + tip
        val delta = chargedAmountCents - calcTotal
        if (delta != 0L) {
            tax += delta
        }

        val guestLabel = "Guest $splitIndex1Based"
        val taxLines = taxLinesForAmounts(om, tax)

        return SplitReceiptPayload(
            splitIndex = splitIndex1Based,
            totalSplits = n,
            guestLabel = guestLabel,
            items = items,
            sharedItemsNote = null,
            subtotalInCents = sub,
            discountInCents = disc,
            taxInCents = tax,
            taxLines = taxLines,
            tipInCents = tip,
            totalInCents = chargedAmountCents,
            paymentMethod = paymentMethod
        )
    }

    /** Pay amount for assigned lines (subtotal − discount + tax + tip), before any payment rounding. */
    fun shareTotalCentsForLineKeys(
        orderDoc: DocumentSnapshot,
        itemDocs: List<DocumentSnapshot>,
        lineKeysForGuest: Set<String>
    ): Long {
        val om = readOrderMoney(orderDoc)
        val keys = lineKeysForGuest.toSet()
        var subGuest = 0L
        var taxFromLines = 0L
        for (doc in itemDocs) {
            if (doc.id !in keys) continue
            subGuest += doc.getLong("lineTotalInCents") ?: 0L
            taxFromLines += doc.getLong("lineTaxInCents") ?: 0L
        }
        val discGuest = if (om.subtotalCents > 0L) {
            kotlin.math.round(om.discountTotalCents * subGuest.toDouble() / om.subtotalCents).toLong()
        } else 0L
        val taxGuest = if (taxFromLines > 0L) taxFromLines else {
            if (om.subtotalCents > 0L) {
                kotlin.math.round(om.taxTotalCents * subGuest.toDouble() / om.subtotalCents).toLong()
            } else 0L
        }
        val tipGuest = if (om.subtotalCents > 0L) {
            kotlin.math.round(om.tipCents * subGuest.toDouble() / om.subtotalCents).toLong()
        } else 0L
        return subGuest - discGuest + taxGuest + tipGuest
    }

    fun computeByItemsSplit(
        orderDoc: DocumentSnapshot,
        itemDocs: List<DocumentSnapshot>,
        lineKeysForGuest: Set<String>,
        splitIndex1Based: Int,
        totalSplits: Int,
        chargedAmountCents: Long,
        paymentMethod: String,
        guestLabel: String
    ): SplitReceiptPayload {
        val om = readOrderMoney(orderDoc)
        val keys = lineKeysForGuest.toSet()
        val lines = mutableListOf<SplitReceiptLine>()
        var subGuest = 0L
        var taxFromLines = 0L

        for (doc in itemDocs) {
            if (doc.id !in keys) continue
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val qty = (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt()
            val lineTotal = doc.getLong("lineTotalInCents") ?: 0L
            subGuest += lineTotal
            taxFromLines += doc.getLong("lineTaxInCents") ?: 0L
            lines.add(SplitReceiptLine(name, qty, lineTotal))
        }

        val discGuest = if (om.subtotalCents > 0L) {
            kotlin.math.round(om.discountTotalCents * subGuest.toDouble() / om.subtotalCents).toLong()
        } else 0L

        var taxGuest = if (taxFromLines > 0L) taxFromLines else {
            if (om.subtotalCents > 0L) {
                kotlin.math.round(om.taxTotalCents * subGuest.toDouble() / om.subtotalCents).toLong()
            } else 0L
        }

        var tipGuest = if (om.subtotalCents > 0L) {
            kotlin.math.round(om.tipCents * subGuest.toDouble() / om.subtotalCents).toLong()
        } else 0L

        var calcTotal = subGuest - discGuest + taxGuest + tipGuest
        val delta = chargedAmountCents - calcTotal
        if (delta != 0L) {
            taxGuest += delta
            calcTotal = subGuest - discGuest + taxGuest + tipGuest
        }

        val taxLines = taxLinesForAmounts(om, taxGuest)

        return SplitReceiptPayload(
            splitIndex = splitIndex1Based,
            totalSplits = totalSplits.coerceAtLeast(1),
            guestLabel = guestLabel,
            items = lines,
            sharedItemsNote = null,
            subtotalInCents = subGuest,
            discountInCents = discGuest,
            taxInCents = taxGuest,
            taxLines = taxLines,
            tipInCents = tipGuest,
            totalInCents = chargedAmountCents,
            paymentMethod = paymentMethod
        )
    }
}
