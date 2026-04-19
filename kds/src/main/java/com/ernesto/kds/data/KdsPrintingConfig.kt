package com.ernesto.kds.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Mirrors POS printing settings at Firestore `settings/printing`.
 * KDS visibility follows the same release rules as kitchen printers.
 */
data class KdsPrintingConfig(
    val printTriggerMode: String,
    val printItemFilterMode: String,
) {
    companion object {
        const val ON_SEND = "ON_SEND"
        const val ON_PAYMENT = "ON_PAYMENT"
        const val FIRST_EVENT = "FIRST_EVENT"

        const val ALL_ITEMS = "ALL_ITEMS"
        const val BY_LABEL = "BY_LABEL"

        val DEFAULT = KdsPrintingConfig(
            printTriggerMode = FIRST_EVENT,
            printItemFilterMode = BY_LABEL,
        )

        private val TRIGGER_MODES = setOf(ON_SEND, ON_PAYMENT, FIRST_EVENT)
        private val FILTER_MODES = setOf(ALL_ITEMS, BY_LABEL)

        private const val COLLECTION = "settings"
        private const val DOCUMENT_ID = "printing"
        private const val FIELD_PRINT_TRIGGER_MODE = "printTriggerMode"
        private const val FIELD_PRINT_ITEM_FILTER_MODE = "printItemFilterMode"

        fun printingDocument(db: FirebaseFirestore) =
            db.collection(COLLECTION).document(DOCUMENT_ID)

        fun fromSnapshot(snap: DocumentSnapshot?): KdsPrintingConfig {
            if (snap == null || !snap.exists()) return DEFAULT
            val trigger = snap.getString(FIELD_PRINT_TRIGGER_MODE)?.trim()
            val filter = snap.getString(FIELD_PRINT_ITEM_FILTER_MODE)?.trim()
            return KdsPrintingConfig(
                printTriggerMode = if (trigger in TRIGGER_MODES) trigger!! else DEFAULT.printTriggerMode,
                printItemFilterMode = if (filter in FILTER_MODES) filter!! else DEFAULT.printItemFilterMode,
            )
        }
    }
}
