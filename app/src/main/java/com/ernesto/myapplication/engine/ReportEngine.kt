package com.ernesto.myapplication.engine

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

data class SalesByOrderType(
    val dineInCents: Long = 0L,
    val toGoCents: Long = 0L,
    val barCents: Long = 0L
) {
    val totalCents: Long get() = dineInCents + toGoCents + barCents

    fun percentOf(partCents: Long): Int {
        if (totalCents == 0L) return 0
        return ((partCents * 100.0) / totalCents).roundToInt()
    }
}

data class DailySalesSummary(
    val grossSales: Double,
    val taxCollected: Double,
    val tipsCollected: Double,
    val netSales: Double,
    val totalTransactions: Int,
    val averageTicket: Double,
    val refunds: Double
)

class ReportEngine(private val db: FirebaseFirestore) {

    fun dayRange(date: Date = Date()): Pair<Date, Date> {
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.time
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.time
    }

    @Suppress("UNCHECKED_CAST")
    private fun sumPaymentsInCents(doc: DocumentSnapshot): Long {
        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        var total = 0L
        for (p in payments) {
            if ((p["status"] as? String) == "VOIDED") continue
            total += (p["amountInCents"] as? Number)?.toLong() ?: 0L
        }
        if (total > 0L) return total
        return doc.getLong("totalPaidInCents")
            ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
    }

    fun queryTransactions(
        start: Date,
        end: Date,
        onSuccess: (QuerySnapshot) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        db.collection("Transactions")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener(onSuccess)
            .addOnFailureListener(onFailure)
    }

    fun getDailySalesSummary(
        onSuccess: (DailySalesSummary) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dayRange()

        var grossCents = 0L
        var refundCents = 0L
        var saleCount = 0
        var taxCents = 0L
        var tipCents = 0L
        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }

            val gross = grossCents / 100.0
            val tax = taxCents / 100.0
            val tips = tipCents / 100.0
            val net = (grossCents - taxCents) / 100.0
            val avg = if (saleCount > 0) gross / saleCount else 0.0

            onSuccess(
                DailySalesSummary(
                    grossSales = gross,
                    taxCollected = tax,
                    tipsCollected = tips,
                    netSales = net,
                    totalTransactions = saleCount,
                    averageTicket = avg,
                    refunds = refundCents / 100.0
                )
            )
        }

        queryTransactions(start, end,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    when (doc.getString("type")) {
                        "SALE", "CAPTURE" -> {
                            val txCents = sumPaymentsInCents(doc)
                            if (txCents > 0L) {
                                grossCents += txCents
                                saleCount++
                            }
                        }
                        "REFUND" -> {
                            refundCents += doc.getLong("amountInCents")
                                ?: ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() }
        )

        db.collection("Orders")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener { snap ->
                for (doc in snap.documents) {
                    if (doc.getString("status") != "CLOSED") continue

                    @Suppress("UNCHECKED_CAST")
                    val breakdown = doc.get("taxBreakdown") as? List<Map<String, Any>>
                    breakdown?.forEach { entry ->
                        taxCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                    }

                    tipCents += doc.getLong("tipAmountInCents") ?: 0L
                }
                tryComplete()
            }
            .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
    }

    /**
     * Aggregates approved (non-voided SALE/CAPTURE) transaction amounts by order type
     * (DINE_IN, TO_GO, BAR). Accepts arbitrary date ranges to support daily, weekly,
     * or monthly reports and future chart visualisation.
     */
    fun getSalesByOrderType(
        startOfDay: Date,
        endOfDay: Date,
        onSuccess: (SalesByOrderType) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val txByOrder = mutableMapOf<String, Long>()
        val orderTypes = mutableMapOf<String, String>()
        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }

            var dineInCents = 0L
            var toGoCents = 0L
            var barCents = 0L

            for ((orderId, cents) in txByOrder) {
                when (orderTypes[orderId]) {
                    "DINE_IN" -> dineInCents += cents
                    "TO_GO"   -> toGoCents += cents
                    "BAR"     -> barCents += cents
                }
            }

            onSuccess(
                SalesByOrderType(
                    dineInCents = dineInCents,
                    toGoCents = toGoCents,
                    barCents = barCents
                )
            )
        }

        queryTransactions(startOfDay, endOfDay,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    when (doc.getString("type")) {
                        "SALE", "CAPTURE" -> {
                            val orderId = doc.getString("orderId") ?: continue
                            val cents = sumPaymentsInCents(doc)
                            if (cents > 0L) {
                                txByOrder[orderId] = (txByOrder[orderId] ?: 0L) + cents
                            }
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() }
        )

        db.collection("Orders")
            .whereGreaterThanOrEqualTo("createdAt", startOfDay)
            .whereLessThan("createdAt", endOfDay)
            .get()
            .addOnSuccessListener { snap ->
                for (doc in snap.documents) {
                    orderTypes[doc.id] = doc.getString("orderType") ?: ""
                }
                tryComplete()
            }
            .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
    }
}
