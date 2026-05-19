package com.volt.shared.engine

/**
 * Shared tax eligibility rules for POS cart preview and [OrderEngine] order totals.
 */
object OrderTaxHelper {

    /** FORCE_APPLY with no tax IDs behaves as INHERIT so sale-wide taxes still apply. */
    fun effectiveTaxMode(taxMode: String?, taxIds: Collection<String>): String {
        val mode = taxMode?.trim().orEmpty()
        val ids = taxIds.map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            mode == "FORCE_APPLY" && ids.isEmpty() -> "INHERIT"
            mode == "FORCE_APPLY" || mode == "INHERIT" -> mode
            ids.isNotEmpty() -> "FORCE_APPLY"
            else -> "INHERIT"
        }
    }

    fun isLineTaxableForTax(
        taxMode: String?,
        taxIds: Collection<String>,
        taxId: String,
        taxEnabledForOrder: Boolean,
    ): Boolean {
        return when (effectiveTaxMode(taxMode, taxIds)) {
            "FORCE_APPLY" -> taxIds.contains(taxId)
            else -> taxEnabledForOrder
        }
    }
}

data class OrderTaxRule(
    val id: String,
    val name: String,
    val type: String,
    val amount: Double,
    val enabled: Boolean = true,
)

data class OrderTaxLine(
    val lineKey: String,
    val lineTotalDollars: Double,
    val taxMode: String = "INHERIT",
    val taxIds: List<String> = emptyList(),
)

data class OrderTaxBreakdownEntry(
    val name: String,
    val type: String,
    val rate: Double,
    val amountInCents: Long,
)

object OrderTaxCalculator {

    fun computeBreakdown(
        lines: List<OrderTaxLine>,
        taxes: List<OrderTaxRule>,
    ): List<OrderTaxBreakdownEntry> {
        val breakdown = mutableListOf<OrderTaxBreakdownEntry>()
        for (tax in taxes) {
            if (!tax.enabled) continue
            var taxableBase = 0.0
            for (line in lines) {
                if (OrderTaxHelper.isLineTaxableForTax(line.taxMode, line.taxIds, tax.id, tax.enabled)) {
                    taxableBase += line.lineTotalDollars
                }
            }
            if (taxableBase <= 0.0) continue
            val taxAmount = if (tax.type == "PERCENTAGE") {
                taxableBase * tax.amount / 100.0
            } else {
                tax.amount
            }
            val taxCents = Math.round(taxAmount * 100)
            if (taxCents <= 0L) continue
            breakdown.add(
                OrderTaxBreakdownEntry(
                    name = tax.name,
                    type = tax.type,
                    rate = tax.amount,
                    amountInCents = taxCents,
                ),
            )
        }
        return breakdown
    }

    fun taxTotalCents(breakdown: List<OrderTaxBreakdownEntry>): Long =
        breakdown.sumOf { it.amountInCents }
}
