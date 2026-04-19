package com.ernesto.myapplication

/**
 * Resolves POS [orderType] (DINE_IN, TO_GO, BAR_TAB, …) to the same colors as the
 * dashboard order-type tiles ([Settings] / [dashboard] modules). Keeps list, detail,
 * and reports aligned with Customize Dashboard.
 */
object OrderTypeColorResolver {

    @Volatile
    private var modules: List<DashboardModule> = DashboardModule.getDefaults()

    fun updateFromDashboard(modules: List<DashboardModule>) {
        this.modules = modules
    }

    /** ARGB color for backgrounds (same channel as [ColorRegistry]). */
    fun colorArgbForOrderType(orderType: String): Int {
        val key = dashboardKeyForPosType(orderType)
        val mod = modules.find { it.key.equals(key, ignoreCase = true) }
        val colorKey = mod?.colorKey?.takeIf { it.isNotBlank() }
            ?: defaultColorKeyForDashboardKey(key)
        return ColorRegistry.getColor(colorKey)
    }

    /** #RRGGBB for [Color.parseColor]. */
    fun colorHexForOrderType(orderType: String): String {
        val c = colorArgbForOrderType(orderType)
        return String.format("#%06X", 0xFFFFFF and c)
    }

    private fun dashboardKeyForPosType(orderType: String): String {
        return when (orderType.trim().uppercase()) {
            "DINE_IN" -> "dine_in"
            "TO_GO", "TAKEOUT", "TAKE_OUT" -> "to_go"
            "BAR", "BAR_TAB" -> "bar"
            else -> "to_go"
        }
    }

    private fun defaultColorKeyForDashboardKey(dashboardKey: String): String = when (dashboardKey) {
        "dine_in" -> "green"
        "to_go" -> "orange"
        "bar" -> "teal"
        else -> "purple"
    }
}
