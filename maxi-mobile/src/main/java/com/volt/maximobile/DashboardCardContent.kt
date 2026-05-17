package com.volt.maximobile

import android.content.Context

internal object DashboardCardContent {

    fun subtitleFor(context: Context, moduleKey: String): String {
        val resId = when (moduleKey) {
            "dine_in" -> R.string.dashboard_desc_dine_in
            "to_go" -> R.string.dashboard_desc_to_go
            "bar" -> R.string.dashboard_desc_bar
            "online_orders" -> R.string.dashboard_desc_online_orders
            "transactions" -> R.string.dashboard_desc_transactions
            "settle_batch" -> R.string.dashboard_desc_settle_batch
            "employees" -> R.string.dashboard_desc_employees
            "customers" -> R.string.dashboard_desc_customers
            "orders" -> R.string.dashboard_desc_orders
            "setup" -> R.string.dashboard_desc_setup
            "modifiers" -> R.string.dashboard_desc_modifiers
            "inventory" -> R.string.dashboard_desc_inventory
            "reports" -> R.string.dashboard_desc_reports
            "printers" -> R.string.dashboard_desc_printers
            "cash_flow" -> R.string.dashboard_desc_cash_flow
            "tips" -> R.string.dashboard_desc_tips
            "reservation" -> R.string.dashboard_desc_reservation
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else ""
    }

    fun profileInitials(employeeName: String): String {
        val parts = employeeName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return "?"
        if (parts.size == 1) return parts[0].take(2).uppercase()
        return buildString {
            append(parts.first().first().uppercaseChar())
            append(parts.last().first().uppercaseChar())
        }
    }

    fun toCardItems(
        context: Context,
        modules: List<DashboardModule>,
        badges: Map<String, Int>,
    ): List<DashboardCardItem> {
        return modules.map { module ->
            DashboardCardItem(
                moduleKey = module.key,
                title = module.label,
                subtitle = subtitleFor(context, module.key),
                iconRes = IconRegistry.getResId(module.iconName),
                color = DashboardTilePalette.filledTileColor(module),
                badge = badges[module.key] ?: 0,
            )
        }
    }

    fun partitionPages(
        context: Context,
        modules: List<DashboardModule>,
        badges: Map<String, Int>,
    ): List<List<DashboardCardItem>> {
        val byKey = modules.associateBy { it.key }
        val usedKeys = mutableSetOf<String>()
        val primary = mutableListOf<DashboardModule>()

        for (key in listOf("dine_in", "to_go", "bar")) {
            byKey[key]?.let {
                primary.add(it)
                usedKeys.add(key)
            }
        }

        if (byKey.containsKey("online_orders")) {
            byKey["online_orders"]?.let {
                primary.add(it)
                usedKeys.add("online_orders")
            }
        } else {
            modules.firstOrNull { it.key !in usedKeys && it.key !in PRIMARY_TAIL_KEYS }?.let {
                primary.add(it)
                usedKeys.add(it.key)
            }
        }

        for (key in PRIMARY_TAIL_KEYS) {
            byKey[key]?.let {
                primary.add(it)
                usedKeys.add(key)
            }
        }

        for (module in modules) {
            if (primary.size >= PRIMARY_SLOT_COUNT) break
            if (module.key !in usedKeys) {
                primary.add(module)
                usedKeys.add(module.key)
            }
        }

        val firstPageModules = primary.take(PRIMARY_SLOT_COUNT)
        val pages = mutableListOf(firstPageModules.map { module ->
            toCardItem(context, module, badges)
        })

        val firstPageKeys = firstPageModules.map { it.key }.toSet()
        val secondary = modules.filter { it.key !in firstPageKeys }
        secondary.chunked(PRIMARY_SLOT_COUNT).forEach { chunk ->
            pages.add(chunk.map { module -> toCardItem(context, module, badges) })
        }
        return pages
    }

    private fun toCardItem(
        context: Context,
        module: DashboardModule,
        badges: Map<String, Int>,
    ): DashboardCardItem {
        return DashboardCardItem(
            moduleKey = module.key,
            title = module.label,
            subtitle = subtitleFor(context, module.key),
            iconRes = IconRegistry.getResId(module.iconName),
            color = DashboardTilePalette.filledTileColor(module),
            badge = badges[module.key] ?: 0,
        )
    }

    private const val PRIMARY_SLOT_COUNT = 6
    private val PRIMARY_TAIL_KEYS = listOf("transactions", "settle_batch")
}
