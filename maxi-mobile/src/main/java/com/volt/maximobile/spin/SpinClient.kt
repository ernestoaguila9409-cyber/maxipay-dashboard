package com.volt.maximobile.spin

import com.volt.maximobile.BuildConfig
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

object SpinClient {

    fun saleUrl(): String {
        val base = BuildConfig.SPIN_BASE_URL.trimEnd('/')
        return "$base/Payment/Sale"
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
