package com.volt.maximobile

import com.volt.shared.data.OrderModifier
import kotlin.math.round

object CartLineHelpers {

    fun modifiersTotalDollars(modifiers: List<OrderModifier>): Double {
        fun flatten(mods: List<OrderModifier>): List<OrderModifier> =
            mods.flatMap { listOf(it) + flatten(it.children) }
        return flatten(modifiers).filter { it.action == "ADD" }.sumOf { it.price }
    }

    fun unitPriceDollars(basePriceDollars: Double, modifiers: List<OrderModifier>): Double =
        basePriceDollars + modifiersTotalDollars(modifiers)

    fun cartLineKey(
        itemId: String,
        modifiers: List<OrderModifier>,
        guest: Int = 0,
        basePrice: Double = 0.0,
    ): String {
        fun modKey(mods: List<OrderModifier>): String =
            mods.sortedBy { "${it.action}|${it.name}|${it.price}" }.joinToString("|") { mod ->
                val childPart = if (mod.children.isNotEmpty()) "[${modKey(mod.children)}]" else ""
                "${mod.action}:${mod.name}:${mod.price}$childPart"
            }
        val modsKey = modKey(modifiers)
        val guestPart = if (guest > 0) "__G$guest" else ""
        val cents = round(basePrice * 100.0).toLong()
        return "${itemId}__${modsKey}${guestPart}__P$cents"
    }
}
