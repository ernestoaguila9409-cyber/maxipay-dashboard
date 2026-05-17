package com.volt.maximobile.spin

import com.volt.maximobile.BuildConfig
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/**
 * SPIn (Semi-Integration) REST client for **POST** `{baseUrl}/Payment/Sale`.
 *
 * Official entry points:
 * - [iPOSpays Developer Central](https://docs.ipospays.com/) → SPIn specification
 * - [SPIn REST API methods (Theneo)](https://app.theneo.io/dejavoo/spin/spin-rest-api-methods)
 *
 * Credentials (**TPN**, **Register ID**, **Auth key**) come from the iPOSpays portal with **SPIn → Cloud**
 * mode for the target terminal (same values the Dejavoo **DVSPIn** app uses for that register).
 *
 * Request shape matches the main POS `SpinGatewayP.saleRequestJson` (Amount as `"#.##"`, etc.).
 *
 * [statusProbeJson] mirrors the main POS `SpinTerminalConnectionProbe` so Maxi can
 * hit `/Payment/Status` before creating a Firestore order.
 */
object SpinClient {

    fun saleUrl(): String {
        val base = BuildConfig.SPIN_BASE_URL.trimEnd('/')
        return "$base/Payment/Sale"
    }

    fun statusUrl(): String {
        val base = BuildConfig.SPIN_BASE_URL.trimEnd('/')
        return "$base/Payment/Status"
    }

    /**
     * Lightweight `/Payment/Status` body (same core fields as main POS terminal probe).
     * Uses a short proxy timeout so unreachable terminals fail fast.
     */
    fun statusProbeJson(referenceId: String): String {
        return JSONObject().apply {
            put("PaymentType", "Credit")
            put("ReferenceId", referenceId.trim())
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", false)
            put("Tpn", BuildConfig.SPIN_TPN)
            put("RegisterId", BuildConfig.SPIN_REGISTER_ID)
            put("Authkey", BuildConfig.SPIN_AUTH_KEY)
            put("SPInProxyTimeout", 12)
        }.toString()
    }

    fun saleRequestJson(amountDollars: Double, paymentType: String, referenceId: String): String {
        val formattedAmount = String.format(Locale.US, "%.2f", amountDollars)
        return JSONObject().apply {
            put("Amount", formattedAmount)
            put("PaymentType", paymentType.ifBlank { "Credit" })
            put("ReferenceId", referenceId.trim())
            put("PrintReceipt", "No")
            put("GetReceipt", "No")
            put("CaptureSignature", false)
            put("GetExtendedData", true)
            put("Tpn", BuildConfig.SPIN_TPN)
            put("RegisterId", BuildConfig.SPIN_REGISTER_ID)
            put("Authkey", BuildConfig.SPIN_AUTH_KEY)
        }.toString()
    }

    fun newClientReferenceId(): String = UUID.randomUUID().toString()

    fun spinConfigured(): Boolean =
        BuildConfig.SPIN_TPN.isNotBlank() &&
            BuildConfig.SPIN_REGISTER_ID.isNotBlank() &&
            BuildConfig.SPIN_AUTH_KEY.isNotBlank()
}
