package com.ernesto.myapplication

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * When a category's `kitchenLabel` is removed, clears that same routing value from:
 * - All [subcategories] under the category whose `kitchenLabel` matches (normalized)
 * - All [MenuItems] placed in that category (`categoryId` or `categoryIds` contains id) whose
 *   `printerLabel` or `labels` entries match (normalized)
 *
 * Keeps Firestore aligned so POS and the web dashboard no longer show a ghost label after the
 * category label is cleared.
 */
object CategoryKitchenLabelCascade {

    fun afterCategoryKitchenLabelRemoved(
        db: FirebaseFirestore,
        categoryId: String,
        removedLabelRaw: String,
        onDone: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val removedNorm = PrinterLabelKey.normalize(removedLabelRaw)
        if (removedNorm.isEmpty()) {
            onDone()
            return
        }

        val pending = mutableListOf<Pair<DocumentReference, Map<String, Any>>>()

        fun commitPending() {
            if (pending.isEmpty()) {
                onDone()
                return
            }
            commitInChunks(db, pending, 0, onDone, onError)
        }

        db.collection("subcategories").whereEqualTo("categoryId", categoryId).get()
            .addOnSuccessListener { subSnap ->
                for (subDoc in subSnap.documents) {
                    val kl = subDoc.getString("kitchenLabel")?.trim().orEmpty()
                    if (kl.isNotEmpty() && PrinterLabelKey.normalize(kl) == removedNorm) {
                        pending.add(subDoc.reference to mapOf("kitchenLabel" to FieldValue.delete()))
                    }
                }

                db.collection("MenuItems").whereEqualTo("categoryId", categoryId).get()
                    .addOnSuccessListener { priSnap ->
                        val seen = mutableSetOf<String>()
                        for (doc in priSnap.documents) {
                            seen.add(doc.id)
                            collectItemMatch(doc, removedNorm, pending)
                        }

                        db.collection("MenuItems").whereArrayContains("categoryIds", categoryId).get()
                            .addOnSuccessListener { placeSnap ->
                                for (doc in placeSnap.documents) {
                                    if (doc.id in seen) continue
                                    collectItemMatch(doc, removedNorm, pending)
                                }
                                commitPending()
                            }
                            .addOnFailureListener(onError)
                    }
                    .addOnFailureListener(onError)
            }
            .addOnFailureListener(onError)
    }

    private fun collectItemMatch(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        removedNorm: String,
        pending: MutableList<Pair<DocumentReference, Map<String, Any>>>,
    ) {
        val updates = hashMapOf<String, Any>()
        val pl = doc.getString("printerLabel")?.trim().orEmpty()
        if (pl.isNotEmpty() && PrinterLabelKey.normalize(pl) == removedNorm) {
            updates["printerLabel"] = FieldValue.delete()
        }

        @Suppress("UNCHECKED_CAST")
        val rawLabels = (doc.get("labels") as? List<*>)
            ?.mapNotNull { it as? String }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        if (rawLabels.isNotEmpty()) {
            val filtered = rawLabels.filter { PrinterLabelKey.normalize(it) != removedNorm }
            when {
                filtered.isEmpty() -> updates["labels"] = FieldValue.delete()
                filtered.size != rawLabels.size -> updates["labels"] = filtered
                else -> { }
            }
        }

        if (updates.isNotEmpty()) {
            pending.add(doc.reference to updates)
        }
    }

    private fun commitInChunks(
        db: FirebaseFirestore,
        pending: List<Pair<DocumentReference, Map<String, Any>>>,
        start: Int,
        onDone: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (start >= pending.size) {
            onDone()
            return
        }
        val end = minOf(start + 400, pending.size)
        val batch = db.batch()
        for (i in start until end) {
            val (ref, map) = pending[i]
            batch.update(ref, map)
        }
        batch.commit()
            .addOnSuccessListener {
                commitInChunks(db, pending, end, onDone, onError)
            }
            .addOnFailureListener(onError)
    }
}
