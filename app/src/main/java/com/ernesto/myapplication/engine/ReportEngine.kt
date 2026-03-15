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

data class CardBrandSale(
    val brand: String,
    val totalCents: Long,
    val transactionCount: Int
)

data class EmployeeMetrics(
    val employeeName: String,
    val salesCents: Long = 0L,
    val orderCount: Int = 0,
    val tipsCents: Long = 0L,
    val tipsCount: Int = 0,
    val refundsCents: Long = 0L,
    val refundsCount: Int = 0,
    val voidsCount: Int = 0
)

data class DailySalesSummary(
    val grossSales: Double,
    val taxCollected: Double,
    val tipsCollected: Double,
    val netSales: Double,
    val totalTransactions: Int,
    val averageTicket: Double,
    val refunds: Double,
    val discounts: Double,
    val voidedItems: Int,
    val itemsSold: Int,
    val cashPayments: Double,
    val creditPayments: Double,
    val debitPayments: Double
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

    @Suppress("UNCHECKED_CAST")
    private fun paymentsByMethod(doc: DocumentSnapshot): Map<String, Long> {
        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        val result = mutableMapOf<String, Long>()
        for (p in payments) {
            if ((p["status"] as? String) == "VOIDED") continue
            val method = ((p["paymentType"] as? String) ?: "OTHER").uppercase()
            val cents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
            result[method] = (result[method] ?: 0L) + cents
        }
        return result
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
        var discountCents = 0L
        var voidedItems = 0
        var itemsSold = 0
        var cashCents = 0L
        var creditCents = 0L
        var debitCents = 0L
        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }

            val gross = grossCents / 100.0
            val tax = taxCents / 100.0
            val tips = tipCents / 100.0
            val net = (grossCents - taxCents - discountCents) / 100.0
            val avg = if (saleCount > 0) gross / saleCount else 0.0

            onSuccess(
                DailySalesSummary(
                    grossSales = gross,
                    taxCollected = tax,
                    tipsCollected = tips,
                    netSales = net,
                    totalTransactions = saleCount,
                    averageTicket = avg,
                    refunds = refundCents / 100.0,
                    discounts = discountCents / 100.0,
                    voidedItems = voidedItems,
                    itemsSold = itemsSold,
                    cashPayments = cashCents / 100.0,
                    creditPayments = creditCents / 100.0,
                    debitPayments = debitCents / 100.0
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
                            val methods = paymentsByMethod(doc)
                            cashCents += methods["CASH"] ?: 0L
                            creditCents += methods["CREDIT"] ?: 0L
                            debitCents += methods["DEBIT"] ?: 0L
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
                val itemQueries = mutableListOf<Pair<String, String>>()

                for (doc in snap.documents) {
                    val status = doc.getString("status") ?: continue

                    if (status == "CLOSED") {
                        @Suppress("UNCHECKED_CAST")
                        val breakdown = doc.get("taxBreakdown") as? List<Map<String, Any>>
                        breakdown?.forEach { entry ->
                            taxCents += (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                        }
                        tipCents += doc.getLong("tipAmountInCents") ?: 0L
                        discountCents += doc.getLong("discountInCents") ?: 0L
                    }

                    if (status == "CLOSED" || status == "VOIDED") {
                        itemQueries.add(doc.id to status)
                    }
                }

                if (itemQueries.isEmpty()) {
                    tryComplete()
                } else {
                    remaining += itemQueries.size
                    tryComplete()
                    for ((orderId, status) in itemQueries) {
                        db.collection("Orders").document(orderId)
                            .collection("items").get()
                            .addOnSuccessListener { itemsSnap ->
                                for (itemDoc in itemsSnap.documents) {
                                    val qty = (itemDoc.getLong("quantity") ?: 1L).toInt()
                                    if (status == "VOIDED") {
                                        voidedItems += qty
                                    } else {
                                        itemsSold += qty
                                    }
                                }
                                tryComplete()
                            }
                            .addOnFailureListener { e ->
                                firstError = firstError ?: e; tryComplete()
                            }
                    }
                }
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

    private fun normalizeCardBrand(raw: String): String = when {
        raw.contains("VISA", ignoreCase = true) -> "Visa"
        raw.contains("MASTER", ignoreCase = true) -> "Mastercard"
        raw.contains("AMEX", ignoreCase = true) || raw.contains("AMERICAN", ignoreCase = true) -> "Amex"
        raw.contains("DISCOVER", ignoreCase = true) -> "Discover"
        raw.isBlank() -> "Other"
        else -> raw
    }

    @Suppress("UNCHECKED_CAST")
    fun getSalesByCardBrand(
        onSuccess: (List<CardBrandSale>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dayRange()

        queryTransactions(start, end,
            onSuccess = { snap ->
                val brandMap = mutableMapOf<String, Pair<Long, Int>>()

                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    when (doc.getString("type")) {
                        "SALE", "CAPTURE" -> {
                            val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                            for (p in payments) {
                                if ((p["status"] as? String) == "VOIDED") continue
                                val pt = ((p["paymentType"] as? String) ?: "").uppercase()
                                if (pt == "CASH") continue
                                val brand = normalizeCardBrand((p["cardBrand"] as? String) ?: "")
                                val cents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
                                val cur = brandMap[brand] ?: (0L to 0)
                                brandMap[brand] = (cur.first + cents) to (cur.second + 1)
                            }
                        }
                    }
                }

                val result = brandMap.map { (brand, pair) ->
                    CardBrandSale(brand, pair.first, pair.second)
                }.sortedByDescending { it.totalCents }

                onSuccess(result)
            },
            onFailure = onFailure
        )
    }

    fun getEmployeeReport(
        onSuccess: (List<EmployeeMetrics>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dayRange()

        class Accum(val name: String) {
            var salesCents = 0L; var orderCount = 0
            var tipsCents = 0L; var tipsCount = 0
            var refundsCents = 0L; var refundsCount = 0
            var voidsCount = 0
            fun toMetrics() = EmployeeMetrics(name, salesCents, orderCount,
                tipsCents, tipsCount, refundsCents, refundsCount, voidsCount)
        }

        val map = mutableMapOf<String, Accum>()
        fun accum(name: String) = map.getOrPut(name) { Accum(name) }

        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }
            val result = map.values.map { it.toMetrics() }.sortedByDescending { it.salesCents }
            onSuccess(result)
        }

        db.collection("Orders")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener { snap ->
                for (doc in snap.documents) {
                    val status = doc.getString("status") ?: continue

                    if (status == "CLOSED") {
                        val emp = doc.getString("employeeName")?.takeIf { it.isNotBlank() } ?: continue
                        val a = accum(emp)
                        a.salesCents += doc.getLong("totalPaidInCents") ?: 0L
                        a.orderCount++
                        val tips = doc.getLong("tipAmountInCents") ?: 0L
                        if (tips > 0L) { a.tipsCents += tips; a.tipsCount++ }
                    }

                    if (status == "VOIDED") {
                        val emp = doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: continue
                        accum(emp).voidsCount++
                    }
                }
                tryComplete()
            }
            .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }

        queryTransactions(start, end,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    if (doc.getString("type") == "REFUND") {
                        val emp = doc.getString("refundedBy")?.takeIf { it.isNotBlank() } ?: continue
                        val a = accum(emp)
                        a.refundsCents += doc.getLong("amountInCents")
                            ?: ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
                        a.refundsCount++
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() }
        )
    }
}
