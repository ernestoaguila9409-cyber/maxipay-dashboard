package com.ernesto.kds.data

import com.google.firebase.firestore.QuerySnapshot

/**
 * Builds the set of normalized routing label keys from synced **Printers** docs (Firestore).
 * Same idea as POS kitchen routing: kitchen printers plus
 * receipt printers that have at least one label.
 */
object KdsKitchenRoutingLabels {

    private const val COLLECTION_PRINTERS = "Printers"
    private const val TYPE_KITCHEN = "KITCHEN"
    private const val TYPE_RECEIPT = "RECEIPT"

    fun collectionPath(): String = COLLECTION_PRINTERS

    fun normalizedLabelKeys(snapshot: QuerySnapshot?): Set<String> {
        if (snapshot == null) return emptySet()
        val out = mutableSetOf<String>()
        for (doc in snapshot.documents) {
            val type = doc.getString("type")?.trim()?.uppercase().orEmpty()
            @Suppress("UNCHECKED_CAST")
            val rawLabels = doc.get("labels") as? List<*> ?: emptyList<Any>()
            val labels = rawLabels.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            when {
                type == TYPE_KITCHEN -> labels.forEach { out.add(normalizeLabelKey(it)) }
                type == TYPE_RECEIPT && labels.isNotEmpty() -> labels.forEach { out.add(normalizeLabelKey(it)) }
            }
        }
        return out
    }

    fun normalizeLabelKey(raw: String): String = raw.trim().lowercase()
}
