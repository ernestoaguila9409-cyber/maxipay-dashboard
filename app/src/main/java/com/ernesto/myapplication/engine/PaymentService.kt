package com.ernesto.myapplication.engine

import android.content.Context
import android.util.Log
import com.ernesto.myapplication.TerminalPrefs
import com.ernesto.myapplication.payments.SpinApiUrls
import com.ernesto.myapplication.payments.SpinCallTracker
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PreAuthResult(
    val referenceId: String,
    val authCode: String,
    val cardLast4: String,
    val cardBrand: String,
    val transactionId: String
)

data class TipAdjustResult(
    val approved: Boolean,
    val message: String
)

data class CaptureResult(
    val referenceId: String,
    val authCode: String,
    val cardLast4: String,
    val cardBrand: String,
    val entryType: String,
    val batchNumber: String,
    val transactionNumber: String
)

class PaymentService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun preAuth(
        amount: Double,
        referenceId: String,
        onSuccess: (PreAuthResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val formattedAmount = String.format(Locale.US, "%.2f", amount)

        TerminalPrefs.spinOperationBlockedMessage(context)?.let { msg ->
            onFailure(msg)
            return
        }

        val json = JSONObject().apply {
            put("Amount", formattedAmount)
            put("PaymentType", "Credit")
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
        }

        Log.d("PREAUTH_REQ", json.toString())

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SpinApiUrls.auth(context))
            .post(body)
            .build()

        SpinCallTracker.beginCall()
        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.e("PREAUTH", "Network error", e)
                onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                SpinCallTracker.endCall()
                val responseText = response.body?.string() ?: ""
                Log.d("PREAUTH_RAW", responseText)

                if (!response.isSuccessful) {
                    onFailure("Server error (${response.code})")
                    return
                }

                try {
                    val jsonObj = JSONObject(responseText)

                    val resultCode = jsonObj
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode", "") ?: ""

                    if (resultCode == "0") {
                        val authCode = jsonObj.optString("AuthCode", "")

                        val returnedRefId = jsonObj.optString("ReferenceId", "")
                            .ifBlank {
                                jsonObj.optJSONObject("GeneralResponse")
                                    ?.optString("ReferenceId", "") ?: ""
                            }
                            .ifBlank { referenceId }

                        val transactionId = jsonObj.optString("TransactionId", "")
                            .ifBlank {
                                jsonObj.optJSONObject("GeneralResponse")
                                    ?.optString("TransactionId", "") ?: ""
                            }

                        val cardData = jsonObj.optJSONObject("CardData")
                        val last4 = cardData?.optString("Last4", "") ?: ""
                        val cardBrand = cardData?.optString("CardType", "") ?: ""

                        Log.d(
                            "PREAUTH",
                            "Approved – ref=$returnedRefId auth=$authCode brand=$cardBrand last4=$last4 txn=$transactionId"
                        )

                        onSuccess(
                            PreAuthResult(
                                referenceId = returnedRefId,
                                authCode = authCode,
                                cardLast4 = last4,
                                cardBrand = cardBrand,
                                transactionId = transactionId
                            )
                        )
                    } else {
                        val msg = jsonObj.optJSONObject("GeneralResponse")
                            ?.optString("ResultMessage", "")
                            ?.takeIf { it.isNotBlank() }
                            ?: "Card declined. Unable to open tab."
                        Log.w("PREAUTH", "Declined – code=$resultCode msg=$msg")
                        onFailure(msg)
                    }
                } catch (e: Exception) {
                    Log.e("PREAUTH", "Parse error", e)
                    onFailure("Invalid response: ${e.message}")
                }
            }
        })
    }

    fun capture(
        amount: Double,
        authCode: String,
        referenceId: String,
        onSuccess: (CaptureResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val formattedAmount = String.format(Locale.US, "%.2f", amount)

        TerminalPrefs.spinOperationBlockedMessage(context)?.let { msg ->
            onFailure(msg)
            return
        }

        val json = JSONObject().apply {
            put("Amount", formattedAmount)
            put("AuthCode", authCode)
            put("PaymentType", "Credit")
            put("ReferenceId", referenceId)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
        }

        Log.d("CAPTURE_REQ", json.toString())

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SpinApiUrls.capture(context))
            .post(body)
            .build()

        SpinCallTracker.beginCall()
        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.e("CAPTURE", "Network error", e)
                onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                SpinCallTracker.endCall()
                val responseText = response.body?.string() ?: ""
                Log.d("CAPTURE_RAW", responseText)

                if (!response.isSuccessful) {
                    onFailure("Server error (${response.code})")
                    return
                }

                try {
                    val jsonObj = JSONObject(responseText)

                    val resultCode = jsonObj
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode", "") ?: ""

                    if (resultCode == "0") {
                        val returnedAuthCode = jsonObj.optString("AuthCode", "")
                            .ifBlank { authCode }

                        val returnedRefId = jsonObj.optString("ReferenceId", "")
                            .ifBlank {
                                jsonObj.optJSONObject("GeneralResponse")
                                    ?.optString("ReferenceId", "") ?: ""
                            }
                            .ifBlank {
                                jsonObj.optJSONObject("Transaction")
                                    ?.optString("ReferenceId", "") ?: ""
                            }
                            .ifBlank { referenceId }

                        val batchNumber = jsonObj.optString("BatchNumber", "")
                            .ifBlank {
                                jsonObj.optJSONObject("GeneralResponse")
                                    ?.optString("BatchNumber", "") ?: ""
                            }

                        val transactionNumber = jsonObj.optString("TransactionNumber", "")
                            .ifBlank {
                                jsonObj.optJSONObject("GeneralResponse")
                                    ?.optString("TransactionNumber", "") ?: ""
                            }

                        val cardData = jsonObj.optJSONObject("CardData")
                        val last4 = cardData?.optString("Last4", "") ?: ""
                        val cardBrand = cardData?.optString("CardType", "") ?: ""
                        val entryType = cardData?.optString("EntryType", "") ?: ""

                        Log.d(
                            "CAPTURE",
                            "Approved – ref=$returnedRefId auth=$returnedAuthCode batch=$batchNumber txn=$transactionNumber brand=$cardBrand last4=$last4 inputRef=$referenceId"
                        )

                        onSuccess(
                            CaptureResult(
                                referenceId = returnedRefId,
                                authCode = returnedAuthCode,
                                cardLast4 = last4,
                                cardBrand = cardBrand,
                                entryType = entryType,
                                batchNumber = batchNumber,
                                transactionNumber = transactionNumber
                            )
                        )
                    } else {
                        val msg = jsonObj.optJSONObject("GeneralResponse")
                            ?.optString("ResultMessage", "")
                            ?.takeIf { it.isNotBlank() }
                            ?: "Capture declined."
                        Log.w("CAPTURE", "Declined – code=$resultCode msg=$msg")
                        onFailure(msg)
                    }
                } catch (e: Exception) {
                    Log.e("CAPTURE", "Parse error", e)
                    onFailure("Invalid response: ${e.message}")
                }
            }
        })
    }

    fun tipAdjust(
        originalAmount: Double,
        tipAmount: Double,
        referenceId: String,
        onSuccess: (TipAdjustResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        TerminalPrefs.spinOperationBlockedMessage(context)?.let { msg ->
            onFailure(msg)
            return
        }

        val json = JSONObject().apply {
            put("Amount", String.format(Locale.US, "%.2f", originalAmount))
            put("TipAmount", String.format(Locale.US, "%.2f", tipAmount))
            put("PaymentType", "Credit")
            put("ReferenceId", referenceId)
            put("GetExtendedData", true)
            put("Tpn", TerminalPrefs.getTpn(context))
            put("RegisterId", TerminalPrefs.getRegisterId(context))
            put("Authkey", TerminalPrefs.getAuthKey(context))
        }

        Log.d("TIP_ADJUST_REQ", json.toString())

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SpinApiUrls.tipAdjust(context))
            .post(body)
            .build()

        SpinCallTracker.beginCall()
        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                SpinCallTracker.endCall()
                Log.e("TIP_ADJUST", "Network error", e)
                onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                SpinCallTracker.endCall()
                val responseText = response.body?.string() ?: ""
                Log.d("TIP_ADJUST_RAW", responseText)

                if (!response.isSuccessful) {
                    onFailure("Server error (${response.code})")
                    return
                }

                try {
                    val jsonObj = JSONObject(responseText)
                    val resultCode = jsonObj
                        .optJSONObject("GeneralResponse")
                        ?.optString("ResultCode", "") ?: ""

                    if (resultCode == "0") {
                        Log.d("TIP_ADJUST", "Approved – ref=$referenceId tip=$tipAmount")
                        onSuccess(TipAdjustResult(approved = true, message = "Tip adjusted successfully"))
                    } else {
                        val msg = jsonObj.optJSONObject("GeneralResponse")
                            ?.optString("ResultMessage", "")
                            ?.takeIf { it.isNotBlank() }
                            ?: "Tip adjustment declined."
                        Log.w("TIP_ADJUST", "Declined – code=$resultCode msg=$msg")
                        onFailure(msg)
                    }
                } catch (e: Exception) {
                    Log.e("TIP_ADJUST", "Parse error", e)
                    onFailure("Invalid response: ${e.message}")
                }
            }
        })
    }
}
