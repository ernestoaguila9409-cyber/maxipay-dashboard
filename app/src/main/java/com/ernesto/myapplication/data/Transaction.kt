package com.ernesto.myapplication.data

/** Single payment entry within a transaction (one element of the Firestore payments array). */
data class TransactionPayment(
    val paymentType: String = "",
    val cardBrand: String = "",
    val last4: String = "",
    val entryType: String = "",
    val amountInCents: Long = 0L,
    /** Terminal/gateway reference for this payment; required for Card Void API. For CAPTURE (Post Auth), use capture referenceId, NOT PreAuth. */
    val referenceId: String = "",
    val clientReferenceId: String = "",
    /** AuthCode from sale/capture response; required for Void on captured PreAuth transactions. */
    val authCode: String = "",
    val batchNumber: String = "",
    val transactionNumber: String = "",
    val paymentId: String = ""
)

data class Transaction(
    val id: String = "",
    val orderId: String = "",
    var orderNumber: Long = 0L,

    /** Firestore document ID; used to link refunds and find the doc for void updates. */
    val referenceId: String = "",
    /** Dejavoo ReferenceId from sale response; required for Void. */
    val gatewayReferenceId: String = "",
    /** Reference we sent on the Sale request; fallback for Void if gateway ref is empty. */
    val clientReferenceId: String = "",
    /** Dejavoo BatchNumber from sale response; required for Void. */
    val batchNumber: String = "",
    /** Dejavoo TransactionNumber from sale response; required for Void. */
    val transactionNumber: String = "",
    /** Terminal invoice number from sale response. */
    val invoiceNumber: String = "",
    val amountInCents: Long = 0L,
    val date: Long = 0L,

    val paymentType: String = "",
    val cardBrand: String = "",
    val last4: String = "",
    val entryType: String = "",

    val voided: Boolean = false,
    val voidedBy: String = "",
    /** True when the batch was closed; only Refund is allowed, not Void. */
    val settled: Boolean = false,

    /** Batch this transaction belongs to (for refunds to inherit). */
    val batchId: String = "",

    val type: String = "SALE",
    val originalReferenceId: String = "",

    val isMixed: Boolean = false,

    /** All payment methods for this transaction (from Firestore payments array). */
    val payments: List<TransactionPayment> = emptyList(),

    /** Tip amount in cents; set after TipAdjust API call. */
    val tipAmountInCents: Long = 0L,
    /** True after at least one successful tip adjustment. */
    val tipAdjusted: Boolean = false,

    /** App-level sequential transaction number, unique within a batch. */
    val appTransactionNumber: Long = 0L
)