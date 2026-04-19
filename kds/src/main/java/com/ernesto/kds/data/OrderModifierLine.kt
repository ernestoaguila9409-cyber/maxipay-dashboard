package com.ernesto.kds.data

/**
 * One displayed modifier line on a ticket, derived from POS modifiers map action.
 * isRemove is true when Firestore action was **REMOVE** (shown as `No {name}`).
 */
data class OrderModifierLine(
    val text: String,
    val isRemove: Boolean,
)
