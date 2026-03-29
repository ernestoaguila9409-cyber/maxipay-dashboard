package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

const val LINE_WIDTH = 48
const val LINE_WIDTH_WIDE = 24

fun formatLine(left: String, right: String, width: Int = LINE_WIDTH): String {
    val space = (width - left.length - right.length).coerceAtLeast(1)
    return left + " ".repeat(space) + right
}

data class ReceiptSettings(
    val businessName: String = "My Restaurant",
    val addressText: String = "123 Main Street\nCity, ST 12345\nTel: (555) 123-4567",
    val showServerName: Boolean = true,
    val showDateTime: Boolean = true,
    val boldBizName: Boolean = true,
    val boldAddress: Boolean = true,
    val boldOrderInfo: Boolean = true,
    val boldItems: Boolean = false,
    val boldTotals: Boolean = false,
    val boldGrandTotal: Boolean = true,
    val boldFooter: Boolean = false,
    val fontSizeBizName: Int = 2,    // 0=Normal, 1=Large, 2=X-Large
    val fontSizeAddress: Int = 2,
    val fontSizeOrderInfo: Int = 2,
    val fontSizeItems: Int = 0,
    val fontSizeTotals: Int = 0,
    val fontSizeGrandTotal: Int = 1,
    val fontSizeFooter: Int = 0
) {
    companion object {
        private const val PREFS = "receipt_settings"

        val FONT_SIZE_LABELS = arrayOf("Normal", "Large", "X-Large")

        // ESC/POS GS ! n — character size select
        // 0x00 = 1x width, 1x height  (Normal)
        // 0x01 = 1x width, 2x height  (Large / tall)
        // 0x11 = 2x width, 2x height  (X-Large)
        fun escPosSizeBytes(setting: Int): ByteArray = byteArrayOf(
            0x1D, 0x21,
            when (setting) {
                1 -> 0x01
                2 -> 0x11
                else -> 0x00
            }
        )

        fun lineWidthForSize(setting: Int): Int = when (setting) {
            2 -> LINE_WIDTH_WIDE
            else -> LINE_WIDTH
        }

        fun load(context: Context): ReceiptSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ReceiptSettings(
                businessName = p.getString("businessName", null) ?: "My Restaurant",
                addressText = p.getString("addressText", null)
                    ?: "123 Main Street\nCity, ST 12345\nTel: (555) 123-4567",
                showServerName = p.getBoolean("showServerName", true),
                showDateTime = p.getBoolean("showDateTime", true),
                boldBizName = p.getBoolean("boldBizName",
                    p.getBoolean("boldBusinessInfo",
                        p.getBoolean("boldHeader", true))),
                boldAddress = p.getBoolean("boldAddress",
                    p.getBoolean("boldBusinessInfo",
                        p.getBoolean("boldHeader", true))),
                boldOrderInfo = p.getBoolean("boldOrderInfo",
                    p.getBoolean("boldHeader", true)),
                boldItems = p.getBoolean("boldItems", false),
                boldTotals = p.getBoolean("boldTotals", false),
                boldGrandTotal = p.getBoolean("boldGrandTotal", true),
                boldFooter = p.getBoolean("boldFooter", false),
                fontSizeBizName = p.getInt("fontSizeBizName",
                    p.getInt("fontSizeBusinessInfo",
                        p.getInt("fontSizeHeader", 2))),
                fontSizeAddress = p.getInt("fontSizeAddress",
                    p.getInt("fontSizeBusinessInfo",
                        p.getInt("fontSizeHeader", 2))),
                fontSizeOrderInfo = p.getInt("fontSizeOrderInfo",
                    p.getInt("fontSizeHeader", 2)),
                fontSizeItems = p.getInt("fontSizeItems", 0),
                fontSizeTotals = p.getInt("fontSizeTotals", 0),
                fontSizeGrandTotal = p.getInt("fontSizeGrandTotal", 1),
                fontSizeFooter = p.getInt("fontSizeFooter", 0)
            )
        }

        fun save(context: Context, s: ReceiptSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("businessName", s.businessName)
                putString("addressText", s.addressText)
                putBoolean("showServerName", s.showServerName)
                putBoolean("showDateTime", s.showDateTime)
                putBoolean("boldBizName", s.boldBizName)
                putBoolean("boldAddress", s.boldAddress)
                putBoolean("boldOrderInfo", s.boldOrderInfo)
                putBoolean("boldItems", s.boldItems)
                putBoolean("boldTotals", s.boldTotals)
                putBoolean("boldGrandTotal", s.boldGrandTotal)
                putBoolean("boldFooter", s.boldFooter)
                putInt("fontSizeBizName", s.fontSizeBizName)
                putInt("fontSizeAddress", s.fontSizeAddress)
                putInt("fontSizeOrderInfo", s.fontSizeOrderInfo)
                putInt("fontSizeItems", s.fontSizeItems)
                putInt("fontSizeTotals", s.fontSizeTotals)
                putInt("fontSizeGrandTotal", s.fontSizeGrandTotal)
                putInt("fontSizeFooter", s.fontSizeFooter)
                apply()
            }
        }

        private var businessInfoListener: ListenerRegistration? = null

        /**
         * Start a real-time listener on Settings/businessInfo.
         * Whenever the web dashboard changes business name, address, or phone,
         * the local ReceiptSettings SharedPreferences are updated immediately.
         */
        fun startBusinessInfoSync(context: Context) {
            stopBusinessInfoSync()
            val db = FirebaseFirestore.getInstance()
            businessInfoListener = db.collection("Settings").document("businessInfo")
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Log.w("ReceiptSettings", "Business info sync error", err)
                        return@addSnapshotListener
                    }
                    if (snap == null || !snap.exists()) return@addSnapshotListener

                    val bizName = snap.getString("businessName") ?: return@addSnapshotListener
                    val address = snap.getString("address") ?: ""
                    val phone = snap.getString("phone") ?: ""

                    val addressBlock = buildString {
                        if (address.isNotBlank()) append(address)
                        if (phone.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(phone)
                        }
                    }

                    val current = load(context)
                    if (current.businessName != bizName || current.addressText != addressBlock) {
                        save(context, current.copy(
                            businessName = bizName,
                            addressText = addressBlock
                        ))
                        Log.d("ReceiptSettings", "Synced business info: $bizName")
                    }
                }
        }

        fun stopBusinessInfoSync() {
            businessInfoListener?.remove()
            businessInfoListener = null
        }
    }
}
