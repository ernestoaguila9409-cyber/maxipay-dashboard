package com.ernesto.myapplication

object IconRegistry {

    data class IconOption(val name: String, val label: String, val resId: Int)

    val allIcons: List<IconOption> by lazy {
        listOf(
            // No icon
            IconOption("none", "No Icon", R.drawable.none),

            // Food & Dining
            IconOption("ic_dine_in", "Dine In", R.drawable.ic_dine_in),
            IconOption("ic_restaurant", "Restaurant", R.drawable.ic_restaurant),
            IconOption("ic_bar", "Bar", R.drawable.ic_bar),
            IconOption("ic_local_dining", "Dining", R.drawable.ic_local_dining),
            IconOption("ic_coffee", "Coffee", R.drawable.ic_coffee),
            IconOption("ic_beer", "Beer", R.drawable.ic_beer),
            IconOption("ic_pizza", "Pizza", R.drawable.ic_pizza),
            IconOption("ic_sandwich", "Sandwich", R.drawable.ic_sandwich),
            IconOption("ic_apple", "Apple", R.drawable.ic_apple),
            IconOption("ic_cake", "Cake", R.drawable.ic_cake),

            // Shopping & Commerce
            IconOption("ic_to_go", "To Go", R.drawable.ic_to_go),
            IconOption("ic_shopping_cart", "Cart", R.drawable.ic_shopping_cart),
            IconOption("ic_store", "Store", R.drawable.ic_store),
            IconOption("ic_tag", "Tag", R.drawable.ic_tag),

            // Finance & Payment
            IconOption("ic_cash", "Cash", R.drawable.ic_cash),
            IconOption("ic_attach_money", "Money", R.drawable.ic_attach_money),
            IconOption("ic_credit_card", "Credit Card", R.drawable.ic_credit_card),
            IconOption("ic_payment", "Payment", R.drawable.ic_payment),
            IconOption("ic_settle_batch", "Settle Batch", R.drawable.ic_settle_batch),
            IconOption("ic_account_balance", "Finance", R.drawable.ic_account_balance),
            IconOption("ic_coins", "Coins", R.drawable.ic_coins),
            IconOption("ic_piggy_bank", "Savings", R.drawable.ic_piggy_bank),
            IconOption("ic_percent", "Percent", R.drawable.ic_percent),

            // People
            IconOption("ic_employees", "Employees", R.drawable.ic_employees),
            IconOption("ic_customers", "Customers", R.drawable.ic_customers),
            IconOption("ic_user_plus", "Add User", R.drawable.ic_user_plus),

            // Organization & Orders
            IconOption("ic_orders", "Orders", R.drawable.ic_orders),
            IconOption("ic_transactions", "Transactions", R.drawable.ic_transactions),
            IconOption("ic_receipt_long", "Receipt", R.drawable.ic_receipt_long),
            IconOption("ic_assessment", "Assessment", R.drawable.ic_assessment),
            IconOption("ic_list", "List", R.drawable.ic_list),
            IconOption("ic_folder", "Folder", R.drawable.ic_folder),

            // Time & Schedule
            IconOption("ic_calendar", "Calendar", R.drawable.ic_calendar),
            IconOption("ic_clock", "Clock", R.drawable.ic_clock),
            IconOption("ic_timer", "Timer", R.drawable.ic_timer),

            // Analytics & Reports
            IconOption("ic_reports", "Reports", R.drawable.ic_reports),
            IconOption("ic_trending_up", "Trending", R.drawable.ic_trending_up),
            IconOption("ic_pie_chart", "Pie Chart", R.drawable.ic_pie_chart),
            IconOption("ic_line_chart", "Line Chart", R.drawable.ic_line_chart),
            IconOption("ic_activity", "Activity", R.drawable.ic_activity),

            // Settings & Tools
            IconOption("ic_settings", "Settings", R.drawable.ic_settings),
            IconOption("ic_modifiers", "Modifiers", R.drawable.ic_modifiers),
            IconOption("ic_wrench", "Wrench", R.drawable.ic_wrench),
            IconOption("ic_shield", "Shield", R.drawable.ic_shield),
            IconOption("ic_key", "Key", R.drawable.ic_key),
            IconOption("ic_lock", "Lock", R.drawable.ic_lock),

            // Layout & Navigation
            IconOption("ic_dashboard_grid", "Dashboard", R.drawable.ic_dashboard_grid),
            IconOption("ic_menu", "Menu", R.drawable.ic_menu),
            IconOption("ic_home", "Home", R.drawable.ic_home),
            IconOption("ic_star", "Star", R.drawable.ic_star),
            IconOption("ic_bookmark", "Bookmark", R.drawable.ic_bookmark),

            // Communication
            IconOption("ic_bell", "Bell", R.drawable.ic_bell),
            IconOption("ic_phone", "Phone", R.drawable.ic_phone),
            IconOption("ic_mail", "Mail", R.drawable.ic_mail),
            IconOption("ic_message", "Message", R.drawable.ic_message),

            // Location & Logistics
            IconOption("ic_local_shipping", "Shipping", R.drawable.ic_local_shipping),
            IconOption("ic_warehouse", "Warehouse", R.drawable.ic_warehouse),
            IconOption("ic_inventory", "Inventory", R.drawable.ic_inventory),
            IconOption("ic_map_pin", "Location", R.drawable.ic_map_pin),
            IconOption("ic_globe", "Globe", R.drawable.ic_globe),
            IconOption("ic_building", "Building", R.drawable.ic_building),

            // Misc
            IconOption("ic_printer", "Printer", R.drawable.ic_printer),
            IconOption("ic_qr_code", "QR Code", R.drawable.ic_qr_code),
            IconOption("ic_gift", "Gift", R.drawable.ic_gift),
            IconOption("ic_heart", "Heart", R.drawable.ic_heart),
            IconOption("ic_zap", "Zap", R.drawable.ic_zap),
            IconOption("ic_flame", "Flame", R.drawable.ic_flame),
            IconOption("ic_award", "Award", R.drawable.ic_award),
            IconOption("ic_crown", "Crown", R.drawable.ic_crown),
            IconOption("ic_target", "Target", R.drawable.ic_target),
            IconOption("ic_sparkles", "Sparkles", R.drawable.ic_sparkles),
            IconOption("ic_power", "Power", R.drawable.ic_power),
            IconOption("ic_eye", "Eye", R.drawable.ic_eye),
            IconOption("ic_wifi", "WiFi", R.drawable.ic_wifi),
            IconOption("ic_download", "Download", R.drawable.ic_download),
            IconOption("ic_upload", "Upload", R.drawable.ic_upload),
            IconOption("ic_refresh", "Refresh", R.drawable.ic_refresh),
            IconOption("ic_trash", "Trash", R.drawable.ic_trash),
            IconOption("ic_plus", "Plus", R.drawable.ic_plus),
            IconOption("ic_search", "Search", R.drawable.ic_search)
        )
    }

    fun getResId(iconName: String): Int {
        return allIcons.find { it.name == iconName }?.resId ?: R.drawable.ic_settings
    }
}
