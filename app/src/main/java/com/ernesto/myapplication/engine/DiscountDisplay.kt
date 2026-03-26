package com.ernesto.myapplication.engine

import java.util.Locale

/**
 * Shared discount line formatting for cart, payment summary, and order detail.
 * Handles Firestore values: [Long] is not a [Number] in Kotlin, so plain "as? Number" fails.
 */
object DiscountDisplay {

    fun numericToDouble(raw: Any?): Double? {
        return when (raw) {
            null -> null
            is Double -> raw
            is Float -> raw.toDouble()
            is Long -> raw.toDouble()
            is Int -> raw.toDouble()
            is Number -> raw.toDouble()
            else -> raw.toString().trim().toDoubleOrNull()
        }
    }

    /** Firestore / app use "PERCENTAGE", "percentage", etc. */
    fun isPercentageType(type: String?): Boolean {
        val t = type?.trim()?.lowercase(Locale.US) ?: return false
        return t == "percentage" || t == "percent"
    }

    /**
     * One line: "• Name (-10%)" for percentage; "• Name" for fixed / unknown (no dollar amounts).
     */
    fun formatBullet(discountName: String, type: String?, value: Any?): String {
        val v = numericToDouble(value)
        return if (isPercentageType(type) && v != null) {
            val pctStr = if (v % 1.0 == 0.0) v.toInt().toString()
            else String.format(Locale.US, "%.1f", v)
            "• $discountName (-$pctStr%)"
        } else {
            "• $discountName"
        }
    }

    fun formatBulletFromFirestoreMap(ad: Map<String, Any>): String {
        val name = ad["discountName"]?.toString() ?: "Discount"
        val type = ad["type"]?.toString()
        return formatBullet(name, type, ad["value"])
    }

    /** Left label for order summary rows: "• Name (10%)" or "• Name" for fixed amounts. */
    fun formatCartSummaryLabel(discountName: String, type: String?, value: Any?): String {
        val v = numericToDouble(value)
        val prefix = "• $discountName"
        return if (isPercentageType(type) && v != null) {
            val pctStr = if (v % 1.0 == 0.0) v.toInt().toString()
            else String.format(Locale.US, "%.1f", v)
            "$prefix ($pctStr%)"
        } else {
            prefix
        }
    }

    fun formatCartSummaryLabelFromFirestoreMap(ad: Map<String, Any>): String {
        val name = ad["discountName"]?.toString() ?: "Discount"
        return formatCartSummaryLabel(name, ad["type"]?.toString(), ad["value"])
    }

    fun amountInCentsFromFirestoreMap(ad: Map<String, Any>): Long {
        val raw = ad["amountInCents"] ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            else -> 0L
        }
    }

    /** Receipt label without bullet: "Name (10%)" or "Name". */
    fun formatReceiptLabel(discountName: String, type: String?, value: Any?): String {
        val v = numericToDouble(value)
        return if (isPercentageType(type) && v != null) {
            val pctStr = if (v % 1.0 == 0.0) v.toInt().toString()
            else String.format(Locale.US, "%.1f", v)
            "$discountName ($pctStr%)"
        } else {
            discountName
        }
    }

    /** Tax label: "Name (N%)" when percentage, otherwise just "Name". */
    fun formatTaxLabel(name: String, taxType: String?, rate: Double?): String {
        if (taxType?.equals("PERCENTAGE", ignoreCase = true) == true && rate != null && rate > 0) {
            val pctStr = if (rate % 1.0 == 0.0) rate.toInt().toString()
            else String.format(Locale.US, "%.2f", rate)
            return "$name ($pctStr%)"
        }
        return name
    }

    data class GroupedDiscount(val name: String, val type: String?, val value: Any?, val totalCents: Long)

    /**
     * Group applied discounts by discount name and sum amounts.
     * Merges item-level + order-level discounts with the same name.
     */
    fun groupByName(appliedDiscounts: List<Map<String, Any>>): List<GroupedDiscount> {
        return appliedDiscounts
            .groupBy { it["discountName"]?.toString() ?: "Discount" }
            .map { (name, entries) ->
                GroupedDiscount(
                    name = name,
                    type = entries.first()["type"]?.toString(),
                    value = entries.first()["value"],
                    totalCents = entries.sumOf { amountInCentsFromFirestoreMap(it) }
                )
            }
            .filter { it.totalCents > 0 }
    }
}
