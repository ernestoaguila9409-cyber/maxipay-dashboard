package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Per-printer kitchen chit typography from Firestore `Printers/{id}.kitchenTicketStyle`.
 * Font sizes match [ReceiptSettings] / ESC/POS GS ! n: 0=Normal, 1=Large, 2=X-Large.
 */
data class KitchenTicketStyle(
    val showTableLineOnlyForDineIn: Boolean = true,
    val showRoutingTag: Boolean = false,
    val titleFontSize: Int = 0,
    val titleBold: Boolean = false,
    val metaFontSize: Int = 0,
    val metaBold: Boolean = false,
    val dividerFontSize: Int = 0,
    val dividerBold: Boolean = false,
    val itemFontSize: Int = 0,
    val itemBold: Boolean = false,
    val modifierFontSize: Int = 0,
    val modifierBold: Boolean = false,
    val stationTagFontSize: Int = 0,
    val stationTagBold: Boolean = false,
    val notesHeadingFontSize: Int = 0,
    val notesHeadingBold: Boolean = false,
    val notesBodyFontSize: Int = 0,
    val notesBodyBold: Boolean = false,
) {
    companion object {
        val DEFAULT = KitchenTicketStyle()

        /** Base columns for 58mm kitchen paper at normal width (matches [KitchenTicketBuilder.LINE_WIDTH]). */
        private const val BASE_COLS = 32

        fun lineWidthChars(fontSize: Int): Int = when (fontSize.coerceIn(0, 2)) {
            2 -> BASE_COLS / 2
            else -> BASE_COLS
        }

        fun fromFirestoreMap(raw: Map<String, Any>?): KitchenTicketStyle {
            if (raw == null) return DEFAULT
            fun intKey(k: String, d: Int): Int {
                val v = raw[k] ?: return d
                val n = when (v) {
                    is Int -> v
                    is Long -> v.toInt()
                    is Number -> v.toInt()
                    else -> return d
                }
                return n.coerceIn(0, 2)
            }
            fun boolKey(k: String, d: Boolean) = raw[k] as? Boolean ?: d
            return KitchenTicketStyle(
                showTableLineOnlyForDineIn = boolKey("showTableLineOnlyForDineIn", true),
                showRoutingTag = boolKey("showRoutingTag", false),
                titleFontSize = intKey("titleFontSize", 0),
                titleBold = boolKey("titleBold", false),
                metaFontSize = intKey("metaFontSize", 0),
                metaBold = boolKey("metaBold", false),
                dividerFontSize = intKey("dividerFontSize", 0),
                dividerBold = boolKey("dividerBold", false),
                itemFontSize = intKey("itemFontSize", 0),
                itemBold = boolKey("itemBold", false),
                modifierFontSize = intKey("modifierFontSize", 0),
                modifierBold = boolKey("modifierBold", false),
                stationTagFontSize = intKey("stationTagFontSize", 0),
                stationTagBold = boolKey("stationTagBold", false),
                notesHeadingFontSize = intKey("notesHeadingFontSize", 0),
                notesHeadingBold = boolKey("notesHeadingBold", false),
                notesBodyFontSize = intKey("notesBodyFontSize", 0),
                notesBodyBold = boolKey("notesBodyBold", false),
            )
        }

        fun fromPrinterDocument(doc: DocumentSnapshot): KitchenTicketStyle {
            @Suppress("UNCHECKED_CAST")
            val map = doc.get("kitchenTicketStyle") as? Map<String, Any>
            return fromFirestoreMap(map)
        }
    }
}
