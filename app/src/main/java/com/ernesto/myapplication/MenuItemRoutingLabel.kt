package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Resolves the kitchen routing label from a [MenuItems] document.
 * Must stay aligned with [ItemDetailActivity]: non-empty [labels] array wins over [printerLabel].
 */
object MenuItemRoutingLabel {

    /** Returns the item-level label only (ignores category/subcategory inheritance). */
    fun fromMenuItemDoc(doc: DocumentSnapshot): String? {
        @Suppress("UNCHECKED_CAST")
        val labelsField = (doc.get("labels") as? List<*>)
            ?.mapNotNull { it as? String }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val printerLabelField = doc.getString("printerLabel")?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            labelsField.isNotEmpty() -> labelsField.first()
            printerLabelField != null -> printerLabelField
            else -> null
        }
    }

    /**
     * Resolves the effective label using the inheritance chain:
     * **item → subcategory → category**.
     *
     * @param itemLabel    The item's own label (from [fromMenuItemDoc]).
     * @param subcategoryLabel  The subcategory's `kitchenLabel` (may be empty/null).
     * @param categoryLabel     The category's `kitchenLabel` (may be empty/null).
     */
    fun resolve(
        itemLabel: String?,
        subcategoryLabel: String?,
        categoryLabel: String?,
    ): String? {
        itemLabel?.takeIf { it.isNotBlank() }?.let { return it }
        subcategoryLabel?.takeIf { it.isNotBlank() }?.let { return it }
        categoryLabel?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }
}
