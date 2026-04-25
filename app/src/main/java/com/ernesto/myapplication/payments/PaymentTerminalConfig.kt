package com.ernesto.myapplication.payments

/**
 * Payment terminal configuration stored in Firestore.
 *
 * Written by the web dashboard ("Payments" page) into `payment_terminals/{id}`.
 * Android never hardcodes provider URLs or credentials — everything comes from
 * the active document in this collection.
 *
 * @see PaymentTerminalRepository
 */
data class PaymentTerminalConfig(
    val id: String,
    val name: String,
    val provider: String,
    val deviceModel: String,
    val baseUrl: String,
    val endpoints: Map<String, String>,
    val capabilities: Map<String, Boolean>,
    val config: Map<String, String>,
    val active: Boolean,
) {
    fun credential(key: String): String = config[key]?.trim().orEmpty()

    /** Full URL for a logical endpoint id (see [EndpointKey]). Empty if not configured. */
    fun endpointUrl(key: String): String {
        val path = endpoints[key].orEmpty()
        if (path.isBlank()) return ""
        val base = baseUrl.trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return "$base$suffix"
    }

    fun capability(key: String): Boolean = capabilities[key] ?: false

    companion object {
        /** Catalog key constants — kept in sync with the dashboard's catalog. */
        object EndpointKey {
            const val AUTH = "auth"
            const val CAPTURE = "capture"
            const val TIP_ADJUST = "tipAdjust"
            const val SALE = "sale"
            const val VOID = "void"
            const val REFUND = "refund"
            const val SETTLE = "settle"
            const val STATUS = "status"
        }

        object ConfigKey {
            const val TPN = "tpn"
            const val REGISTER_ID = "registerId"
            const val AUTH_KEY = "authKey"
        }

        const val PROVIDER_SPIN = "SPIN"
    }
}
