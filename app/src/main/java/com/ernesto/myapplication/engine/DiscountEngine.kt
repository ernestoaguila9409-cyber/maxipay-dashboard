package com.ernesto.myapplication.engine

import com.ernesto.myapplication.CartItem
import com.ernesto.myapplication.DiscountItem
import com.ernesto.myapplication.DiscountSchedule
import com.google.firebase.firestore.FirebaseFirestore

data class AppliedDiscount(
    val discountId: String,
    val discountName: String,
    val type: String,
    val value: Double,
    val applyScope: String,
    val amountInCents: Long,
    val lineKey: String? = null
)

class DiscountEngine(private val db: FirebaseFirestore) {

    private var cachedDiscounts: List<DiscountItem> = emptyList()

    fun loadDiscounts(onComplete: () -> Unit) {
        db.collection("discounts")
            .get()
            .addOnSuccessListener { snap ->
                cachedDiscounts = snap.documents.mapNotNull { doc ->
                    parseDiscount(doc)
                }
                onComplete()
            }
            .addOnFailureListener {
                cachedDiscounts = emptyList()
                onComplete()
            }
    }

    fun getActiveDiscounts(): List<DiscountItem> = cachedDiscounts

    @Suppress("UNCHECKED_CAST")
    private fun parseDiscount(doc: com.google.firebase.firestore.DocumentSnapshot): DiscountItem? {
        val name = doc.getString("name") ?: return null
        val type = doc.getString("type") ?: return null
        val value = (doc.getDouble("value") ?: doc.getLong("value")?.toDouble()) ?: return null
        val applyTo = doc.getString("applyTo") ?: "ORDER"
        val active = doc.getBoolean("active") ?: true
        val applyScope = doc.getString("applyScope") ?: if (applyTo == "ITEM") "item" else "order"
        val autoApply = doc.getBoolean("autoApply") ?: true
        val itemIds = (doc.get("itemIds") as? List<String>) ?: emptyList()

        val schedMap = doc.get("schedule") as? Map<String, Any>
        val schedule = if (schedMap != null) {
            DiscountSchedule(
                days = (schedMap["days"] as? List<String>) ?: emptyList(),
                startTime = schedMap["startTime"]?.toString() ?: "",
                endTime = schedMap["endTime"]?.toString() ?: ""
            )
        } else null

        return DiscountItem(
            id = doc.id,
            name = name,
            type = type,
            value = value,
            applyTo = applyTo,
            active = active,
            applyScope = applyScope,
            itemIds = itemIds,
            schedule = schedule,
            autoApply = autoApply
        )
    }

    /**
     * Compute all automatic discounts for the current cart.
     * Returns a list of applied discounts (item-level and order-level).
     * Only one order-level discount is applied (the best one wins).
     */
    fun computeAutoDiscounts(
        cartMap: Map<String, CartItem>,
        manualDiscountId: String? = null
    ): List<AppliedDiscount> {
        val result = mutableListOf<AppliedDiscount>()
        val eligibleDiscounts = cachedDiscounts.filter { it.active && it.isScheduleValid() }

        // Item-level discounts (auto-apply only)
        val itemDiscounts = eligibleDiscounts.filter { it.applyScope == "item" && it.autoApply }
        for (discount in itemDiscounts) {
            for ((lineKey, cartItem) in cartMap) {
                if (discount.itemIds.contains(cartItem.itemId)) {
                    val lineTotal = computeLineTotal(cartItem)
                    val discountCents = computeDiscountAmount(discount, lineTotal)
                    if (discountCents > 0L) {
                        result.add(
                            AppliedDiscount(
                                discountId = discount.id,
                                discountName = discount.name,
                                type = discount.type,
                                value = discount.value,
                                applyScope = "item",
                                amountInCents = discountCents,
                                lineKey = lineKey
                            )
                        )
                    }
                }
            }
        }

        // Order-level auto discounts
        val orderDiscounts = eligibleDiscounts.filter { it.applyScope == "order" && it.autoApply }
        val subtotalCents = computeSubtotal(cartMap)

        var bestOrderDiscount: AppliedDiscount? = null
        for (discount in orderDiscounts) {
            val discountCents = computeDiscountAmount(discount, subtotalCents)
            if (discountCents > 0L) {
                if (bestOrderDiscount == null || discountCents > bestOrderDiscount.amountInCents) {
                    bestOrderDiscount = AppliedDiscount(
                        discountId = discount.id,
                        discountName = discount.name,
                        type = discount.type,
                        value = discount.value,
                        applyScope = "order",
                        amountInCents = discountCents
                    )
                }
            }
        }

        // Manual discount
        if (manualDiscountId != null) {
            val manualDiscount = eligibleDiscounts.find {
                it.id == manualDiscountId && it.applyScope == "manual"
            }
            if (manualDiscount != null) {
                val discountCents = computeDiscountAmount(manualDiscount, subtotalCents)
                if (discountCents > 0L) {
                    val manualApplied = AppliedDiscount(
                        discountId = manualDiscount.id,
                        discountName = manualDiscount.name,
                        type = manualDiscount.type,
                        value = manualDiscount.value,
                        applyScope = "manual",
                        amountInCents = discountCents
                    )
                    if (bestOrderDiscount == null || manualApplied.amountInCents > bestOrderDiscount.amountInCents) {
                        bestOrderDiscount = manualApplied
                    }
                }
            }
        }

        if (bestOrderDiscount != null) {
            result.add(bestOrderDiscount)
        }

        return result
    }

    /**
     * Get manual discounts available in checkout that match current schedule.
     */
    fun getAvailableManualDiscounts(): List<DiscountItem> {
        return cachedDiscounts.filter {
            it.active && (it.applyScope == "manual" || !it.autoApply) && it.isScheduleValid()
        }
    }

    private fun computeLineTotal(cartItem: CartItem): Long {
        val modTotal = cartItem.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
        val unitPriceCents = ((cartItem.basePrice + modTotal) * 100).toLong()
        return unitPriceCents * cartItem.quantity
    }

    private fun computeSubtotal(cartMap: Map<String, CartItem>): Long {
        var total = 0L
        for ((_, item) in cartMap) {
            total += computeLineTotal(item)
        }
        return total
    }

    private fun computeDiscountAmount(discount: DiscountItem, baseCents: Long): Long {
        return when (discount.type) {
            "PERCENTAGE" -> ((baseCents * discount.value) / 100.0).toLong()
            "FIXED" -> (discount.value * 100).toLong().coerceAtMost(baseCents)
            else -> 0L
        }
    }
}
