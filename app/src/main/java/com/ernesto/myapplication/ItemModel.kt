package com.ernesto.myapplication

data class Pricing(
    val pos: Double? = null,
    val online: Double? = null
)

data class Channels(
    val pos: Boolean? = true,
    val online: Boolean? = false
)

data class ItemModel(
    val id: String,
    val name: String,
    val price: Double,
    val prices: Map<String, Double> = emptyMap(),
    val stock: Long,
    val categoryId: String = "",
    val availableOrderTypes: List<String>? = null,
    val modifierGroupIds: List<String> = emptyList(),
    val taxIds: List<String> = emptyList(),
    val sku: String? = null,
    val barcode: String? = null,
    val isScheduled: Boolean = false,
    val scheduleIds: List<String> = emptyList(),
    val menuId: String? = null,
    val pricing: Pricing? = null,
    val menuIds: List<String>? = null,
    val channels: Channels? = null,
    val subcategoryId: String = ""
) {
    fun getPrice(channel: String): Double = when (channel) {
        "pos" -> pricing?.pos ?: price
        "online" -> pricing?.online ?: pricing?.pos ?: price
        else -> price
    }
}

data class CategoryModel(
    val id: String = "",
    val name: String = "",
    /** Canonical key for deduplication (OCR / imports); may be absent on legacy rows. */
    val normalizedName: String? = null,
    val availableOrderTypes: List<String> = listOf(),
    val scheduleIds: List<String> = listOf()
)

data class SubcategoryModel(
    val id: String = "",
    val name: String = "",
    val categoryId: String = "",
    val order: Int = 0
)
