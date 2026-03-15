package com.ernesto.myapplication

data class OrderModifier(
    val name: String = "",
    val action: String = "ADD",
    val price: Double = 0.0
)
