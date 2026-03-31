package com.ernesto.myapplication

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
    val menuId: String? = null
)

data class CategoryModel(
    val id: String = "",
    val name: String = "",
    /** Canonical key for deduplication (OCR / imports); may be absent on legacy rows. */
    val normalizedName: String? = null,
    val availableOrderTypes: List<String> = listOf(),
    val scheduleIds: List<String> = listOf()
)
