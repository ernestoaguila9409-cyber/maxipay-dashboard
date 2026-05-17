package com.volt.maximobile.dvpaylite

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.denovo.app.invokeiposgo.interfaces.TransactionListener
import com.denovo.app.invokeiposgo.launcher.IntentApplication
import org.json.JSONObject
import java.util.Locale
import java.util.Random

/**
 * Thin wrapper around the Dejavoo DvPayLite Deep-Linking SDK
 * (`com.denovo:invoke-dvpay-lite`).
 *
 * Unlike the SPIn Cloud HTTP flow, this launches the on-device DvPayLite app
 * via Android Intent and receives the result through [ActivityResultLauncher].
 */
object DvPayLiteClient {

    private var intentApp: IntentApplication? = null

    fun init(ctx: Context) {
        if (intentApp == null) {
            intentApp = IntentApplication(ctx.applicationContext)
        }
    }

    fun getIntentApplication(): IntentApplication =
        intentApp ?: throw IllegalStateException("DvPayLiteClient.init() not called")

    fun handleResult(result: androidx.activity.result.ActivityResult) {
        intentApp?.handleResultCallBack(result)
    }

    /**
     * Launch a credit/debit SALE via the on-device DvPayLite app.
     *
     * @param amountDollars  total including tax, e.g. 12.50
     * @param paymentType    "Credit" or "Debit"
     * @param tipDollars     tip amount (0.00 if none)
     * @param launcher       the [ActivityResultLauncher] registered in the activity
     * @param onSuccess      callback with the full transaction-result JSON
     * @param onFailure      callback with error message
     */
    fun performSale(
        amountDollars: Double,
        paymentType: String,
        tipDollars: Double = 0.0,
        launcher: ActivityResultLauncher<Intent>,
        onSuccess: (JSONObject) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val app = getIntentApplication()
        val refId = "MX" + generateRandom(12)
        val json = JSONObject().apply {
            put("type", "SALE")
            put("paymentType", paymentType.ifBlank { "Credit" })
            put("amount", String.format(Locale.US, "%.2f", amountDollars))
            put("tip", String.format(Locale.US, "%.2f", tipDollars))
            put("applicationType", "DVPAYLITE")
            put("refId", refId)
            put("receiptType", "No")
            put("showTipScreen", "No")
            put("showBreakupScreen", "No")
            put("isTxnStatusScreenRequired", "No")
        }

        app.setTransactionListener(object : TransactionListener {
            override fun onApplicationLaunched(result: JSONObject?) {}

            override fun onApplicationLaunchFailed(errorResult: JSONObject) {
                val msg = errorResult.optString("error_message", "DvPayLite launch failed")
                onFailure(msg)
            }

            override fun onTransactionSuccess(transactionResult: JSONObject?) {
                if (transactionResult != null) {
                    onSuccess(transactionResult)
                } else {
                    onFailure("Empty transaction result")
                }
            }

            override fun onTransactionFailed(errorResult: JSONObject) {
                val msg = errorResult.optString("error_message", "Transaction declined")
                onFailure(msg)
            }
        })

        app.performTransaction(json, launcher)
    }

    private fun generateRandom(length: Int): String {
        val rng = Random()
        val digits = CharArray(length)
        digits[0] = ('1' + rng.nextInt(9))
        for (i in 1 until length) {
            digits[i] = ('0' + rng.nextInt(10))
        }
        return String(digits)
    }
}
