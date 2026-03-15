package com.ernesto.myapplication

data class ModifierGroupModel(
    val id: String = "",
    var name: String = "",
    var required: Boolean = false,
    var maxSelection: Int = 1,
    var isExpanded: Boolean = false,
    var groupType: String = "ADD"
)