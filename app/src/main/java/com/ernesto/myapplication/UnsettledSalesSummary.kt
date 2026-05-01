package com.ernesto.myapplication

import com.google.firebase.firestore.QuerySnapshot
import kotlin.math.abs

/**
 * Dashboard / POS "current sales" net: unsettled [SALE]/[CAPTURE] minus [REFUND].
 * Matches [BatchManagementActivity] settle logic expectations.
 */
object UnsettledSalesSummary {

    fun compute(documents: QuerySnapshot): Pair<Double, Int> {
        var total = 0.0
        var count = 0
        for (doc in documents) {
            val voided = doc.getBoolean("voided") ?: false
            if (voided) continue

            val type = doc.getString("type") ?: "SALE"
            if (type == "PRE_AUTH") continue

            if (type == "SALE" || type == "CAPTURE") {
                val totalPaidInCentsField = doc.getLong("totalPaidInCents")
                if (totalPaidInCentsField != null) {
                    total += totalPaidInCentsField / 100.0
                } else {
                    val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                    var totalCents = 0L
                    for (p in payments) {
                        val map = p as? Map<*, *> ?: continue
                        val status = (map["status"] as? String) ?: ""
                        if (status.equals("VOIDED", ignoreCase = true)) continue
                        val amountInCents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                        totalCents += amountInCents
                    }
                    if (totalCents > 0L) {
                        total += totalCents / 100.0
                    } else {
                        val amount = doc.getDouble("amount")
                            ?: doc.getDouble("totalPaid")
                            ?: 0.0
                        total += amount
                    }
                }
            } else if (type == "REFUND") {
                val cents = doc.getLong("amountInCents")
                val refundDollars = if (cents != null && cents > 0L) {
                    cents / 100.0
                } else {
                    doc.getDouble("amount") ?: 0.0
                }
                total -= refundDollars
            }
            count++
        }
        val finalTotal = if (abs(total) < 0.005) 0.0 else total
        return Pair(finalTotal, count)
    }
}
