package com.ernesto.myapplication

data class ItemModel(
    val id: String,
    val name: String,
    val price: Double,
    val stock: Long,
    val availableOrderTypes: List<String>? = null
)

data class CategoryModel(
    val id: String = "",
    val name: String = "",
    val availableOrderTypes: List<String> = listOf()
)