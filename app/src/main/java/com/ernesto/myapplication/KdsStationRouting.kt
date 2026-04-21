package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Shared rules for which tickets a KDS shows, aligned with
 * [com.ernesto.kds.ui.KdsViewModel.filterOrdersByMenuAssignment] and the web dashboard.
 */
object KdsStationRouting {

    const val COLLECTION = "kds_devices"

    fun isDeviceSelectable(doc: DocumentSnapshot): Boolean {
        if (doc.contains("isActive") && doc.getBoolean("isActive") == false) return false
        if (doc.contains("isPaired") && doc.getBoolean("isPaired") == false) return false
        return true
    }

    fun assignedItemIds(doc: DocumentSnapshot): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val raw = doc.get("assignedItemIds") as? List<*> ?: return emptySet()
        return raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun assignedCategoryIds(doc: DocumentSnapshot): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val raw = doc.get("assignedCategoryIds") as? List<*> ?: return emptySet()
        return raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Category placements for a [MenuItems] doc (dashboard `placementCategoryIds`). */
    fun placementCategoryIdsFromMenuItemDoc(doc: DocumentSnapshot): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val rawList = doc.get("categoryIds") as? List<*>
        if (rawList != null && rawList.isNotEmpty()) {
            return rawList.mapNotNull { it as? String }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        val catId = doc.getString("categoryId")?.trim().orEmpty()
        return if (catId.isNotEmpty()) setOf(catId) else emptySet()
    }

    fun deviceCoversMenuItem(
        doc: DocumentSnapshot,
        menuItemId: String,
        itemPlacementCategoryIds: Set<String>,
    ): Boolean {
        val itemIds = assignedItemIds(doc)
        val catIds = assignedCategoryIds(doc)
        if (itemIds.isEmpty() && catIds.isEmpty()) return true
        if (menuItemId in itemIds) return true
        if (itemPlacementCategoryIds.isNotEmpty() && catIds.isNotEmpty()) {
            if (itemPlacementCategoryIds.any { it in catIds }) return true
        }
        return false
    }

    /** Whether this KDS routes the whole [categoryId] (explicit list or “all tickets”). */
    fun deviceCoversCategory(doc: DocumentSnapshot, categoryId: String): Boolean {
        val cid = categoryId.trim()
        if (cid.isEmpty()) return false
        val itemIds = assignedItemIds(doc)
        val catIds = assignedCategoryIds(doc)
        if (itemIds.isEmpty() && catIds.isEmpty()) return true
        return cid in catIds
    }
}
