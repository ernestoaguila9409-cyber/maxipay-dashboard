package com.ernesto.myapplication

import android.content.Context

const val LINE_WIDTH = 48
const val ALIGN_CENTER = 1

fun formatLine(left: String, right: String, width: Int = LINE_WIDTH): String {
    val space = (width - left.length - right.length).coerceAtLeast(1)
    return left + " ".repeat(space) + right
}

data class ReceiptSettings(
    val businessName: String = "My Restaurant",
    val addressText: String = "123 Main Street\nCity, ST 12345\nTel: (555) 123-4567",
    val showServerName: Boolean = true,
    val showDateTime: Boolean = true
) {
    companion object {
        private const val PREFS = "receipt_settings"

        fun load(context: Context): ReceiptSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ReceiptSettings(
                businessName = p.getString("businessName", null) ?: "My Restaurant",
                addressText = p.getString("addressText", null)
                    ?: "123 Main Street\nCity, ST 12345\nTel: (555) 123-4567",
                showServerName = p.getBoolean("showServerName", true),
                showDateTime = p.getBoolean("showDateTime", true)
            )
        }

        fun save(context: Context, s: ReceiptSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("businessName", s.businessName)
                putString("addressText", s.addressText)
                putBoolean("showServerName", s.showServerName)
                putBoolean("showDateTime", s.showDateTime)
                apply()
            }
        }
    }
}
