package com.ernesto.myapplication.data

data class Transaction(
    val id: String = "",

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

    val type: String = "SALE",
    val originalReferenceId: String = "",

    val isMixed: Boolean = false
)