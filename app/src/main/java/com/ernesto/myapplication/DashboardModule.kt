package com.ernesto.myapplication

import android.content.Context

data class DashboardModule(
    val key: String = "",
    val label: String = "",
    val iconName: String = "",
    val colorKey: String = "",
    val position: Int = 0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "key" to key,
        "label" to label,
        "iconName" to iconName,
        "colorKey" to colorKey,
        "position" to position
    )

    companion object {
        fun fromMap(map: Map<String, Any>): DashboardModule {
            val colorKey = (map["colorKey"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["color_key"] as? String)?.takeIf { it.isNotBlank() }
                ?: ""
            return DashboardModule(
                key = map["key"] as? String ?: "",
                label = map["label"] as? String ?: "",
                iconName = map["iconName"] as? String ?: "",
                colorKey = colorKey,
                position = (map["position"] as? Number)?.toInt() ?: 0
            )
        }

        fun getDefaults(): List<DashboardModule> = listOf(
            DashboardModule("dine_in", "DINE IN", "ic_dine_in", "green", 0),
            DashboardModule("to_go", "TO-GO", "ic_to_go", "orange", 1),
            DashboardModule("bar", "BAR", "ic_bar", "teal", 2),
            DashboardModule("online_orders", "ONLINE", "ic_globe", "uber_green", 3),
            DashboardModule("transactions", "TRANSACTIONS", "ic_transactions", "purple", 4),
            DashboardModule("settle_batch", "SETTLE BATCH", "ic_settle_batch", "purple", 5),
            DashboardModule("employees", "EMPLOYEES", "ic_employees", "purple", 6),
            DashboardModule("customers", "CUSTOMERS", "ic_customers", "purple", 7),
            DashboardModule("orders", "ORDERS", "ic_orders", "purple", 8),
            DashboardModule("setup", "SETUP", "ic_settings", "purple", 9),
            DashboardModule("modifiers", "MODIFIERS", "ic_modifiers", "purple", 10),
            DashboardModule("inventory", "INVENTORY", "ic_inventory", "purple", 11),
            DashboardModule("reports", "REPORTS", "ic_reports", "purple", 12),
            DashboardModule("printers", "PRINTERS", "ic_printer", "purple", 13),
            DashboardModule("cash_flow", "CASH FLOW", "ic_cash", "purple", 14),
            DashboardModule("reservation", "RESERVATION", "ic_calendar", "purple", 15),
        )

        /**
         * Ensures the ONLINE ORDERS tile exists when the saved Firestore layout predates it.
         * Inserts it right after BAR when possible; otherwise appends. Re-indexes positions.
         */
        fun mergeOnlineOrdersDashboardTile(modules: List<DashboardModule>): List<DashboardModule> {
            if (modules.any { it.key == "online_orders" }) {
                return modules.sortedBy { it.position }
            }
            val def = getDefaults().find { it.key == "online_orders" }
                ?: return modules.sortedBy { it.position }
            val sorted = modules.sortedBy { it.position }.toMutableList()
            val barIdx = sorted.indexOfFirst { it.key == "bar" }
            if (barIdx >= 0) {
                sorted.add(barIdx + 1, def)
            } else {
                sorted.add(def)
            }
            return sorted.mapIndexed { index, m -> m.copy(position = index) }
        }

        /**
         * Ensures the PRINTERS tile exists when the saved Firestore layout predates it.
         * Inserts it right after REPORTS when possible; otherwise appends. Re-indexes positions.
         */
        fun mergePrintersDashboardTile(modules: List<DashboardModule>): List<DashboardModule> {
            if (modules.any { it.key == "printers" }) {
                return modules.sortedBy { it.position }
            }
            val printerDef = getDefaults().find { it.key == "printers" } ?: return modules.sortedBy { it.position }
            val sorted = modules.sortedBy { it.position }.toMutableList()
            val reportsIdx = sorted.indexOfFirst { it.key == "reports" }
            if (reportsIdx >= 0) {
                sorted.add(reportsIdx + 1, printerDef)
            } else {
                sorted.add(printerDef)
            }
            return sorted.mapIndexed { index, m -> m.copy(position = index) }
        }

        /**
         * Ensures the RESERVATION tile exists when the saved Firestore layout predates it.
         * Inserts after CASH FLOW when possible.
         */
        fun mergeReservationDashboardTile(modules: List<DashboardModule>): List<DashboardModule> {
            if (modules.any { it.key == "reservation" }) {
                return modules.sortedBy { it.position }
            }
            val def = getDefaults().find { it.key == "reservation" } ?: return modules.sortedBy { it.position }
            val sorted = modules.sortedBy { it.position }.toMutableList()
            val cashIdx = sorted.indexOfFirst { it.key == "cash_flow" }
            if (cashIdx >= 0) {
                sorted.add(cashIdx + 1, def)
            } else {
                sorted.add(def)
            }
            return sorted.mapIndexed { index, m -> m.copy(position = index) }
        }

        /**
         * Appends a Tips tile when tips are enabled and collection is on the printed receipt
         * (not the customer-facing tip screen). Opens [TipConfigActivity] from the dashboard.
         */
        fun mergeTipDashboardTile(context: Context, modules: List<DashboardModule>): List<DashboardModule> {
            val filtered = modules.filter { it.key != "tips" }
            val showTipsTile =
                TipConfig.isTipsEnabled(context) && !TipConfig.isTipOnCustomerScreen(context)
            if (!showTipsTile) return filtered
            val maxPos = filtered.maxOfOrNull { it.position } ?: -1
            val tipMod = DashboardModule(
                key = "tips",
                label = "TIPS",
                iconName = "ic_percent",
                colorKey = "orange",
                position = maxPos + 1
            )
            return (filtered + tipMod).sortedBy { it.position }
        }
    }
}
