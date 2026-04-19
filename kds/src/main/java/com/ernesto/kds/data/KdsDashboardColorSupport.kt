package com.ernesto.kds.data

import androidx.compose.ui.graphics.Color

/**
 * Dashboard color keys/hex aligned with the POS app and MaxiPay dashboard palette.
 */
internal object KdsColorPalette {
    private val colors = mapOf(
        "green" to 0xFF2E7D32,
        "orange" to 0xFFE65100,
        "teal" to 0xFF00897B,
        "purple" to 0xFF6A4FB3,
        "blue" to 0xFF1976D2,
        "red" to 0xFFC62828,
        "amber" to 0xFFF9A825,
        "indigo" to 0xFF3949AB,
        "pink" to 0xFFAD1457,
        "cyan" to 0xFF0097A7,
    )

    fun argbForColorKey(colorKey: String): Int {
        val key = colorKey.trim().lowercase()
        val v = colors[key] ?: colors.getValue("purple")
        return v.toInt()
    }
}

internal fun defaultDashboardColorKey(dashboardKey: String): String = when (dashboardKey) {
    "dine_in" -> "green"
    "to_go" -> "orange"
    "bar" -> "teal"
    else -> "purple"
}

internal fun posOrderTypeToDashboardKey(orderType: String): String {
    return when (orderType.trim().uppercase()) {
        "DINE_IN" -> "dine_in"
        "TO_GO", "TAKEOUT", "TAKE_OUT" -> "to_go"
        "BAR", "BAR_TAB" -> "bar"
        else -> "to_go"
    }
}

@Suppress("UNCHECKED_CAST")
internal fun parseDashboardColorKeys(raw: Any?): Map<String, String> {
    val list = raw as? List<*> ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for (item in list) {
        val map = item as? Map<*, *> ?: continue
        val key = (map.get("key") as? String)?.trim()?.lowercase() ?: continue
        var ck = (map.get("colorKey") as? String)?.trim().orEmpty()
        if (ck.isEmpty()) ck = (map.get("color_key") as? String)?.trim().orEmpty()
        if (ck.isEmpty()) ck = defaultDashboardColorKey(key)
        out[key] = ck
    }
    return out
}

internal fun headerColorForOrderType(orderType: String, moduleColorKeys: Map<String, String>): Color {
    val dKey = posOrderTypeToDashboardKey(orderType)
    val colorKey = moduleColorKeys[dKey]?.takeIf { it.isNotBlank() }
        ?: defaultDashboardColorKey(dKey)
    return Color(KdsColorPalette.argbForColorKey(colorKey))
}
