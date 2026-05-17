package com.volt.shared.data

data class CartItem(
    val itemId: String,
    val name: String,
    var quantity: Int,
    val basePrice: Double,
    val stock: Long,
    val modifiers: List<OrderModifier>,
    val guestNumber: Int = 0,
    val taxMode: String = "INHERIT",
    val taxIds: List<String> = emptyList(),
    val printerLabel: String? = null,
    val imageUrl: String? = null,
    val courseId: String? = null,
)
