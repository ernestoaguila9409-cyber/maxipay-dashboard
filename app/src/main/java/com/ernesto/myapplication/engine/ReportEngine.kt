package com.ernesto.myapplication.engine

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

/** Per-table breakdown for Dine-In orders (within a section). */
data class DineInTableSale(
    val tableName: String,
    val orderCount: Int,
    val totalCents: Long
)

/** Per-section breakdown for Dine-In orders. Each section contains tables. */
data class DineInSectionSale(
    val sectionName: String,
    val tables: List<DineInTableSale>
)

data class SalesByOrderType(
    val dineInCents: Long = 0L,
    val toGoCents: Long = 0L,
    val barCents: Long = 0L,
    val dineInBySection: List<DineInSectionSale> = emptyList()
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

/** Payment breakdown by method (Cash vs Card). Card = credit + debit. */
data class PaymentMethodBreakdown(
    val cashCents: Long = 0L,
    val cashTxCount: Int = 0,
    val cardCents: Long = 0L,
    val cardTxCount: Int = 0
) {
    val totalCents: Long get() = cashCents + cardCents
    val totalTxCount: Int get() = cashTxCount + cardTxCount
}

/** Extended employee metrics with payment-method breakdown for payments, tips, and refunds. */
data class EmployeeReportData(
    val employeeName: String,
    val salesCents: Long = 0L,
    val orderCount: Int = 0,
    val tipsCents: Long = 0L,
    val tipsCount: Int = 0,
    val refundsCents: Long = 0L,
    val refundsCount: Int = 0,
    val voidsCount: Int = 0,
    val paymentsByMethod: PaymentMethodBreakdown = PaymentMethodBreakdown(),
    val tipsByMethod: PaymentMethodBreakdown = PaymentMethodBreakdown(),
    val refundsByMethod: PaymentMethodBreakdown = PaymentMethodBreakdown()
) {
    fun toMetrics() = EmployeeMetrics(
        employeeName, salesCents, orderCount,
        tipsCents, tipsCount, refundsCents, refundsCount, voidsCount
    )
}

/** Breakdown of a single order type within an hour bucket. */
data class OrderTypeHourlySale(
    val orderType: String,
    val totalCents: Long,
    val orderCount: Int
)

/** Aggregated sales for a single hour (0–23), with per-order-type breakdown. */
data class HourlySale(
    val hour: Int,
    val orderTypes: List<OrderTypeHourlySale>,
    val totalCents: Long,
    val totalOrders: Int
)

/** Tax total for a single order type (Dine-In, To-Go, Bar). */
data class TaxByOrderType(
    val orderType: String,
    val taxCents: Long
)

/** Tax total for a single tax name (e.g. "State Tax (6%)"). */
data class TaxByTaxName(
    val taxName: String,
    val taxCents: Long
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
    val debitPayments: Double,
    val taxesByOrderType: List<TaxByOrderType> = emptyList(),
    val taxesByTaxName: List<TaxByTaxName> = emptyList()
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

    /** Returns (start of startDate, start of day after endDate) for Firestore range queries. */
    fun dateRange(startDate: Date, endDate: Date): Pair<Date, Date> {
        val startCal = Calendar.getInstance().apply {
            time = startDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            time = endDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return startCal.time to endCal.time
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
            val method = (p["paymentType"] as? String ?: p["paymentMethod"] as? String ?: "OTHER").uppercase()
            val cents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
            result[method] = (result[method] ?: 0L) + cents
        }
        return result
    }

    /** Returns (cashCents, cardCents) from a payment method map. Card = credit + debit. */
    private fun toCashCardBreakdown(methods: Map<String, Long>): Pair<Long, Long> {
        val cash = methods["CASH"] ?: 0L
        val card = (methods["CREDIT"] ?: 0L) + (methods["DEBIT"] ?: 0L)
        return cash to card
    }

    /** Returns (cashTxCount, cardTxCount): 1 if this transaction had any cash/card payment. Mixed = (1,1). */
    @Suppress("UNCHECKED_CAST")
    private fun paymentCountsByMethod(doc: DocumentSnapshot): Pair<Int, Int> {
        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        var hasCash = false
        var hasCard = false
        for (p in payments) {
            if ((p["status"] as? String) == "VOIDED") continue
            val method = (p["paymentType"] as? String ?: p["paymentMethod"] as? String ?: "OTHER").uppercase()
            when (method) {
                "CASH" -> hasCash = true
                "CREDIT", "DEBIT" -> hasCard = true
            }
        }
        if (!hasCash && !hasCard && payments.isNotEmpty()) {
            val legacyType = doc.getString("paymentType") ?: doc.getString("paymentMethod") ?: ""
            when (legacyType.uppercase()) {
                "CASH" -> hasCash = true
                "CREDIT", "DEBIT" -> hasCard = true
            }
        }
        return (if (hasCash) 1 else 0) to (if (hasCard) 1 else 0)
    }

    private fun isCashRefund(doc: DocumentSnapshot): Boolean {
        val pt = doc.getString("paymentType") ?: doc.getString("paymentMethod") ?: ""
        return pt.equals("Cash", ignoreCase = true)
    }

    fun getDailySalesSummary(
        startDate: Date = Date(),
        endDate: Date = Date(),
        employeeName: String? = null,
        onSuccess: (DailySalesSummary) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        val validOrderIds = mutableSetOf<String>()
        data class TxRec(val orderId: String?, val gross: Long, val cash: Long, val credit: Long, val debit: Long)
        val saleRecords = mutableListOf<TxRec>()
        val refundRecords = mutableListOf<Pair<String?, Long>>()

        var taxCents = 0L
        var tipCents = 0L
        var discountCents = 0L
        var voidedItems = 0
        var itemsSold = 0
        val taxByOrderTypeAgg = mutableMapOf<String, Long>()
        val taxByNameAgg = mutableMapOf<String, Long>()
        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }

            var grossCents = 0L
            var saleCount = 0
            var cashCents = 0L
            var creditCents = 0L
            var debitCents = 0L
            var refundCents = 0L

            for (rec in saleRecords) {
                if (employeeName != null && rec.orderId != null && rec.orderId !in validOrderIds) continue
                if (rec.gross > 0L) { grossCents += rec.gross; saleCount++ }
                cashCents += rec.cash
                creditCents += rec.credit
                debitCents += rec.debit
            }
            for ((orderId, amount) in refundRecords) {
                if (employeeName != null && orderId != null && orderId !in validOrderIds) continue
                refundCents += amount
            }

            val gross = grossCents / 100.0
            val tax = taxCents / 100.0
            val tips = tipCents / 100.0
            val net = (grossCents - taxCents - discountCents) / 100.0
            val avg = if (saleCount > 0) gross / saleCount else 0.0

            val taxesByOrderType = taxByOrderTypeAgg.map { (ot, cents) ->
                TaxByOrderType(orderType = ot, taxCents = cents)
            }.sortedByDescending { it.taxCents }

            val taxesByTaxName = taxByNameAgg.map { (name, cents) ->
                TaxByTaxName(taxName = name, taxCents = cents)
            }.sortedByDescending { it.taxCents }

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
                    debitPayments = debitCents / 100.0,
                    taxesByOrderType = taxesByOrderType,
                    taxesByTaxName = taxesByTaxName
                )
            )
        }

        queryTransactions(start, end,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    when (doc.getString("type")) {
                        "SALE", "CAPTURE" -> {
                            val orderId = doc.getString("orderId")
                            val txCents = sumPaymentsInCents(doc)
                            val methods = paymentsByMethod(doc)
                            saleRecords.add(TxRec(orderId, txCents, methods["CASH"] ?: 0L, methods["CREDIT"] ?: 0L, methods["DEBIT"] ?: 0L))
                        }
                        "REFUND" -> {
                            val orderId = doc.getString("orderId")
                            refundRecords.add(orderId to (doc.getLong("amountInCents")
                                ?: ((doc.getDouble("amount") ?: 0.0) * 100).toLong()))
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
                    if (employeeName != null && doc.getString("employeeName") != employeeName) continue

                    validOrderIds.add(doc.id)

                    if (status == "CLOSED") {
                        val orderType = doc.getString("orderType") ?: "OTHER"
                        @Suppress("UNCHECKED_CAST")
                        val breakdown = doc.get("taxBreakdown") as? List<Map<String, Any>>
                        breakdown?.forEach { entry ->
                            val cents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                            taxCents += cents
                            val taxName = (entry["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Unknown Tax"
                            taxByOrderTypeAgg[orderType] = (taxByOrderTypeAgg[orderType] ?: 0L) + cents
                            taxByNameAgg[taxName] = (taxByNameAgg[taxName] ?: 0L) + cents
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
        employeeName: String? = null,
        onSuccess: (SalesByOrderType) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        data class OrderInfo(
            val orderType: String,
            val tableId: String?,
            val tableName: String?,
            val sectionId: String?,
            val sectionName: String?
        )
        val txByOrder = mutableMapOf<String, Long>()
        val orderTypes = mutableMapOf<String, String>()
        val orderInfoMap = mutableMapOf<String, OrderInfo>()
        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }

            var dineInCents = 0L
            var toGoCents = 0L
            var barCents = 0L
            // sectionName -> (tableKey -> (cents, orderCount))
            val sectionTableAgg = mutableMapOf<String, MutableMap<String, Pair<Long, Int>>>()

            fun getTableKey(info: OrderInfo?): String = when {
                info != null && (info.tableName?.isNotBlank() == true) -> info.tableName!!
                info != null && (info.tableId?.isNotBlank() == true) -> "Table ${info.tableId}"
                else -> "Unknown"
            }

            fun getSectionKey(info: OrderInfo?): String =
                info?.sectionName?.takeIf { it.isNotBlank() }
                    ?: info?.sectionId?.takeIf { it.isNotBlank() }
                    ?: "Unknown"

            for ((orderId, cents) in txByOrder) {
                when (orderTypes[orderId]) {
                    "DINE_IN" -> {
                        dineInCents += cents
                        val info = orderInfoMap[orderId]
                        val sectionKey = getSectionKey(info)
                        val tableKey = getTableKey(info)
                        val tableMap = sectionTableAgg.getOrPut(sectionKey) { mutableMapOf() }
                        val (sum, count) = tableMap[tableKey] ?: (0L to 0)
                        tableMap[tableKey] = (sum + cents) to (count + 1)
                    }
                    "TO_GO"   -> toGoCents += cents
                    "BAR", "BAR_TAB" -> barCents += cents
                }
            }

            val dineInBySection = sectionTableAgg.map { (sectionName, tableMap) ->
                val tables = tableMap.map { (name, pair) ->
                    DineInTableSale(tableName = name, orderCount = pair.second, totalCents = pair.first)
                }.sortedByDescending { it.totalCents }
                DineInSectionSale(sectionName = sectionName, tables = tables)
            }.sortedByDescending { it.tables.sumOf { t -> t.totalCents } }

            onSuccess(
                SalesByOrderType(
                    dineInCents = dineInCents,
                    toGoCents = toGoCents,
                    barCents = barCents,
                    dineInBySection = dineInBySection
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
                    if (employeeName != null && doc.getString("employeeName") != employeeName) continue
                    val orderType = doc.getString("orderType") ?: ""
                    orderTypes[doc.id] = orderType
                    orderInfoMap[doc.id] = OrderInfo(
                        orderType = orderType,
                        tableId = doc.getString("tableId")?.takeIf { it.isNotBlank() },
                        tableName = doc.getString("tableName")?.takeIf { it.isNotBlank() },
                        sectionId = doc.getString("sectionId")?.takeIf { it.isNotBlank() },
                        sectionName = doc.getString("sectionName")?.takeIf { it.isNotBlank() }
                    )
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
        startDate: Date = Date(),
        endDate: Date = Date(),
        employeeName: String? = null,
        onSuccess: (List<CardBrandSale>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        val validOrderIds = mutableSetOf<String>()
        data class BrandRec(val orderId: String?, val brand: String, val cents: Long)
        val brandRecords = mutableListOf<BrandRec>()
        var remaining = if (employeeName != null) 2 else 1
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }

            val brandMap = mutableMapOf<String, Pair<Long, Int>>()
            for (rec in brandRecords) {
                if (employeeName != null && rec.orderId != null && rec.orderId !in validOrderIds) continue
                val cur = brandMap[rec.brand] ?: (0L to 0)
                brandMap[rec.brand] = (cur.first + rec.cents) to (cur.second + 1)
            }
            val result = brandMap.map { (brand, pair) ->
                CardBrandSale(brand, pair.first, pair.second)
            }.sortedByDescending { it.totalCents }
            onSuccess(result)
        }

        queryTransactions(start, end,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    when (doc.getString("type")) {
                        "SALE", "CAPTURE" -> {
                            val orderId = doc.getString("orderId")
                            val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                            for (p in payments) {
                                if ((p["status"] as? String) == "VOIDED") continue
                                val pt = ((p["paymentType"] as? String) ?: "").uppercase()
                                if (pt == "CASH") continue
                                val brand = normalizeCardBrand((p["cardBrand"] as? String) ?: "")
                                val cents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
                                brandRecords.add(BrandRec(orderId, brand, cents))
                            }
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() }
        )

        if (employeeName != null) {
            db.collection("Orders")
                .whereGreaterThanOrEqualTo("createdAt", start)
                .whereLessThan("createdAt", end)
                .get()
                .addOnSuccessListener { snap ->
                    for (doc in snap.documents) {
                        if (doc.getString("employeeName") == employeeName) {
                            validOrderIds.add(doc.id)
                        }
                    }
                    tryComplete()
                }
                .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
        }
    }

    fun getEmployeeReport(
        startDate: Date = Date(),
        endDate: Date = Date(),
        employeeName: String? = null,
        onSuccess: (List<EmployeeReportData>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        data class OrderInfo(val employeeName: String, val tipAmountInCents: Long)

        class Accum(val name: String) {
            var salesCents = 0L
            var orderCount = 0
            var tipsCents = 0L
            var tipsCount = 0
            var refundsCents = 0L
            var refundsCount = 0
            var voidsCount = 0
            var payCashCents = 0L
            var payCashTxCount = 0
            var payCardCents = 0L
            var payCardTxCount = 0
            var tipCashCents = 0L
            var tipCardCents = 0L
            var refundCashCents = 0L
            var refundCardCents = 0L

            fun toReportData() = EmployeeReportData(
                employeeName = name,
                salesCents = salesCents,
                orderCount = orderCount,
                tipsCents = tipsCents,
                tipsCount = tipsCount,
                refundsCents = refundsCents,
                refundsCount = refundsCount,
                voidsCount = voidsCount,
                paymentsByMethod = PaymentMethodBreakdown(payCashCents, payCashTxCount, payCardCents, payCardTxCount),
                tipsByMethod = PaymentMethodBreakdown(tipCashCents, 0, tipCardCents, 0),
                refundsByMethod = PaymentMethodBreakdown(refundCashCents, 0, refundCardCents, 0)
            )
        }

        val map = mutableMapOf<String, Accum>()
        fun accum(name: String) = map.getOrPut(name) { Accum(name) }

        val orderInfo = mutableMapOf<String, OrderInfo>()
        var remaining = 2
        var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }
            var result = map.values.map { it.toReportData() }.sortedByDescending { it.salesCents }
            if (employeeName != null) result = result.filter { it.employeeName == employeeName }
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
                        val tipCents = doc.getLong("tipAmountInCents") ?: 0L
                        orderInfo[doc.id] = OrderInfo(emp, tipCents)
                        val a = accum(emp)
                        a.salesCents += doc.getLong("totalPaidInCents") ?: 0L
                        a.orderCount++
                        if (tipCents > 0L) { a.tipsCents += tipCents; a.tipsCount++ }
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
                    when (doc.getString("type")) {
                        "SALE", "CAPTURE" -> {
                            val orderId = doc.getString("orderId") ?: continue
                            val info = orderInfo[orderId] ?: continue
                            val a = accum(info.employeeName)
                            val methods = paymentsByMethod(doc)
                            val (cashCents, cardCents) = toCashCardBreakdown(methods)
                            val (cashCount, cardCount) = paymentCountsByMethod(doc)
                            a.payCashCents += cashCents
                            a.payCashTxCount += cashCount
                            a.payCardCents += cardCents
                            a.payCardTxCount += cardCount
                            if (info.tipAmountInCents > 0L && (cashCents + cardCents) > 0L) {
                                val total = cashCents + cardCents
                                a.tipCashCents += (info.tipAmountInCents * cashCents) / total
                                a.tipCardCents += (info.tipAmountInCents * cardCents) / total
                            }
                        }
                        "REFUND" -> {
                            val emp = doc.getString("refundedBy")?.takeIf { it.isNotBlank() } ?: continue
                            val a = accum(emp)
                            val amount = doc.getLong("amountInCents")
                                ?: ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
                            a.refundsCents += amount
                            a.refundsCount++
                            if (isCashRefund(doc)) a.refundCashCents += amount
                            else a.refundCardCents += amount
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() }
        )
    }

    fun getHourlySalesByOrderType(
        startDate: Date,
        endDate: Date,
        employeeName: String? = null,
        onSuccess: (List<HourlySale>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        db.collection("Orders")
            .whereEqualTo("status", "CLOSED")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener { snap ->
                val hourMap = mutableMapOf<Int, MutableMap<String, Pair<Long, Int>>>()

                for (doc in snap.documents) {
                    if (employeeName != null && doc.getString("employeeName") != employeeName) continue
                    val createdAt = doc.getTimestamp("createdAt")?.toDate()
                        ?: doc.getDate("createdAt") ?: continue
                    val orderType = doc.getString("orderType") ?: "OTHER"
                    val cents = doc.getLong("totalPaidInCents")
                        ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
                    if (cents <= 0L) continue

                    val cal = Calendar.getInstance().apply { time = createdAt }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)

                    val typeMap = hourMap.getOrPut(hour) { mutableMapOf() }
                    val (sum, count) = typeMap[orderType] ?: (0L to 0)
                    typeMap[orderType] = (sum + cents) to (count + 1)
                }

                val result = hourMap.map { (hour, typeMap) ->
                    val orderTypes = typeMap.map { (ot, pair) ->
                        OrderTypeHourlySale(orderType = ot, totalCents = pair.first, orderCount = pair.second)
                    }.sortedByDescending { it.totalCents }
                    HourlySale(
                        hour = hour,
                        orderTypes = orderTypes,
                        totalCents = orderTypes.sumOf { it.totalCents },
                        totalOrders = orderTypes.sumOf { it.orderCount }
                    )
                }.sortedBy { it.hour }

                onSuccess(result)
            }
            .addOnFailureListener(onFailure)
    }
}
