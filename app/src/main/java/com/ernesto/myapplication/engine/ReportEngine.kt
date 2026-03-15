package com.ernesto.myapplication.engine

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.util.Calendar
import java.util.Date

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
}
