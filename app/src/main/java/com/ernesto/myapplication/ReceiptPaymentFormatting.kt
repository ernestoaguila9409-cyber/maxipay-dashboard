package com.ernesto.myapplication

import com.ernesto.myapplication.engine.MoneyUtils

/**
 * Receipt helpers for rendering payment sections with fixed-width alignment.
 * Presentation-only: consumes existing transaction payment fields.
 */
object ReceiptPaymentFormatting {

    /**
     * Derives a receipt-header transaction label from the payments list.
     * Returns "DEBIT SALE", "CREDIT SALE", or "CASH".
     */
    fun resolveTransactionTypeLabel(payments: List<Map<String, Any>>): String {
        if (payments.isEmpty()) return "CASH"
        val hasDebit = payments.any {
            it["paymentType"]?.toString()?.equals("Debit", ignoreCase = true) == true
        }
        val hasCredit = payments.any {
            it["paymentType"]?.toString()?.equals("Credit", ignoreCase = true) == true
        }
        return when {
            hasDebit -> "DEBIT SALE"
            hasCredit -> "CREDIT SALE"
            else -> "CASH"
        }
    }

    /** Right-align [right] within [width], truncating [left] if needed. */
    fun formatRightAligned(left: String, right: String, width: Int): String {
        val l = left.trimEnd()
        val r = right.trimStart()
        val minGap = 1
        val maxLeftLen = (width - r.length - minGap).coerceAtLeast(0)
        val clippedLeft = if (l.length <= maxLeftLen) l else l.take(maxLeftLen.coerceAtLeast(0))
        val spaces = (width - clippedLeft.length - r.length).coerceAtLeast(minGap)
        return clippedLeft + " ".repeat(spaces) + r
    }

    data class PaymentLine(
        val amountCents: Long,
        val cardBrand: String,
        val last4: String,
        val entryMethod: String,
        val authCode: String,
    )

    /**
     * Formats a signed currency value for receipts.
     * Uses ASCII-safe characters for thermal printers.
     */
    fun formatSignedCents(amountCents: Long): String {
        if (amountCents == 0L) return MoneyUtils.centsToDisplay(0)
        val abs = kotlin.math.abs(amountCents)
        val base = MoneyUtils.centsToDisplay(abs)
        return if (amountCents < 0) "-$base" else base
    }

    fun parsePayments(payments: List<Map<String, Any>>): List<PaymentLine> {
        return payments.mapNotNull { p ->
            val amountCents = when (val v = p["amountInCents"] ?: p["amount"]) {
                is Number -> {
                    // If amountInCents, accept as cents; if amount dollars, convert.
                    if (p.containsKey("amountInCents")) v.toLong() else Math.round(v.toDouble() * 100.0)
                }
                is String -> v.toDoubleOrNull()?.let { Math.round(it * 100.0) }
                else -> null
            } ?: return@mapNotNull null

            val brand = p["cardBrand"]?.toString()?.trim().orEmpty()
            val last4 = p["last4"]?.toString()?.trim().orEmpty()
            val entryRaw = (p["entryMethod"] ?: p["entryType"])?.toString()
            val entry = receiptLabelForCardEntryType(entryRaw)?.trim().orEmpty()
            val auth = p["authCode"]?.toString()?.trim().orEmpty()

            PaymentLine(
                amountCents = amountCents,
                cardBrand = brand,
                last4 = last4,
                entryMethod = entry,
                authCode = auth,
            )
        }
    }

    /**
     * Void receipts: reverse (negative) payments from `Orders/{orderId}/transactions/{txnId}`.
     *
     * Expected fields per txn:
     * - amount (dollars) or amountInCents (cents)
     * - cardBrand, last4, entryMethod, authCode
     */
    fun buildReversedPaymentsSectionLines(
        reversedTransactions: List<Map<String, Any>>,
        width: Int,
    ): List<String> {
        val parsed = parsePayments(reversedTransactions)
            .map { it.copy(amountCents = -kotlin.math.abs(it.amountCents)) }
        if (parsed.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val sep = "-".repeat(width)

        // Match printed receipt structure: separator, header, separator, entries, separator, totals.
        lines += sep
        lines += ""
        lines += if (parsed.size > 1) {
            "Reversed Payments (${parsed.size} methods):"
        } else {
            "Reversed Payment:"
        }
        lines += sep

        for ((idx, p) in parsed.withIndex()) {
            val brand = p.cardBrand.takeIf { it.isNotBlank() } ?: "Card"
            val masked = p.last4.takeIf { it.isNotBlank() }?.let { "**** $it" }.orEmpty()
            val method = p.entryMethod.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val left = buildString {
                append(brand)
                if (masked.isNotBlank()) {
                    append(" ")
                    append(masked)
                }
                append(method)
            }.trim()
            lines += formatRightAligned(left, formatSignedCents(p.amountCents), width)
            if (p.authCode.isNotBlank()) lines += "  Auth: ${p.authCode}"
            if (idx != parsed.lastIndex) lines += ""
        }

        lines += sep

        val totalRefundedCents = parsed.sumOf { it.amountCents } // negative
        lines += formatRightAligned("Total Refunded:", formatSignedCents(totalRefundedCents), width)
        lines += formatRightAligned("Balance:", MoneyUtils.centsToDisplay(0L), width)

        return lines
    }

    /**
     * Builds fixed-width payment lines:
     * - Optional "Split Payment (X methods)" header
     * - "Payments:" section with card lines and auth lines
     * - Separator, then Paid / Balance summary
     */
    fun buildPaymentsSectionLines(
        totalInCents: Long,
        payments: List<Map<String, Any>>,
        width: Int,
    ): List<String> {
        val parsed = parsePayments(payments)
        if (parsed.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()

        if (parsed.size > 1) {
            lines += "MIX PAYMENTS (${parsed.size} methods)"
            lines += ""
        }

        lines += "Payments:"
        lines += ""

        for ((idx, p) in parsed.withIndex()) {
            val brand = p.cardBrand.takeIf { it.isNotBlank() } ?: "Card"
            // Thermal printers frequently can't render Unicode bullets reliably; keep this ASCII-safe.
            val masked = p.last4.takeIf { it.isNotBlank() }?.let { "**** $it" }.orEmpty()
            val method = p.entryMethod.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val left = buildString {
                append(brand)
                if (masked.isNotBlank()) {
                    append(" ")
                    append(masked)
                }
                append(method)
            }.trim()
            val amt = MoneyUtils.centsToDisplay(p.amountCents)
            lines += formatRightAligned(left, amt, width)
            if (p.authCode.isNotBlank()) {
                lines += "  Auth: ${p.authCode}"
            }
            if (idx != parsed.lastIndex) lines += ""
        }

        lines += "-".repeat(width)

        val paidCents = parsed.sumOf { it.amountCents }.coerceAtLeast(0L)
        val balanceCents = (totalInCents - paidCents).coerceAtLeast(0L)
        lines += formatRightAligned("Paid:", MoneyUtils.centsToDisplay(paidCents), width)
        lines += formatRightAligned("Balance:", MoneyUtils.centsToDisplay(balanceCents), width)

        return lines
    }
}

