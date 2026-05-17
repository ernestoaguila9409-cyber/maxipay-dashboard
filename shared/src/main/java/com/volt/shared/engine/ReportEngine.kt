package com.volt.shared.engine

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.volt.shared.MerchantFirestore
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

data class DineInTableSale(val tableName: String, val orderCount: Int, val totalCents: Long)
data class DineInSectionSale(val sectionName: String, val tables: List<DineInTableSale>)

data class SalesByOrderType(
    val dineInCents: Long = 0L,
    val toGoCents: Long = 0L,
    val barCents: Long = 0L,
    val dineInBySection: List<DineInSectionSale> = emptyList(),
) {
    val totalCents: Long get() = dineInCents + toGoCents + barCents
    fun percentOf(partCents: Long): Int {
        if (totalCents == 0L) return 0
        return ((partCents * 100.0) / totalCents).roundToInt()
    }
}

data class CardBrandSale(val brand: String, val totalCents: Long, val transactionCount: Int)

data class EmployeeMetrics(
    val employeeName: String,
    val salesCents: Long = 0L,
    val orderCount: Int = 0,
    val tipsCents: Long = 0L,
    val tipsCount: Int = 0,
    val refundsCents: Long = 0L,
    val refundsCount: Int = 0,
    val voidsCount: Int = 0,
)

data class PaymentMethodBreakdown(
    val cashCents: Long = 0L,
    val cashTxCount: Int = 0,
    val cardCents: Long = 0L,
    val cardTxCount: Int = 0,
) {
    val totalCents: Long get() = cashCents + cardCents
    val totalTxCount: Int get() = cashTxCount + cardTxCount
}

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
    val refundsByMethod: PaymentMethodBreakdown = PaymentMethodBreakdown(),
    val discountBreakdown: DiscountBreakdown = DiscountBreakdown(),
)

data class OrderTypeHourlySale(val orderType: String, val totalCents: Long, val orderCount: Int)
data class HourlySale(val hour: Int, val orderTypes: List<OrderTypeHourlySale>, val totalCents: Long, val totalOrders: Int)
data class TaxByOrderType(val orderType: String, val taxCents: Long)
data class TaxByTaxName(val taxName: String, val taxCents: Long)
data class DiscountByOrderType(val orderType: String, val discountCents: Long, val orderCount: Int)
data class DiscountByName(val discountName: String, val discountCents: Long, val timesUsed: Int)

data class DiscountByPaymentMethod(
    val cashDiscountCents: Long = 0L,
    val cashOrderCount: Int = 0,
    val cardDiscountCents: Long = 0L,
    val cardOrderCount: Int = 0,
) {
    val totalCents: Long get() = cashDiscountCents + cardDiscountCents
}

data class DiscountBreakdown(
    val byOrderType: List<DiscountByOrderType> = emptyList(),
    val byName: List<DiscountByName> = emptyList(),
    val byPaymentMethod: DiscountByPaymentMethod = DiscountByPaymentMethod(),
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
    val taxesByTaxName: List<TaxByTaxName> = emptyList(),
    val discountBreakdown: DiscountBreakdown = DiscountBreakdown(),
)

class ReportEngine(private val db: FirebaseFirestore) {

    fun dayRange(date: Date = Date()): Pair<Date, Date> {
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.time
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.time
    }

    fun dateRange(startDate: Date, endDate: Date): Pair<Date, Date> {
        val startCal = Calendar.getInstance().apply {
            time = startDate
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            time = endDate
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
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
        return doc.getLong("totalPaidInCents") ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
    }

    fun queryTransactions(
        start: Date, end: Date,
        onSuccess: (QuerySnapshot) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        MerchantFirestore.col("Transactions")
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

    private fun toCashCardBreakdown(methods: Map<String, Long>): Pair<Long, Long> {
        val cash = methods["CASH"] ?: 0L
        val card = (methods["CREDIT"] ?: 0L) + (methods["DEBIT"] ?: 0L)
        return cash to card
    }

    @Suppress("UNCHECKED_CAST")
    private fun paymentCountsByMethod(doc: DocumentSnapshot): Pair<Int, Int> {
        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        var hasCash = false; var hasCard = false
        for (p in payments) {
            if ((p["status"] as? String) == "VOIDED") continue
            when ((p["paymentType"] as? String ?: p["paymentMethod"] as? String ?: "OTHER").uppercase()) {
                "CASH" -> hasCash = true
                "CREDIT", "DEBIT" -> hasCard = true
            }
        }
        if (!hasCash && !hasCard && payments.isNotEmpty()) {
            when ((doc.getString("paymentType") ?: doc.getString("paymentMethod") ?: "").uppercase()) {
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

    private fun normalizeCardBrand(raw: String): String = when {
        raw.contains("VISA", ignoreCase = true) -> "Visa"
        raw.contains("MASTER", ignoreCase = true) -> "Mastercard"
        raw.contains("AMEX", ignoreCase = true) || raw.contains("AMERICAN", ignoreCase = true) -> "Amex"
        raw.contains("DISCOVER", ignoreCase = true) -> "Discover"
        raw.isBlank() -> "Other"
        else -> raw
    }

    fun getDailySalesSummary(
        startDate: Date = Date(), endDate: Date = Date(),
        employeeName: String? = null,
        onSuccess: (DailySalesSummary) -> Unit, onFailure: (Exception) -> Unit,
    ) {
        val (start, end) = dateRange(startDate, endDate)
        val validOrderIds = mutableSetOf<String>()
        data class TxRec(val orderId: String?, val gross: Long, val cash: Long, val credit: Long, val debit: Long)
        val saleRecords = mutableListOf<TxRec>()
        val refundRecords = mutableListOf<Pair<String?, Long>>()
        var taxCents = 0L; var tipCents = 0L; var discountCents = 0L
        var voidedItems = 0; var itemsSold = 0
        val taxByOrderTypeAgg = mutableMapOf<String, Long>()
        val taxByNameAgg = mutableMapOf<String, Long>()
        val discountByOrderTypeAgg = mutableMapOf<String, Pair<Long, Int>>()
        val discountByNameAgg = mutableMapOf<String, Pair<Long, Int>>()
        data class OrderDiscountInfo(val discountCents: Long, val orderType: String)
        val orderDiscountInfoMap = mutableMapOf<String, OrderDiscountInfo>()
        var remaining = 2; var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }
            var grossCents = 0L; var saleCount = 0
            var cashCents = 0L; var creditCents = 0L; var debitCents = 0L; var refundCents = 0L
            var discCashCents = 0L; var discCashCount = 0; var discCardCents = 0L; var discCardCount = 0
            for (rec in saleRecords) {
                if (employeeName != null && rec.orderId != null && rec.orderId !in validOrderIds) continue
                if (rec.gross > 0L) { grossCents += rec.gross; saleCount++ }
                cashCents += rec.cash; creditCents += rec.credit; debitCents += rec.debit
                val discInfo = rec.orderId?.let { orderDiscountInfoMap[it] }
                if (discInfo != null && discInfo.discountCents > 0L) {
                    val hasCash = rec.cash > 0L; val hasCard = (rec.credit + rec.debit) > 0L
                    if (hasCash && !hasCard) { discCashCents += discInfo.discountCents; discCashCount++ }
                    else if (hasCard && !hasCash) { discCardCents += discInfo.discountCents; discCardCount++ }
                    else if (hasCash && hasCard) {
                        val total = rec.cash + rec.credit + rec.debit
                        discCashCents += (discInfo.discountCents * rec.cash) / total
                        discCardCents += (discInfo.discountCents * (rec.credit + rec.debit)) / total
                        discCashCount++; discCardCount++
                    }
                }
            }
            for ((orderId, amount) in refundRecords) {
                if (employeeName != null && orderId != null && orderId !in validOrderIds) continue
                refundCents += amount
            }
            onSuccess(DailySalesSummary(
                grossSales = grossCents / 100.0, taxCollected = taxCents / 100.0,
                tipsCollected = tipCents / 100.0, netSales = (grossCents - taxCents - discountCents) / 100.0,
                totalTransactions = saleCount, averageTicket = if (saleCount > 0) grossCents / 100.0 / saleCount else 0.0,
                refunds = refundCents / 100.0, discounts = discountCents / 100.0,
                voidedItems = voidedItems, itemsSold = itemsSold,
                cashPayments = cashCents / 100.0, creditPayments = creditCents / 100.0, debitPayments = debitCents / 100.0,
                taxesByOrderType = taxByOrderTypeAgg.map { (ot, c) -> TaxByOrderType(ot, c) }.sortedByDescending { it.taxCents },
                taxesByTaxName = taxByNameAgg.map { (n, c) -> TaxByTaxName(n, c) }.sortedByDescending { it.taxCents },
                discountBreakdown = DiscountBreakdown(
                    byOrderType = discountByOrderTypeAgg.map { (ot, p) -> DiscountByOrderType(ot, p.first, p.second) }.sortedByDescending { it.discountCents },
                    byName = discountByNameAgg.map { (n, p) -> DiscountByName(n, p.first, p.second) }.sortedByDescending { it.timesUsed },
                    byPaymentMethod = DiscountByPaymentMethod(discCashCents, discCashCount, discCardCents, discCardCount),
                ),
            ))
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
                            refundRecords.add(orderId to (doc.getLong("amountInCents") ?: ((doc.getDouble("amount") ?: 0.0) * 100).toLong()))
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() },
        )

        @Suppress("UNCHECKED_CAST")
        MerchantFirestore.col("Orders")
            .whereGreaterThanOrEqualTo("createdAt", start).whereLessThan("createdAt", end).get()
            .addOnSuccessListener { snap ->
                val itemQueries = mutableListOf<Pair<String, String>>()
                for (doc in snap.documents) {
                    val status = doc.getString("status") ?: continue
                    if (employeeName != null && doc.getString("employeeName") != employeeName) continue
                    validOrderIds.add(doc.id)
                    if (status == "CLOSED") {
                        val orderType = doc.getString("orderType") ?: "OTHER"
                        val breakdown = doc.get("taxBreakdown") as? List<Map<String, Any>>
                        breakdown?.forEach { entry ->
                            val cents = (entry["amountInCents"] as? Number)?.toLong() ?: 0L
                            taxCents += cents
                            val taxName = (entry["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Unknown Tax"
                            taxByOrderTypeAgg[orderType] = (taxByOrderTypeAgg[orderType] ?: 0L) + cents
                            taxByNameAgg[taxName] = (taxByNameAgg[taxName] ?: 0L) + cents
                        }
                        tipCents += doc.getLong("tipAmountInCents") ?: 0L
                        val orderDiscCents = doc.getLong("discountInCents") ?: 0L
                        discountCents += orderDiscCents
                        if (orderDiscCents > 0L) {
                            val (curCents, curCount) = discountByOrderTypeAgg[orderType] ?: (0L to 0)
                            discountByOrderTypeAgg[orderType] = (curCents + orderDiscCents) to (curCount + 1)
                            val appliedDiscounts = doc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
                            if (appliedDiscounts.isNotEmpty()) {
                                for (ad in appliedDiscounts) {
                                    val dName = (ad["discountName"] as? String)?.takeIf { it.isNotBlank() } ?: "Unknown Discount"
                                    val dCents = (ad["amountInCents"] as? Number)?.toLong() ?: 0L
                                    if (dCents > 0L) { val (nc, nt) = discountByNameAgg[dName] ?: (0L to 0); discountByNameAgg[dName] = (nc + dCents) to (nt + 1) }
                                }
                            } else {
                                val (nc, nt) = discountByNameAgg["Discount"] ?: (0L to 0); discountByNameAgg["Discount"] = (nc + orderDiscCents) to (nt + 1)
                            }
                            orderDiscountInfoMap[doc.id] = OrderDiscountInfo(orderDiscCents, orderType)
                        }
                    }
                    if (status == "CLOSED" || status == "VOIDED") itemQueries.add(doc.id to status)
                }
                if (itemQueries.isEmpty()) { tryComplete() } else {
                    remaining += itemQueries.size; tryComplete()
                    for ((orderId, status) in itemQueries) {
                        MerchantFirestore.col("Orders").document(orderId).collection("items").get()
                            .addOnSuccessListener { itemsSnap ->
                                for (itemDoc in itemsSnap.documents) {
                                    val qty = (itemDoc.getLong("quantity") ?: 1L).toInt()
                                    if (status == "VOIDED") voidedItems += qty else itemsSold += qty
                                }
                                tryComplete()
                            }
                            .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
                    }
                }
            }
            .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
    }

    fun getSalesByOrderType(
        startOfDay: Date, endOfDay: Date, employeeName: String? = null,
        onSuccess: (SalesByOrderType) -> Unit, onFailure: (Exception) -> Unit,
    ) {
        data class OrderInfo(val orderType: String, val tableId: String?, val tableName: String?, val sectionId: String?, val sectionName: String?)
        val txByOrder = mutableMapOf<String, Long>()
        val orderTypes = mutableMapOf<String, String>()
        val orderInfoMap = mutableMapOf<String, OrderInfo>()
        var remaining = 2; var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }
            var dineInCents = 0L; var toGoCents = 0L; var barCents = 0L
            val sectionTableAgg = mutableMapOf<String, MutableMap<String, Pair<Long, Int>>>()
            fun getTableKey(info: OrderInfo?) = when {
                info != null && info.tableName?.isNotBlank() == true -> info.tableName!!
                info != null && info.tableId?.isNotBlank() == true -> "Table ${info.tableId}"
                else -> "Unknown"
            }
            fun getSectionKey(info: OrderInfo?) = info?.sectionName?.takeIf { it.isNotBlank() } ?: info?.sectionId?.takeIf { it.isNotBlank() } ?: "Unknown"
            for ((orderId, cents) in txByOrder) {
                when (orderTypes[orderId]) {
                    "DINE_IN" -> {
                        dineInCents += cents
                        val info = orderInfoMap[orderId]; val sectionKey = getSectionKey(info); val tableKey = getTableKey(info)
                        val tableMap = sectionTableAgg.getOrPut(sectionKey) { mutableMapOf() }
                        val (sum, count) = tableMap[tableKey] ?: (0L to 0)
                        tableMap[tableKey] = (sum + cents) to (count + 1)
                    }
                    "TO_GO" -> toGoCents += cents
                    "BAR", "BAR_TAB" -> barCents += cents
                }
            }
            val dineInBySection = sectionTableAgg.map { (sectionName, tableMap) ->
                DineInSectionSale(sectionName, tableMap.map { (name, pair) -> DineInTableSale(name, pair.second, pair.first) }.sortedByDescending { it.totalCents })
            }.sortedByDescending { it.tables.sumOf { t -> t.totalCents } }
            onSuccess(SalesByOrderType(dineInCents, toGoCents, barCents, dineInBySection))
        }

        queryTransactions(startOfDay, endOfDay,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    if (doc.getString("type") in listOf("SALE", "CAPTURE")) {
                        val orderId = doc.getString("orderId") ?: continue
                        val cents = sumPaymentsInCents(doc)
                        if (cents > 0L) txByOrder[orderId] = (txByOrder[orderId] ?: 0L) + cents
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() },
        )

        MerchantFirestore.col("Orders").whereGreaterThanOrEqualTo("createdAt", startOfDay).whereLessThan("createdAt", endOfDay).get()
            .addOnSuccessListener { snap ->
                for (doc in snap.documents) {
                    if (employeeName != null && doc.getString("employeeName") != employeeName) continue
                    val orderType = doc.getString("orderType") ?: ""
                    orderTypes[doc.id] = orderType
                    orderInfoMap[doc.id] = OrderInfo(orderType, doc.getString("tableId"), doc.getString("tableName"), doc.getString("sectionId"), doc.getString("sectionName"))
                }
                tryComplete()
            }
            .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
    }

    @Suppress("UNCHECKED_CAST")
    fun getSalesByCardBrand(
        startDate: Date = Date(), endDate: Date = Date(), employeeName: String? = null,
        onSuccess: (List<CardBrandSale>) -> Unit, onFailure: (Exception) -> Unit,
    ) {
        val (start, end) = dateRange(startDate, endDate)
        val validOrderIds = mutableSetOf<String>()
        data class BrandRec(val orderId: String?, val brand: String, val cents: Long)
        val brandRecords = mutableListOf<BrandRec>()
        var remaining = if (employeeName != null) 2 else 1; var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }
            val brandMap = mutableMapOf<String, Pair<Long, Int>>()
            for (rec in brandRecords) {
                if (employeeName != null && rec.orderId != null && rec.orderId !in validOrderIds) continue
                val cur = brandMap[rec.brand] ?: (0L to 0)
                brandMap[rec.brand] = (cur.first + rec.cents) to (cur.second + 1)
            }
            onSuccess(brandMap.map { (brand, pair) -> CardBrandSale(brand, pair.first, pair.second) }.sortedByDescending { it.totalCents })
        }

        queryTransactions(start, end,
            onSuccess = { snap ->
                for (doc in snap.documents) {
                    if (doc.getBoolean("voided") == true) continue
                    if (doc.getString("type") in listOf("SALE", "CAPTURE")) {
                        val orderId = doc.getString("orderId")
                        val payments = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
                        for (p in payments) {
                            if ((p["status"] as? String) == "VOIDED") continue
                            val pt = ((p["paymentType"] as? String) ?: "").uppercase()
                            if (pt == "CASH") continue
                            brandRecords.add(BrandRec(orderId, normalizeCardBrand((p["cardBrand"] as? String) ?: ""), (p["amountInCents"] as? Number)?.toLong() ?: 0L))
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() },
        )

        if (employeeName != null) {
            MerchantFirestore.col("Orders").whereGreaterThanOrEqualTo("createdAt", start).whereLessThan("createdAt", end).get()
                .addOnSuccessListener { snap -> for (doc in snap.documents) { if (doc.getString("employeeName") == employeeName) validOrderIds.add(doc.id) }; tryComplete() }
                .addOnFailureListener { e -> firstError = firstError ?: e; tryComplete() }
        }
    }

    fun getEmployeeReport(
        startDate: Date = Date(), endDate: Date = Date(), employeeName: String? = null,
        onSuccess: (List<EmployeeReportData>) -> Unit, onFailure: (Exception) -> Unit,
    ) {
        val (start, end) = dateRange(startDate, endDate)
        data class OrderInfo(val employeeName: String, val tipAmountInCents: Long, val discountCents: Long = 0L)

        class Accum(val name: String) {
            var salesCents = 0L; var orderCount = 0; var tipsCents = 0L; var tipsCount = 0
            var refundsCents = 0L; var refundsCount = 0; var voidsCount = 0
            var payCashCents = 0L; var payCashTxCount = 0; var payCardCents = 0L; var payCardTxCount = 0
            var tipCashCents = 0L; var tipCardCents = 0L; var refundCashCents = 0L; var refundCardCents = 0L
            val discByOrderType = mutableMapOf<String, Pair<Long, Int>>()
            val discByName = mutableMapOf<String, Pair<Long, Int>>()
            var discCashCents = 0L; var discCashCount = 0; var discCardCents = 0L; var discCardCount = 0

            @Suppress("UNCHECKED_CAST")
            fun toReportData() = EmployeeReportData(
                name, salesCents, orderCount, tipsCents, tipsCount, refundsCents, refundsCount, voidsCount,
                PaymentMethodBreakdown(payCashCents, payCashTxCount, payCardCents, payCardTxCount),
                PaymentMethodBreakdown(tipCashCents, 0, tipCardCents, 0),
                PaymentMethodBreakdown(refundCashCents, 0, refundCardCents, 0),
                DiscountBreakdown(
                    discByOrderType.map { (ot, p) -> DiscountByOrderType(ot, p.first, p.second) }.sortedByDescending { it.discountCents },
                    discByName.map { (n, p) -> DiscountByName(n, p.first, p.second) }.sortedByDescending { it.timesUsed },
                    DiscountByPaymentMethod(discCashCents, discCashCount, discCardCents, discCardCount),
                ),
            )
        }

        val map = mutableMapOf<String, Accum>()
        fun accum(name: String) = map.getOrPut(name) { Accum(name) }
        val orderInfo = mutableMapOf<String, OrderInfo>()
        var remaining = 2; var firstError: Exception? = null

        fun tryComplete() {
            if (--remaining > 0) return
            firstError?.let { onFailure(it); return }
            var result = map.values.map { it.toReportData() }.sortedByDescending { it.salesCents }
            if (employeeName != null) result = result.filter { it.employeeName == employeeName }
            onSuccess(result)
        }

        @Suppress("UNCHECKED_CAST")
        MerchantFirestore.col("Orders").whereGreaterThanOrEqualTo("createdAt", start).whereLessThan("createdAt", end).get()
            .addOnSuccessListener { snap ->
                for (doc in snap.documents) {
                    val status = doc.getString("status") ?: continue
                    if (status == "CLOSED") {
                        val emp = doc.getString("employeeName")?.takeIf { it.isNotBlank() } ?: continue
                        val tipC = doc.getLong("tipAmountInCents") ?: 0L
                        val orderDiscCents = doc.getLong("discountInCents") ?: 0L
                        orderInfo[doc.id] = OrderInfo(emp, tipC, orderDiscCents)
                        val a = accum(emp)
                        a.salesCents += doc.getLong("totalPaidInCents") ?: 0L; a.orderCount++
                        if (tipC > 0L) { a.tipsCents += tipC; a.tipsCount++ }
                        if (orderDiscCents > 0L) {
                            val orderType = doc.getString("orderType") ?: "OTHER"
                            val (curC, curN) = a.discByOrderType[orderType] ?: (0L to 0)
                            a.discByOrderType[orderType] = (curC + orderDiscCents) to (curN + 1)
                            val appliedDiscs = doc.get("appliedDiscounts") as? List<Map<String, Any>> ?: emptyList()
                            if (appliedDiscs.isNotEmpty()) {
                                for (ad in appliedDiscs) {
                                    val dName = (ad["discountName"] as? String)?.takeIf { it.isNotBlank() } ?: "Unknown Discount"
                                    val dCents = (ad["amountInCents"] as? Number)?.toLong() ?: 0L
                                    if (dCents > 0L) { val (nc, nt) = a.discByName[dName] ?: (0L to 0); a.discByName[dName] = (nc + dCents) to (nt + 1) }
                                }
                            } else { val (nc, nt) = a.discByName["Discount"] ?: (0L to 0); a.discByName["Discount"] = (nc + orderDiscCents) to (nt + 1) }
                        }
                    }
                    if (status == "VOIDED") { val emp = doc.getString("voidedBy")?.takeIf { it.isNotBlank() } ?: continue; accum(emp).voidsCount++ }
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
                            a.payCashCents += cashCents; a.payCashTxCount += cashCount; a.payCardCents += cardCents; a.payCardTxCount += cardCount
                            if (info.tipAmountInCents > 0L && (cashCents + cardCents) > 0L) {
                                val total = cashCents + cardCents
                                a.tipCashCents += (info.tipAmountInCents * cashCents) / total
                                a.tipCardCents += (info.tipAmountInCents * cardCents) / total
                            }
                            if (info.discountCents > 0L && (cashCents + cardCents) > 0L) {
                                val hasCash = cashCents > 0L; val hasCard = cardCents > 0L
                                if (hasCash && !hasCard) { a.discCashCents += info.discountCents; a.discCashCount++ }
                                else if (hasCard && !hasCash) { a.discCardCents += info.discountCents; a.discCardCount++ }
                                else { val total = cashCents + cardCents; a.discCashCents += (info.discountCents * cashCents) / total; a.discCardCents += (info.discountCents * cardCents) / total; a.discCashCount++; a.discCardCount++ }
                            }
                        }
                        "REFUND" -> {
                            val emp = doc.getString("refundedBy")?.takeIf { it.isNotBlank() } ?: continue
                            val a = accum(emp); val amount = doc.getLong("amountInCents") ?: ((doc.getDouble("amount") ?: 0.0) * 100).toLong()
                            a.refundsCents += amount; a.refundsCount++
                            if (isCashRefund(doc)) a.refundCashCents += amount else a.refundCardCents += amount
                        }
                    }
                }
                tryComplete()
            },
            onFailure = { e -> firstError = firstError ?: e; tryComplete() },
        )
    }

    fun getHourlySalesByOrderType(
        startDate: Date, endDate: Date, employeeName: String? = null,
        onSuccess: (List<HourlySale>) -> Unit, onFailure: (Exception) -> Unit,
    ) {
        val (start, end) = dateRange(startDate, endDate)
        MerchantFirestore.col("Orders").whereEqualTo("status", "CLOSED")
            .whereGreaterThanOrEqualTo("createdAt", start).whereLessThan("createdAt", end).get()
            .addOnSuccessListener { snap ->
                val hourMap = mutableMapOf<Int, MutableMap<String, Pair<Long, Int>>>()
                for (doc in snap.documents) {
                    if (employeeName != null && doc.getString("employeeName") != employeeName) continue
                    val createdAt = doc.getTimestamp("createdAt")?.toDate() ?: doc.getDate("createdAt") ?: continue
                    val orderType = doc.getString("orderType") ?: "OTHER"
                    val cents = doc.getLong("totalPaidInCents") ?: ((doc.getDouble("totalPaid") ?: 0.0) * 100).toLong()
                    if (cents <= 0L) continue
                    val hour = Calendar.getInstance().apply { time = createdAt }.get(Calendar.HOUR_OF_DAY)
                    val typeMap = hourMap.getOrPut(hour) { mutableMapOf() }
                    val (sum, count) = typeMap[orderType] ?: (0L to 0)
                    typeMap[orderType] = (sum + cents) to (count + 1)
                }
                onSuccess(hourMap.map { (hour, typeMap) ->
                    val ots = typeMap.map { (ot, pair) -> OrderTypeHourlySale(ot, pair.first, pair.second) }.sortedByDescending { it.totalCents }
                    HourlySale(hour, ots, ots.sumOf { it.totalCents }, ots.sumOf { it.orderCount })
                }.sortedBy { it.hour })
            }
            .addOnFailureListener(onFailure)
    }
}
