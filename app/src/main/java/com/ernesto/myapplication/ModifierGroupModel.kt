package com.ernesto.myapplication

data class ModifierGroupModel(
    val id: String,
    val name: String,
    var isExpanded: Boolean = false,
    var options: MutableList<ModifierOptionModel> = mutableListOf()
)