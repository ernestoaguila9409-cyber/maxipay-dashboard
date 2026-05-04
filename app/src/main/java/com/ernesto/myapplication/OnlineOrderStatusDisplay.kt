package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot

/**
 * List / badge labels for MaxiPay web online orders ([orderSource] / [orderType]),
 * matching dashboard [orderDisplayUtils.onlineOpenPaymentBadgeFromEffective].
 */
object OnlineOrderStatusDisplay {

    fun isWebOnlineOrder(doc: DocumentSnapshot): Boolean {
        val src = doc.getString("orderSource")?.trim().orEmpty()
        if (src == "online_ordering") return true
        val ot = doc.getString("orderType")?.trim().orEmpty()
        return ot.equals("ONLINE_PICKUP", ignoreCase = true)
    }

    /**
     * When [effectiveStatus] is OPEN, maps checkout [onlinePaymentChoice] to UNPAID when appropriate.
     * Otherwise returns [effectiveStatus] unchanged.
     */
    fun listBadgeStatus(doc: DocumentSnapshot, effectiveStatus: String): String {
        if (!effectiveStatus.equals("OPEN", ignoreCase = true)) return effectiveStatus
        if (!isWebOnlineOrder(doc)) return effectiveStatus
        val choice = doc.getString("onlinePaymentChoice")?.trim().orEmpty()
        val total = doc.getLong("totalInCents") ?: 0L
        val paid = doc.getLong("totalPaidInCents") ?: 0L
        return when {
            choice == "PAY_AT_STORE" || choice.isEmpty() -> "UNPAID"
            choice == "PAY_ONLINE_HPP" && total > 0L && paid < total -> "UNPAID"
            else -> effectiveStatus
        }
    }

    /** Same rule as [com.ernesto.myapplication.OrdersActivity.effectiveOrderListStatus]. */
    fun effectiveListStatus(doc: DocumentSnapshot): String {
        if (!doc.exists()) return "OPEN"
        val raw = doc.getString("status")?.trim().orEmpty().ifBlank { "OPEN" }
        when (raw.uppercase()) {
            "VOIDED", "REFUNDED" -> return raw
        }
        val total = doc.getLong("totalInCents") ?: 0L
        val paid = doc.getLong("totalPaidInCents") ?: 0L
        if (total > 0L && paid >= total) return "CLOSED"
        return raw
    }

    /**
     * Web online ordering ticket that still shows as **UNPAID** (pay at store, or HPP not fully paid).
     * Used to show cart **Update** so staff can add items before checkout.
     */
    fun isUnpaidWebOnlineOrder(doc: DocumentSnapshot): Boolean {
        if (!doc.exists()) return false
        val eff = effectiveListStatus(doc)
        return listBadgeStatus(doc, eff) == "UNPAID"
    }
}
