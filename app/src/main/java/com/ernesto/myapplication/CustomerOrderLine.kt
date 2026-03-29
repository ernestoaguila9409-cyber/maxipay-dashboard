package com.ernesto.myapplication

data class CustomerOrderLine(
    val name: String,
    val quantity: Int,
    val modifiers: List<String>,
    val lineTotalCents: Long
)

data class OrderSummaryInfo(
    val subtotalCents: Long = 0L,
    val discountLines: List<SummaryLine> = emptyList(),
    val taxLines: List<SummaryLine> = emptyList(),
    val tipCents: Long = 0L
)

data class SummaryLine(val label: String, val amountCents: Long)

/** Shown on customer display after a payment is approved. */
data class PaymentSuccessInfo(
    val isCash: Boolean,
    /** Amount charged in this transaction (not remaining balance on order). */
    val amountChargedCents: Long,
    val tenderedCents: Long = 0L,
    val changeCents: Long = 0L
)

enum class ReceiptOption {
    PRINT,
    EMAIL,
    SMS,
    SKIP
}
