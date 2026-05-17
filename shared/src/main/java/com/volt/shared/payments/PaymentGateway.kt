package com.volt.shared.payments

data class PaymentResult(
    val approved: Boolean,
    val authCode: String = "",
    val cardBrand: String = "",
    val last4: String = "",
    val entryType: String = "",
    val referenceId: String = "",
    val clientReferenceId: String = "",
    val batchNumber: String = "",
    val transactionNumber: String = "",
    val invoiceNumber: String = "",
    val pnReferenceId: String = "",
    val declineReason: String = "",
    val rawResponse: Map<String, String> = emptyMap(),
)

interface PaymentCallback {
    fun onApproved(result: PaymentResult)
    fun onDeclined(result: PaymentResult)
    fun onError(message: String)
}

interface PaymentGateway {
    fun sale(amountCents: Long, paymentType: String, callback: PaymentCallback)
    fun void(referenceId: String, callback: PaymentCallback)
    fun refund(amountCents: Long, referenceId: String, callback: PaymentCallback)
    fun tipAdjust(referenceId: String, baseAmountCents: Long, tipAmountCents: Long, callback: PaymentCallback)
    fun cancel()
}
