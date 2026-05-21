package com.volt.maximobile

import com.volt.shared.data.OrderModifier

data class ModifierPickResult(
    val itemId: String,
    val name: String,
    val basePriceDollars: Double,
    val modifiers: List<OrderModifier>,
    val taxMode: String = "INHERIT",
    val taxIds: List<String> = emptyList(),
)
