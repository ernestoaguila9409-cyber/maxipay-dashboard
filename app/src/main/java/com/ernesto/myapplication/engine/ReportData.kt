package com.ernesto.myapplication.engine

import java.io.Serializable
import java.text.NumberFormat
import java.util.Locale

data class ReportRow(
    val label: String,
    val value: String = "",
    val indent: Int = 0,
    val isBold: Boolean = false,
    val isTotal: Boolean = false,
    val isDivider: Boolean = false,
    val isSectionHeader: Boolean = false,
    val labelColor: String? = null,
    val valueColor: String? = null
) : Serializable

data class ReportSection(
    val title: String,
    val rows: List<ReportRow>
) : Serializable

data class ReportData(
    val title: String,
    val dateRange: String,
    val employeeFilter: String? = null,
    val sections: List<ReportSection>
) : Serializable

object ReportBuilder {

    private val currency: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)
    private fun fmt(amount: Double): String = currency.format(amount)
    private fun fmtCents(cents: Long): String = currency.format(cents / 100.0)
    private fun negFmtCents(cents: Long): String = "-${fmtCents(cents)}"

    private fun divider() = ReportRow("", isDivider = true)
    private fun total(label: String, value: String, valueColor: String? = null) =
        ReportRow(label, value, isTotal = true, isBold = true, valueColor = valueColor)

    private fun orderTypeLabel(raw: String): String = when (raw) {
        "DINE_IN" -> "Dine-In"
        "TO_GO" -> "To-Go"
        "BAR" -> "Bar"
        "BAR_TAB" -> "Bar Tab"
        else -> raw.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }

    fun fromDailySalesSummary(summary: DailySalesSummary): ReportData {
        val sections = mutableListOf<ReportSection>()

        val overview = mutableListOf<ReportRow>()
        overview += ReportRow("Gross Sales", fmt(summary.grossSales), isBold = true)
        if (summary.discounts > 0) {
            overview += ReportRow("Discounts", "-${fmt(summary.discounts)}", valueColor = "#E65100")
        }
        overview += ReportRow("Tax Collected", fmt(summary.taxCollected))
        overview += ReportRow("Tips Collected", fmt(summary.tipsCollected))
        overview += ReportRow("Net Sales", fmt(summary.netSales), isBold = true, valueColor = "#2E7D32")
        overview += divider()
        overview += ReportRow("Transactions", summary.totalTransactions.toString())
        overview += ReportRow("Average Ticket", fmt(summary.averageTicket))
        overview += ReportRow("Items Sold", summary.itemsSold.toString())
        overview += ReportRow("Refunds", fmt(summary.refunds), valueColor = "#C62828")
        overview += ReportRow("Voided Items", summary.voidedItems.toString())
        sections += ReportSection("Sales Overview", overview)

        val payments = mutableListOf<ReportRow>()
        payments += ReportRow("Cash", fmt(summary.cashPayments))
        payments += ReportRow("Credit", fmt(summary.creditPayments))
        payments += ReportRow("Debit", fmt(summary.debitPayments))
        payments += divider()
        payments += total("Total", fmt(summary.cashPayments + summary.creditPayments + summary.debitPayments))
        sections += ReportSection("Payment Methods", payments)

        val activeByOT = summary.taxesByOrderType.filter { it.taxCents > 0L }
        val activeByName = summary.taxesByTaxName.filter { it.taxCents > 0L }
        if (activeByOT.isNotEmpty() || activeByName.isNotEmpty()) {
            val taxRows = mutableListOf<ReportRow>()
            if (activeByOT.isNotEmpty()) {
                taxRows += ReportRow("By Order Type", isSectionHeader = true, isBold = true)
                for (t in activeByOT) {
                    taxRows += ReportRow("${orderTypeLabel(t.orderType)} Tax", fmtCents(t.taxCents), indent = 1)
                }
                taxRows += divider()
                taxRows += total("Total", fmtCents(activeByOT.sumOf { it.taxCents }))
            }
            if (activeByName.isNotEmpty()) {
                taxRows += ReportRow("By Tax Type", isSectionHeader = true, isBold = true)
                for (t in activeByName) {
                    taxRows += ReportRow(t.taxName, fmtCents(t.taxCents), indent = 1)
                }
                taxRows += divider()
                taxRows += total("Total", fmtCents(activeByName.sumOf { it.taxCents }))
            }
            sections += ReportSection("Tax Breakdown", taxRows)
        }

        val db = summary.discountBreakdown
        if (db.byOrderType.isNotEmpty() || db.byName.isNotEmpty()) {
            val discRows = mutableListOf<ReportRow>()
            if (db.byOrderType.isNotEmpty()) {
                discRows += ReportRow("By Order Type", isSectionHeader = true, isBold = true)
                for (d in db.byOrderType) {
                    discRows += ReportRow(
                        "${orderTypeLabel(d.orderType)} (${d.orderCount} orders)",
                        negFmtCents(d.discountCents), indent = 1, valueColor = "#E65100"
                    )
                }
                discRows += divider()
                discRows += total("Total", negFmtCents(db.byOrderType.sumOf { it.discountCents }), "#E65100")
            }
            if (db.byName.isNotEmpty()) {
                discRows += ReportRow("Most Used Discounts", isSectionHeader = true, isBold = true)
                for (d in db.byName) {
                    discRows += ReportRow(
                        "${d.discountName} (${d.timesUsed}x)",
                        negFmtCents(d.discountCents), indent = 1, valueColor = "#E65100"
                    )
                }
            }
            if (db.byPaymentMethod.totalCents > 0L) {
                val pm = db.byPaymentMethod
                discRows += ReportRow("By Payment Method", isSectionHeader = true, isBold = true)
                if (pm.cashDiscountCents > 0L) {
                    discRows += ReportRow("Cash (${pm.cashOrderCount} orders)", negFmtCents(pm.cashDiscountCents), indent = 1, valueColor = "#E65100")
                }
                if (pm.cardDiscountCents > 0L) {
                    discRows += ReportRow("Card (${pm.cardOrderCount} orders)", negFmtCents(pm.cardDiscountCents), indent = 1, valueColor = "#E65100")
                }
                discRows += divider()
                discRows += total("Total", negFmtCents(pm.totalCents), "#E65100")
            }
            sections += ReportSection("Discount Breakdown", discRows)
        }

        return ReportData("Daily Sales Summary", "", sections = sections)
    }

    fun fromSalesByOrderType(data: SalesByOrderType): ReportData {
        val rows = mutableListOf<ReportRow>()
        rows += ReportRow("Dine-In", "${fmtCents(data.dineInCents)}  (${data.percentOf(data.dineInCents)}%)", isBold = true)

        for (section in data.dineInBySection) {
            rows += ReportRow(section.sectionName, isSectionHeader = true, indent = 1)
            for (table in section.tables) {
                rows += ReportRow(
                    "${table.tableName} (${table.orderCount} orders)",
                    fmtCents(table.totalCents), indent = 2
                )
            }
        }

        rows += ReportRow("To-Go", "${fmtCents(data.toGoCents)}  (${data.percentOf(data.toGoCents)}%)", isBold = true)
        rows += ReportRow("Bar", "${fmtCents(data.barCents)}  (${data.percentOf(data.barCents)}%)", isBold = true)
        rows += divider()
        rows += total("Total", fmtCents(data.totalCents), "#2E7D32")

        return ReportData("Sales by Order Type", "", sections = listOf(ReportSection("Order Type Breakdown", rows)))
    }

    fun fromHourlySales(data: List<HourlySale>): ReportData {
        val rows = mutableListOf<ReportRow>()
        for (h in data) {
            val hour12 = when {
                h.hour == 0 -> "12:00 AM"
                h.hour < 12 -> "${h.hour}:00 AM"
                h.hour == 12 -> "12:00 PM"
                else -> "${h.hour - 12}:00 PM"
            }
            rows += ReportRow(hour12, "${fmtCents(h.totalCents)}  (${h.totalOrders} orders)", isBold = true)
            for (ot in h.orderTypes) {
                rows += ReportRow(
                    orderTypeLabel(ot.orderType),
                    "${fmtCents(ot.totalCents)} (${ot.orderCount})", indent = 1
                )
            }
        }
        if (data.isNotEmpty()) {
            rows += divider()
            rows += total("Total", fmtCents(data.sumOf { it.totalCents }), "#2E7D32")
        }
        return ReportData("Hourly Sales by Order Type", "", sections = listOf(ReportSection("Hourly Breakdown", rows)))
    }

    fun fromCardBrandSales(data: List<CardBrandSale>): ReportData {
        val rows = mutableListOf<ReportRow>()
        for (cb in data) {
            rows += ReportRow(
                "${cb.brand} (${cb.transactionCount} txn)",
                fmtCents(cb.totalCents), isBold = true
            )
        }
        if (data.isNotEmpty()) {
            rows += divider()
            rows += total("Total", fmtCents(data.sumOf { it.totalCents }), "#2E7D32")
        }
        return ReportData("Sales by Card Brand", "", sections = listOf(ReportSection("Card Brands", rows)))
    }

    fun fromEmployeeReport(data: List<EmployeeReportData>): ReportData {
        val sections = mutableListOf<ReportSection>()
        for (emp in data) {
            val rows = mutableListOf<ReportRow>()
            rows += ReportRow("Sales", fmtCents(emp.salesCents), isBold = true)
            rows += ReportRow("Orders", emp.orderCount.toString())
            if (emp.tipsCents > 0L) rows += ReportRow("Tips (${emp.tipsCount})", fmtCents(emp.tipsCents))
            if (emp.refundsCents > 0L) rows += ReportRow("Refunds (${emp.refundsCount})", fmtCents(emp.refundsCents), valueColor = "#C62828")
            if (emp.voidsCount > 0) rows += ReportRow("Voids", emp.voidsCount.toString(), valueColor = "#C62828")

            val pm = emp.paymentsByMethod
            if (pm.totalCents > 0L) {
                rows += divider()
                rows += ReportRow("Payments", isSectionHeader = true, isBold = true)
                if (pm.cashCents > 0L) rows += ReportRow("Cash (${pm.cashTxCount} txn)", fmtCents(pm.cashCents), indent = 1)
                if (pm.cardCents > 0L) rows += ReportRow("Card (${pm.cardTxCount} txn)", fmtCents(pm.cardCents), indent = 1)
            }

            val db = emp.discountBreakdown
            if (db.byName.isNotEmpty()) {
                rows += divider()
                rows += ReportRow("Discounts", isSectionHeader = true, isBold = true)
                for (d in db.byName) {
                    rows += ReportRow("${d.discountName} (${d.timesUsed}x)", negFmtCents(d.discountCents), indent = 1, valueColor = "#E65100")
                }
            }

            sections += ReportSection(emp.employeeName, rows)
        }
        return ReportData("Employee Report", "", sections = sections)
    }

    fun fromMenuPerformance(
        items: List<ItemSalesRow>,
        categories: List<CategorySalesRow>,
        modifiers: List<ModifierSalesRow>
    ): ReportData {
        val sections = mutableListOf<ReportSection>()

        if (items.isNotEmpty()) {
            val rows = mutableListOf<ReportRow>()
            val top3 = items.sortedByDescending { it.quantitySold }.take(3)
            for ((index, r) in top3.withIndex()) {
                val medal = when (index) { 0 -> "#1  "; 1 -> "#2  "; 2 -> "#3  "; else -> "" }
                rows += ReportRow("$medal${r.itemName}", "${r.quantitySold} sold  ${fmtCents(r.totalRevenueCents)}", isBold = index == 0)
            }
            rows += divider()
            rows += total(
                "Total (all items)",
                "${items.sumOf { it.quantitySold }} sold  ${fmtCents(items.sumOf { it.totalRevenueCents })}",
                "#2E7D32"
            )
            sections += ReportSection("Top Selling Items", rows)
        }

        if (categories.isNotEmpty()) {
            val rows = mutableListOf<ReportRow>()
            for (r in categories) {
                rows += ReportRow(r.categoryName, fmtCents(r.totalRevenueCents))
            }
            rows += divider()
            rows += total("Total", fmtCents(categories.sumOf { it.totalRevenueCents }), "#2E7D32")
            sections += ReportSection("Category Sales", rows)
        }

        if (modifiers.isNotEmpty()) {
            val rows = mutableListOf<ReportRow>()
            val grouped = modifiers.groupBy { it.itemName }
            for ((itemName, mods) in grouped) {
                rows += ReportRow(itemName, isSectionHeader = true, isBold = true)
                for (m in mods) {
                    val display = if (m.action == "REMOVE") "REMOVE ${m.modifierName}" else m.modifierName
                    val extra = if (m.totalExtraCents > 0) "  +${fmtCents(m.totalExtraCents)}" else ""
                    rows += ReportRow(display, "${m.usageCount}x$extra", indent = 1,
                        labelColor = if (m.action == "REMOVE") "#C62828" else null)
                }
            }
            rows += divider()
            rows += total("Total Usage", modifiers.sumOf { it.usageCount }.toString())
            sections += ReportSection("Modifier Usage", rows)
        }

        return ReportData("Menu Performance", "", sections = sections)
    }
}
