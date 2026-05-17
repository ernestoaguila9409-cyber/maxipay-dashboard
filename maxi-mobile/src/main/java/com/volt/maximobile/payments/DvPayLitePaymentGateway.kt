package com.volt.maximobile.payments

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.volt.maximobile.dvpaylite.DvPayLiteClient
import com.volt.shared.payments.PaymentCallback
import com.volt.shared.payments.PaymentGateway
import com.volt.shared.payments.PaymentResult
import org.json.JSONObject

/**
 * [PaymentGateway] backed by the on-device DvPayLite deep-linking SDK.
 * All card operations route through the local DvPayLite app on the Dejavoo P8.
 */
class DvPayLitePaymentGateway(
    private val launcher: ActivityResultLauncher<Intent>,
) : PaymentGateway {

    override fun sale(amountCents: Long, paymentType: String, callback: PaymentCallback) {
        val dollars = amountCents / 100.0
        DvPayLiteClient.performSale(
            amountDollars = dollars,
            paymentType = paymentType.ifBlank { "Credit" },
            launcher = launcher,
            onSuccess = { json ->
                callback.onApproved(parseResult(json))
            },
            onFailure = { msg ->
                callback.onDeclined(PaymentResult(approved = false, declineReason = msg))
            },
        )
    }

    override fun void(referenceId: String, callback: PaymentCallback) {
        val app = DvPayLiteClient.getIntentApplication()
        val json = JSONObject().apply {
            put("type", "VOID")
            put("applicationType", "DVPAYLITE")
            put("refId", referenceId)
            put("receiptType", "No")
        }
        app.setTransactionListener(dvPayListener(callback))
        app.performTransaction(json, launcher)
    }

    override fun refund(amountCents: Long, referenceId: String, callback: PaymentCallback) {
        val app = DvPayLiteClient.getIntentApplication()
        val dollars = amountCents / 100.0
        val json = JSONObject().apply {
            put("type", "RETURN")
            put("applicationType", "DVPAYLITE")
            put("amount", String.format(java.util.Locale.US, "%.2f", dollars))
            put("refId", referenceId)
            put("receiptType", "No")
        }
        app.setTransactionListener(dvPayListener(callback))
        app.performTransaction(json, launcher)
    }

    override fun tipAdjust(referenceId: String, baseAmountCents: Long, tipAmountCents: Long, callback: PaymentCallback) {
        val app = DvPayLiteClient.getIntentApplication()
        val tipDollars = tipAmountCents / 100.0
        val json = JSONObject().apply {
            put("type", "TIPADJUST")
            put("applicationType", "DVPAYLITE")
            put("tip", String.format(java.util.Locale.US, "%.2f", tipDollars))
            put("refId", referenceId)
            put("receiptType", "No")
        }
        app.setTransactionListener(dvPayListener(callback))
        app.performTransaction(json, launcher)
    }

    override fun cancel() {
        // DvPayLite deep-linking doesn't support in-flight cancel from POS side
    }

    private fun dvPayListener(callback: PaymentCallback) =
        object : com.denovo.app.invokeiposgo.interfaces.TransactionListener {
            override fun onApplicationLaunched(result: JSONObject?) {}
            override fun onApplicationLaunchFailed(errorResult: JSONObject) {
                callback.onError(errorResult.optString("error_message", "DvPayLite launch failed"))
            }
            override fun onTransactionSuccess(transactionResult: JSONObject?) {
                if (transactionResult != null) callback.onApproved(parseResult(transactionResult))
                else callback.onError("Empty transaction result")
            }
            override fun onTransactionFailed(errorResult: JSONObject) {
                callback.onDeclined(PaymentResult(approved = false, declineReason = errorResult.optString("error_message", "Transaction declined")))
            }
        }

    private fun parseResult(json: JSONObject): PaymentResult {
        val extData = json.optString("extData", "")
        val extMap = parseExtData(extData)
        return PaymentResult(
            approved = true,
            authCode = json.optString("authCode", ""),
            cardBrand = extMap["cardBrand"] ?: json.optString("cardBrand", ""),
            last4 = extMap["last4"] ?: json.optString("last4", ""),
            entryType = extMap["entryType"] ?: json.optString("entryType", ""),
            referenceId = json.optString("refId", ""),
            clientReferenceId = json.optString("clientReferenceId", ""),
            batchNumber = json.optString("batchNumber", ""),
            transactionNumber = json.optString("transactionNumber", ""),
            invoiceNumber = json.optString("invoiceNumber", ""),
            pnReferenceId = json.optString("pnReferenceId", ""),
            rawResponse = extMap,
        )
    }

    private fun parseExtData(extData: String): Map<String, String> {
        if (extData.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (part in extData.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) result[kv[0].trim()] = kv[1].trim()
        }
        return result
    }
}
