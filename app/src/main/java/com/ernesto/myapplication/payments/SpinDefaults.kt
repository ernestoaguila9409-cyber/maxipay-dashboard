package com.ernesto.myapplication.payments

/**
 * Defaults used only as a safety net when nothing is configured in Firestore yet
 * (fresh install, legacy-only `Terminals`, or a brand-new environment).
 *
 * Anything stored in `payment_terminals/{id}` overrides these.
 */
internal object SpinDefaults {
    const val BASE_URL = "https://spinpos.net/v2"

    val ENDPOINTS: Map<String, String> = mapOf(
        PaymentTerminalConfig.Companion.EndpointKey.AUTH to "/Payment/Auth",
        PaymentTerminalConfig.Companion.EndpointKey.CAPTURE to "/Payment/Capture",
        PaymentTerminalConfig.Companion.EndpointKey.TIP_ADJUST to "/Payment/TipAdjust",
        PaymentTerminalConfig.Companion.EndpointKey.SALE to "/Payment/Sale",
        PaymentTerminalConfig.Companion.EndpointKey.VOID to "/Payment/Void",
        PaymentTerminalConfig.Companion.EndpointKey.REFUND to "/Payment/Return",
        PaymentTerminalConfig.Companion.EndpointKey.SETTLE to "/Payment/Settle",
        PaymentTerminalConfig.Companion.EndpointKey.STATUS to "/Payment/Status",
    )

    val CAPABILITIES: Map<String, Boolean> = mapOf(
        "supportsPreAuth" to true,
        "supportsCapture" to true,
        "supportsTipAdjust" to true,
        "supportsSale" to true,
        "supportsVoid" to true,
        "supportsRefund" to true,
        "supportsSettle" to true,
        "supportsStatusCheck" to true,
    )
}
