package com.volt.maximobile

/** Category row for Maxi menu (shared with [MainActivity] loaders). */
data class CatUi(
    val id: String,
    val name: String,
    val availableOrderTypes: List<String> = emptyList(),
)

/** One sellable menu item on the Maxi grid. */
data class ItemUi(
    val id: String,
    val name: String,
    val categoryId: String,
    val priceDollars: Double,
)
