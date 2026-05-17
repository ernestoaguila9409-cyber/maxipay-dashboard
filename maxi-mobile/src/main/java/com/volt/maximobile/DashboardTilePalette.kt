package com.volt.maximobile

object DashboardTilePalette {

    fun heroColorKey(module: DashboardModule): String {
        if (module.colorKey.isNotBlank()) return module.colorKey
        return when (module.key) {
            "dine_in" -> "green"
            "to_go" -> "orange"
            "bar" -> "teal"
            else -> "purple"
        }
    }

    fun managementAccentKey(module: DashboardModule): String = when (module.key) {
        "transactions" -> "indigo"
        "settle_batch" -> "blue"
        "employees" -> "purple"
        "customers" -> "pink"
        "orders" -> "blue"
        "setup" -> "purple"
        "online_orders" -> "uber_green"
        "modifiers" -> "indigo"
        "inventory" -> "teal"
        "reports" -> "blue"
        "printers" -> "cyan"
        "cash_flow" -> "green"
        "tips" -> "amber"
        "reservation" -> "pink"
        else -> "purple"
    }

    fun secondaryAccentKey(module: DashboardModule): String = when (module.key) {
        "modifiers" -> "indigo"
        "inventory" -> "teal"
        "reports" -> "blue"
        "printers" -> "cyan"
        "cash_flow" -> "green"
        "reservation" -> "pink"
        "tips" -> "amber"
        "online_orders" -> "uber_green"
        "setup" -> "purple"
        else -> managementAccentKey(module)
    }

    fun filledTileColorKey(module: DashboardModule): String {
        if (module.key in HERO_KEYS) return heroColorKey(module)
        return if (module.key in SECONDARY_KEYS) secondaryAccentKey(module) else managementAccentKey(module)
    }

    fun filledTileColor(module: DashboardModule): Int =
        ColorRegistry.getColor(filledTileColorKey(module))

    private val HERO_KEYS = setOf("dine_in", "to_go", "bar")
    private val SECONDARY_KEYS = setOf(
        "setup",
        "modifiers",
        "inventory",
        "reports",
        "printers",
        "cash_flow",
        "reservation",
        "tips",
    )
}
