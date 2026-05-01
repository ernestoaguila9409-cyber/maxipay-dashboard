package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Web checkout orders can require staff acceptance on the POS before they appear in the Online
 * orders list or KDS ([awaitingStaffConfirmOrder] + [orderSource] == `online_ordering`).
 */
object OnlineOrderStaffConfirm {
    const val FIELD_AWAITING = "awaitingStaffConfirmOrder"

    fun isAwaitingStaffWebOnline(doc: DocumentSnapshot): Boolean {
        if (doc.getBoolean(FIELD_AWAITING) != true) return false
        return doc.getString("orderSource")?.trim() == "online_ordering"
    }
}
