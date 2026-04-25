package com.ernesto.myapplication.payments

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * SPIn `/Payment/Status` probe used for reachability (POS ↔ gateway ↔ device),
 * shared by [TerminalListActivity] patterns and [PaymentTerminalReachabilitySync].
 */
object SpinTerminalConnectionProbe {

    private const val TAG = "SpinTerminalProbe"

    private val OFFLINE_MESSAGES = listOf(
        "route not found",
        "not connected",
        "timed out",
        "timeout",
        "offline",
        "unreachable",
        "unable to connect",
        "no response",
        "not responding",
        "connection refused",
        "connection failed",
        "powered off",
    )

    fun enqueueCheck(
        httpClient: OkHttpClient,
        cfg: PaymentTerminalConfig,
        callback: (connected: Boolean) -> Unit,
    ) {
        val tpn = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.TPN)
        val registerId = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.REGISTER_ID)
        val authKey = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.AUTH_KEY)
        if (tpn.isBlank() || authKey.isBlank()) {
            callback(false)
            return
        }

        val url = cfg.endpointUrl(PaymentTerminalConfig.Companion.EndpointKey.STATUS).ifBlank {
            val base = cfg.baseUrl.trimEnd('/').ifBlank { SpinDefaults.BASE_URL.trimEnd('/') }
            val path = SpinDefaults.ENDPOINTS[PaymentTerminalConfig.Companion.EndpointKey.STATUS].orEmpty()
            val suffix = if (path.startsWith("/")) path else "/$path"
            "$base$suffix"
        }

        val healthCheckRef = "hc-${tpn}-${System.currentTimeMillis()}"
        val json = JSONObject().apply {
            put("PaymentType", "Credit")
            put("ReferenceId", healthCheckRef)
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", false)
            put("Tpn", tpn)
            put("RegisterId", registerId)
            put("Authkey", authKey)
            put("SPInProxyTimeout", 8)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Probe failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                callback(parseConnectionStatus(response.code, responseText))
            }
        })
    }

    private fun parseConnectionStatus(@Suppress("UNUSED_PARAMETER") httpCode: Int, responseText: String): Boolean {
        return try {
            val jsonObj = JSONObject(responseText)
            val gr = jsonObj.optJSONObject("GeneralResponse")
            val resultCode = gr?.optString("ResultCode", "").orEmpty()
            val resultMessage = gr?.optString("ResultMessage", "").orEmpty()
            val message = gr?.optString("Message", "").orEmpty()
            val detailedMessage = gr?.optString("DetailedMessage", "").orEmpty()

            if (resultCode == "0") return true

            val combined = "$resultMessage $message $detailedMessage".lowercase()
            val isOffline = OFFLINE_MESSAGES.any { combined.contains(it) }
            !isOffline
        } catch (e: Exception) {
            Log.e(TAG, "Parse error for response: $responseText", e)
            false
        }
    }
}
