package com.ernesto.myapplication.payments

import android.content.Context

/**
 * Resolves the full URL (base + endpoint path) for each SPIn operation using
 * the active [PaymentTerminalConfig]. Falls back to [SpinDefaults] if the
 * dashboard has not configured a terminal yet.
 *
 * Every hardcoded `https://spinpos.net/v2/...` call site in the app goes
 * through one of these helpers.
 */
object SpinApiUrls {

    fun auth(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.AUTH)

    fun capture(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.CAPTURE)

    fun tipAdjust(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.TIP_ADJUST)

    fun sale(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.SALE)

    fun voidPayment(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.VOID)

    fun refund(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.REFUND)

    fun settle(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.SETTLE)

    fun status(context: Context): String =
        url(context, PaymentTerminalConfig.Companion.EndpointKey.STATUS)

    /** Base URL currently in use (for logging / diagnostics). */
    fun baseUrl(context: Context): String =
        PaymentTerminalRepository.getActiveConfig(context)?.baseUrl
            ?.takeIf { it.isNotBlank() }
            ?: SpinDefaults.BASE_URL

    private fun url(context: Context, endpointKey: String): String {
        val cfg = PaymentTerminalRepository.getActiveConfig(context)
        val configured = cfg?.endpointUrl(endpointKey).orEmpty()
        if (configured.isNotBlank()) return configured
        val fallbackPath = SpinDefaults.ENDPOINTS[endpointKey].orEmpty()
        val base = SpinDefaults.BASE_URL.trimEnd('/')
        return "$base$fallbackPath"
    }
}
