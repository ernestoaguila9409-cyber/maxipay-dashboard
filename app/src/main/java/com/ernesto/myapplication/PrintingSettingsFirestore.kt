package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Shared printing preferences for POS behavior.
 * Document: [COLLECTION]/[DOCUMENT_ID].
 */
object PrintingSettingsFirestore {
    const val COLLECTION = "settings"
    const val DOCUMENT_ID = "printing"
    const val FIELD_PRINT_TRIGGER_MODE = "printTriggerMode"
    const val FIELD_PRINT_ITEM_FILTER_MODE = "printItemFilterMode"
    /** Set after kitchen chits are printed for FIRST_EVENT / ON_PAYMENT deduplication. */
    const val FIELD_KITCHEN_CHITS_PRINTED_AT = "kitchenChitsPrintedAt"

    const val ON_SEND = "ON_SEND"
    const val ON_PAYMENT = "ON_PAYMENT"
    const val FIRST_EVENT = "FIRST_EVENT"

    val ALL_MODES = setOf(ON_SEND, ON_PAYMENT, FIRST_EVENT)

    const val ALL_ITEMS = "ALL_ITEMS"
    const val BY_LABEL = "BY_LABEL"

    val ALL_ITEM_FILTER_MODES = setOf(ALL_ITEMS, BY_LABEL)

    fun documentRef(db: FirebaseFirestore) =
        db.collection(COLLECTION).document(DOCUMENT_ID)

    fun printTriggerModeFromSnapshot(snap: DocumentSnapshot): String {
        val raw = snap.getString(FIELD_PRINT_TRIGGER_MODE)?.trim()
        if (raw != null && raw in ALL_MODES) return raw
        return FIRST_EVENT
    }

    fun isPrintTriggerModeValid(raw: String?): Boolean =
        raw != null && raw.trim() in ALL_MODES

    fun printItemFilterModeFromSnapshot(snap: DocumentSnapshot): String {
        val raw = snap.getString(FIELD_PRINT_ITEM_FILTER_MODE)?.trim()
        if (raw != null && raw in ALL_ITEM_FILTER_MODES) return raw
        return BY_LABEL
    }

    fun isPrintItemFilterModeValid(raw: String?): Boolean =
        raw != null && raw.trim() in ALL_ITEM_FILTER_MODES

    fun defaultPrintingDocument(): HashMap<String, String> = hashMapOf(
        FIELD_PRINT_TRIGGER_MODE to FIRST_EVENT,
        FIELD_PRINT_ITEM_FILTER_MODE to BY_LABEL,
    )
}
