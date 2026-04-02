package com.ernesto.myapplication

data class OrderModifier(
    val name: String = "",
    val action: String = "ADD",
    val price: Double = 0.0,
    val groupId: String = "",
    val groupName: String = "",
    val children: List<OrderModifier> = emptyList()
)
