package com.ernesto.myapplication.payments

import com.ernesto.myapplication.data.TransactionPayment
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Resolves gateway ReferenceId / clientReferenceId for SPIn void when payment legs
 * are sparse (common for online iPOS HPP: `transactionId` missing in webhook payload).
 */
object TransactionVoidReferenceResolver {

    private val PAYMENT_MAP_REF_KEYS: List<String> = listOf(
        "referenceId",
        "terminalReference",
        "transactionId",
        "gatewayTransactionId",
        "gatewayReferenceId",
        "rrn",
        "RRN",
        "retrievalReferenceNumber",
        "ReferenceId",
        "TransactionId",
    )

    private val PAYMENT_MAP_CLIENT_KEYS: List<String> = listOf(
        "clientReferenceId",
        "transactionReferenceId",
        "ClientReferenceId",
    )

    private fun nonBlank(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    /** PN / processor reference from a payment map (SPIn sale response). */
    fun pnReferenceFromPaymentMap(p: Map<String, Any>?): String {
        if (p == null) return ""
        return nonBlank(stringFromFirestoreValue(p["pnReferenceId"]))
            ?: nonBlank(stringFromFirestoreValue(p["PNReferenceId"]))
            ?: ""
    }

    private fun stringFromFirestoreValue(v: Any?): String = when (v) {
        null -> ""
        is String -> v.trim()
        is Number -> {
            val d = v.toDouble()
            if (d == d.toLong().toDouble() && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                d.toLong().toString()
            } else {
                v.toString().trim()
            }
        }
        else -> v.toString().trim()
    }

    /** Best gateway reference string stored on a single payment map. */
    fun gatewayRefFromPaymentMap(p: Map<String, Any>?): String {
        if (p == null) return ""
        for (k in PAYMENT_MAP_REF_KEYS) {
            nonBlank(stringFromFirestoreValue(p[k]))?.let { return it }
        }
        return ""
    }

    fun clientRefFromPaymentMap(p: Map<String, Any>?): String {
        if (p == null) return ""
        for (k in PAYMENT_MAP_CLIENT_KEYS) {
            nonBlank(stringFromFirestoreValue(p[k]))?.let { return it }
        }
        return ""
    }

    /** First non-blank gateway-style reference on the transaction document (not payment array). */
    fun firstGatewayRefFromTransactionDoc(doc: DocumentSnapshot): String =
        docLevelGatewayRefs(doc).firstOrNull().orEmpty()

    fun firstClientRefFromTransactionDoc(doc: DocumentSnapshot): String =
        nonBlank(doc.getString("clientReferenceId"))
            ?: nonBlank(doc.getString("hppTransactionRefId"))
            ?: ""

    private fun docLevelGatewayRefs(doc: DocumentSnapshot): List<String> {
        val keys = listOf(
            "referenceId",
            "gatewayReferenceId",
            "terminalReference",
            "hppTransactionId",
            "transactionId",
            "spinReferenceId",
        )
        return keys.mapNotNull { k -> nonBlank(doc.getString(k)) }
    }

    /**
     * Fills blank [TransactionPayment.referenceId] / [TransactionPayment.clientReferenceId]
     * (and batch/txn/auth when blank) from the transaction document and raw `payments` maps.
     */
    fun enrichPaymentsForVoid(doc: DocumentSnapshot, payments: List<TransactionPayment>): List<TransactionPayment> {
        @Suppress("UNCHECKED_CAST")
        val rawList = doc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        val docRefs = docLevelGatewayRefs(doc)
        val firstDocRef = docRefs.firstOrNull().orEmpty()
        val docClient = nonBlank(doc.getString("clientReferenceId"))
            ?: nonBlank(doc.getString("hppTransactionRefId"))
            ?: ""

        return payments.mapIndexed { index, p ->
            val raw = rawList.getOrNull(index)
            val fromRawRef = gatewayRefFromPaymentMap(raw)
            val fromRawClient = clientRefFromPaymentMap(raw)
            val newRef = when {
                p.referenceId.isNotBlank() -> p.referenceId.trim()
                fromRawRef.isNotBlank() -> fromRawRef
                else -> firstDocRef
            }
            val newClient = when {
                p.clientReferenceId.isNotBlank() -> p.clientReferenceId.trim()
                fromRawClient.isNotBlank() -> fromRawClient
                else -> docClient
            }
            val rawBatch = raw?.let { stringFromFirestoreValue(it["batchNumber"]) }.orEmpty()
            val rawTxn = raw?.let { stringFromFirestoreValue(it["transactionNumber"]) }.orEmpty()
            val rawAuth = raw?.let { stringFromFirestoreValue(it["authCode"]) }.orEmpty()
            val rawLast4 = raw?.let { stringFromFirestoreValue(it["last4"]) }.orEmpty()
            val rawPn = pnReferenceFromPaymentMap(raw)
            p.copy(
                referenceId = newRef,
                clientReferenceId = newClient,
                batchNumber = p.batchNumber.ifBlank { rawBatch },
                transactionNumber = p.transactionNumber.ifBlank { rawTxn },
                authCode = p.authCode.ifBlank { rawAuth },
                last4 = p.last4.ifBlank { rawLast4 },
                pnReferenceId = p.pnReferenceId.ifBlank { rawPn },
            )
        }
    }

    /**
     * Uses [Orders] HPP mirror fields when the transaction leg still has no gateway ref
     * (legacy ecommerce rows written before top-level `referenceId` existed).
     */
    fun enrichPaymentsFromOrderDoc(orderDoc: DocumentSnapshot, payments: List<TransactionPayment>): List<TransactionPayment> {
        if (!orderDoc.exists()) return payments
        val hppTxnId = nonBlank(orderDoc.getString("hppTransactionId")).orEmpty()
        val hppRef = nonBlank(orderDoc.getString("hppTransactionRefId")).orEmpty()
        val hppBatch = stringFromFirestoreValue(orderDoc.get("hppBatchNumber"))
        val hppTxnNum = stringFromFirestoreValue(orderDoc.get("hppTransactionNumber"))
        val hppAuth = nonBlank(orderDoc.getString("hppApprovalCode")).orEmpty()
        val hppLast4 = nonBlank(orderDoc.getString("hppCardLast4")).orEmpty()

        return payments.map { p ->
            var ref = p.referenceId.trim()
            var client = p.clientReferenceId.trim()
            if (ref.isEmpty() && hppTxnId.isNotEmpty()) ref = hppTxnId
            if (client.isEmpty() && hppRef.isNotEmpty()) client = hppRef
            p.copy(
                referenceId = ref,
                clientReferenceId = client,
                batchNumber = p.batchNumber.ifBlank { hppBatch },
                transactionNumber = p.transactionNumber.ifBlank { hppTxnNum },
                authCode = p.authCode.ifBlank { hppAuth },
                last4 = p.last4.ifBlank { hppLast4 },
            )
        }
    }

    fun anyCardLegMissingGatewayRef(payments: List<TransactionPayment>): Boolean =
        payments.any { p ->
            !p.paymentType.equals("Cash", ignoreCase = true) &&
                p.referenceId.isBlank() &&
                p.clientReferenceId.isBlank()
        }
}
