package com.ernesto.myapplication.payments

import android.content.Context
import android.util.Log
import com.ernesto.myapplication.TerminalPrefs
import com.ernesto.myapplication.data.Transaction
import com.ernesto.myapplication.data.TransactionPayment
import com.google.firebase.firestore.DocumentSnapshot
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * SPInPos Gateway (**SPIN_P** / P-series) HTTP entry points.
 *
 * Per project convention, SPIn (SPInPos Gateway) request/response handling for voids lives here
 * (not duplicated in activities). Same `/Payment/Void` payload shape is used for Z/P in Firestore;
 * this type is the single place to adjust gateway JSON, timeouts, and result parsing.
 *
 * Card **refunds** (batch closed / Return) use the same host lookup fields as void; sending only
 * `ReferenceId` often yields “Invalid reference ID” on Dejavoo P17.
 *
 * **Sale** payloads are built by [saleRequestJson] so `/Payment/Sale` matches void/return/capture
 * (e.g. `GetExtendedData`, numeric `Amount`) — a minimal body can leave **Debit** idle on P17 while
 * **Credit** still works.
 */
object SpinGatewayP {

    private const val TAG = "SpinGatewayP"

    data class VoidHttpResult(
        /** Non-null when OkHttp failed before a response. */
        val networkError: String? = null,
        val httpCode: Int = 0,
        val responseBody: String = "",
    ) {
        val hostApproved: Boolean
            get() = networkError == null &&
                httpCode in 200..299 &&
                parseVoidResultCodeIsZero(responseBody)
    }

    fun voidDeclineMessage(responseText: String): String {
        if (responseText.isBlank()) return "No response from payment host."
        return try {
            val gen = JSONObject(responseText).optJSONObject("GeneralResponse")
            val detail = gen?.optString("DetailedMessage", "")?.trim().orEmpty()
            val shortMsg = gen?.optString("Message", "")?.trim().orEmpty()
            when {
                detail.isNotEmpty() -> detail
                shortMsg.isNotEmpty() -> shortMsg
                else -> responseText.trim().take(400)
            }
        } catch (_: Exception) {
            responseText.trim().take(400)
        }
    }

    fun isVoidHostBusyMessage(msg: String): Boolean {
        val m = msg.lowercase(Locale.US)
        return m.contains("service busy") || m.contains("host busy") || m.contains("terminal busy") ||
            m.contains("device busy") || m.contains("processor busy") || m.contains("please wait") ||
            m.contains("try again later")
    }

    private fun parseVoidResultCodeIsZero(responseText: String): Boolean = try {
        JSONObject(responseText).optJSONObject("GeneralResponse")?.optString("ResultCode", "") == "0"
    } catch (_: Exception) {
        false
    }

    /**
     * First non-cash payment leg for SPIn follow-up calls (void / return), merged with top-level
     * [Transaction] fields when a list row omits batch/txn.
     */
    fun cardLegForRefund(transaction: Transaction): TransactionPayment {
        val card = transaction.payments.firstOrNull { !it.paymentType.equals("Cash", ignoreCase = true) }
        if (card != null) {
            return card.copy(
                paymentType = card.paymentType.ifBlank { transaction.paymentType },
                referenceId = card.referenceId.trim().ifBlank { transaction.gatewayReferenceId.trim() },
                clientReferenceId = card.clientReferenceId.trim().ifBlank { transaction.clientReferenceId.trim() },
                batchNumber = card.batchNumber.ifBlank { transaction.batchNumber },
                transactionNumber = card.transactionNumber.ifBlank { transaction.transactionNumber },
                invoiceNumber = card.invoiceNumber.ifBlank { transaction.invoiceNumber },
            )
        }
        return TransactionPayment(
            paymentType = transaction.paymentType.ifBlank { "Credit" },
            cardBrand = transaction.cardBrand,
            last4 = transaction.last4,
            entryType = transaction.entryType,
            amountInCents = transaction.amountInCents,
            referenceId = transaction.gatewayReferenceId,
            clientReferenceId = transaction.clientReferenceId,
            batchNumber = transaction.batchNumber,
            transactionNumber = transaction.transactionNumber,
            invoiceNumber = transaction.invoiceNumber,
        )
    }

    /**
     * Builds the card leg from a Firestore `Transactions/{id}` snapshot (same resolution as void).
     */
    fun cardLegForHostReturnFromTxDoc(txDoc: DocumentSnapshot): TransactionPayment {
        @Suppress("UNCHECKED_CAST")
        val paymentsRaw = txDoc.get("payments") as? List<Map<String, Any>> ?: emptyList()
        fun numStr(p: Map<String, Any>, key: String): String {
            val v = p[key] ?: return ""
            return (v as? Number)?.toString() ?: v.toString().trim()
        }
        val parsed = paymentsRaw.map { p ->
            val amountCents = (p["amountInCents"] as? Number)?.toLong() ?: 0L
            TransactionPayment(
                paymentType = p["paymentType"]?.toString() ?: "",
                cardBrand = p["cardBrand"]?.toString() ?: "",
                last4 = p["last4"]?.toString() ?: "",
                entryType = p["entryType"]?.toString() ?: "",
                amountInCents = amountCents,
                referenceId = TransactionVoidReferenceResolver.gatewayRefFromPaymentMap(p),
                clientReferenceId = TransactionVoidReferenceResolver.clientRefFromPaymentMap(p),
                authCode = p["authCode"]?.toString() ?: "",
                batchNumber = numStr(p, "batchNumber"),
                transactionNumber = numStr(p, "transactionNumber"),
                invoiceNumber = p["invoiceNumber"]?.toString() ?: "",
                pnReferenceId = TransactionVoidReferenceResolver.pnReferenceFromPaymentMap(p),
            )
        }
        val cardPayments = parsed.filter { !it.paymentType.equals("Cash", ignoreCase = true) }
        val enriched = TransactionVoidReferenceResolver.enrichPaymentsForVoid(txDoc, cardPayments)
        return enriched.firstOrNull() ?: TransactionPayment(
            paymentType = txDoc.getString("paymentType") ?: "Credit",
            referenceId = TransactionVoidReferenceResolver.firstGatewayRefFromTransactionDoc(txDoc),
            clientReferenceId = TransactionVoidReferenceResolver.firstClientRefFromTransactionDoc(txDoc),
            batchNumber = txDoc.getString("batchNumber") ?: "",
            transactionNumber = txDoc.getString("transactionNumber") ?: "",
        )
    }

    /**
     * JSON body for [SpinApiUrls.sale] (`/Payment/Sale`).
     *
     * P-series PIN pads (e.g. P17) expect the same extended flags as other SPIn payment calls.
     * Omitting `GetExtendedData` / `CaptureSignature` can result in **no UI on the terminal for Debit**
     * while Credit still processes.
     *
     * `Amount` uses the same string format as the original working request (`"10.00"`).
     */
    fun saleRequestJson(
        context: Context,
        amount: Double,
        paymentType: String,
        referenceId: String,
    ): String {
        val formattedAmount = String.format(Locale.US, "%.2f", amount)
        return JSONObject().apply {
            put("Amount", formattedAmount)
            put("PaymentType", paymentType.ifBlank { "Credit" })
            put("ReferenceId", referenceId.trim())
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
        }.toString()
    }

    /**
     * JSON body for [SpinApiUrls.refund] (`/Payment/Return`), aligned with iPOS SPIn docs.
     *
     * CRITICAL: Return ≠ Void. The `ReferenceId` on a Return is a **new unique reference** for the
     * return transaction itself (like a Sale needs a unique one). It is NOT the original sale's ref.
     * Do not send empty ReconId / IsvId — the host validates min length 1 when those keys are present.
     */
    private fun spinReturnJson(
        context: Context,
        refundAmountDollars: Double,
        paymentTypeForReturn: String,
        leg: TransactionPayment,
    ): String {
        val newReturnRef = UUID.randomUUID().toString()
        return JSONObject().apply {
            put("Amount", refundAmountDollars)
            put("PaymentType", paymentTypeForReturn.ifBlank { "Credit" })
            put("ReferenceId", newReturnRef)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("MerchantNumber", JSONObject.NULL)
            put("InvoiceNumber", leg.invoiceNumber.trim())
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("IsReadyForIS", false)
            put("CallbackInfo", JSONObject().apply { put("Url", "") })
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
            put("SPInProxyTimeout", JSONObject.NULL)
            put("CustomFields", JSONObject())
        }.toString()
    }

    /**
     * POST `/Payment/Return` for card refund (full or partial). A fresh UUID is generated as the
     * Return's own ReferenceId (Return ≠ Void — it does NOT reuse the sale's ref).
     * [onComplete] runs on OkHttp’s thread.
     */
    fun enqueueRefundPayment(
        context: Context,
        refundAmountDollars: Double,
        paymentTypeForReturn: String,
        leg: TransactionPayment,
        readTimeoutSeconds: Long = 180,
        onComplete: (VoidHttpResult) -> Unit,
    ) {
        TerminalPrefs.spinOperationBlockedMessage(context)?.let { msg ->
            Log.w(TAG, "[REFUND] Blocked: $msg")
            onComplete(VoidHttpResult(networkError = msg))
            return
        }
        val json = spinReturnJson(context, refundAmountDollars, paymentTypeForReturn, leg)
        Log.d(TAG, "[REFUND_REQ] $json")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SpinApiUrls.refund(context))
            .post(body)
            .build()

        SpinCallTracker.beginCall()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.e(TAG, "[REFUND] Network error", e)
                onComplete(VoidHttpResult(networkError = e.message ?: "Network error"))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                SpinCallTracker.endCall()
                val responseText = response.body?.string().orEmpty()
                Log.d(TAG, "[REFUND] HTTP ${response.code} Response: $responseText")
                if (!parseVoidResultCodeIsZero(responseText)) {
                    Log.w(TAG, "[REFUND] Declined: ${voidDeclineMessage(responseText)}")
                }
                onComplete(VoidHttpResult(networkError = null, httpCode = response.code, responseBody = responseText))
            }
        })
    }

    /**
     * POST void for one card leg. [onComplete] runs on OkHttp’s background thread.
     */
    fun enqueueVoidPayment(
        context: Context,
        payment: TransactionPayment,
        referenceIdForVoid: String,
        readTimeoutSeconds: Long = 180,
        onComplete: (VoidHttpResult) -> Unit,
    ) {
        TerminalPrefs.spinOperationBlockedMessage(context)?.let { msg ->
            Log.w(TAG, "[VOID] Blocked: $msg")
            onComplete(VoidHttpResult(networkError = msg))
            return
        }
        val refId = referenceIdForVoid.trim()
        val amountNumber = payment.amountInCents / 100.0
        val json = JSONObject().apply {
            put("Amount", amountNumber)
            put("PaymentType", payment.paymentType.ifBlank { "Credit" })
            put("ReferenceId", refId)
            if (payment.authCode.isNotBlank()) put("AuthCode", payment.authCode)
            if (payment.invoiceNumber.isNotBlank()) put("InvoiceNumber", payment.invoiceNumber)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
            if (payment.batchNumber.isNotBlank()) {
                put("BatchNumber", payment.batchNumber.toIntOrNull() ?: payment.batchNumber)
            }
            if (payment.transactionNumber.isNotBlank()) {
                put("TransactionNumber", payment.transactionNumber.toIntOrNull() ?: payment.transactionNumber)
            }
        }.toString()
        Log.d(TAG, "[VOID_REQ] $json")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SpinApiUrls.voidPayment(context))
            .post(body)
            .build()

        SpinCallTracker.beginCall()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.e(TAG, "[VOID] Network error", e)
                onComplete(VoidHttpResult(networkError = e.message ?: "Network error"))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                SpinCallTracker.endCall()
                val responseText = response.body?.string().orEmpty()
                Log.d(TAG, "[VOID] HTTP ${response.code} Response: $responseText")
                onComplete(VoidHttpResult(networkError = null, httpCode = response.code, responseBody = responseText))
            }
        })
    }
}
