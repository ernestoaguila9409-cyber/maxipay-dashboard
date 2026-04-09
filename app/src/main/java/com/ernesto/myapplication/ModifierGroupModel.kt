package com.ernesto.myapplication

data class ModifierGroupModel(
    val id: String = "",
    var name: String = "",
    var required: Boolean = false,
    var maxSelection: Int = 1,
    var isExpanded: Boolean = false,
    var groupType: String = "ADD",
    var options: List<ModifierOptionEntry> = emptyList()
)

data class ModifierOptionEntry(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val triggersModifierGroupIds: List<String> = emptyList(),
    /** Per-option remove (e.g. "No onions"); stored on `ModifierOptions` / embedded `options[]` as `action`. */
    val action: String = "ADD",
)
