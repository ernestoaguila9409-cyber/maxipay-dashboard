package com.ernesto.myapplication

import android.content.Context
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * After LAN printers are removed or their routing labels change locally, keeps
 * [MenuItems] `labels` / `printerLabel` and [Categories] / `subcategories` `kitchenLabel`
 * in sync with labels still defined on saved kitchen and receipt printers.
 */
object MenuItemRoutingLabelCleanup {

    /** Runs [syncMenuItemLabelsToSavedPrinters] and [syncCategorySubcategoryKitchenLabelsToSavedPrinters]. */
    fun syncAllAssignedRoutingLabelsToSavedPrinters(
        context: Context,
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    ) {
        syncMenuItemLabelsToSavedPrinters(context, db)
        syncCategorySubcategoryKitchenLabelsToSavedPrinters(context, db)
    }

    fun syncMenuItemLabelsToSavedPrinters(
        context: Context,
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    ) {
        val app = context.applicationContext
        val validNorms = SelectedPrinterPrefs.allRoutingLabelsFromSavedPrinters(app)
            .map { PrinterLabelKey.normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        MerchantFirestore.col("MenuItems").get()
            .addOnSuccessListener { snap ->
                val pending = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

                for (doc in snap.documents) {
                    @Suppress("UNCHECKED_CAST")
                    val rawLabels = (doc.get("labels") as? List<*>)?.mapNotNull { it as? String }
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()
                    val pl = doc.getString("printerLabel")?.trim().orEmpty()

                    val filtered = mutableListOf<String>()
                    val seenNorm = mutableSetOf<String>()
                    for (l in rawLabels) {
                        val n = PrinterLabelKey.normalize(l)
                        if (n.isEmpty() || n !in validNorms) continue
                        if (!seenNorm.add(n)) continue
                        filtered.add(l)
                    }

                    val plNorm = PrinterLabelKey.normalize(pl)
                    val plValid = pl.isNotEmpty() && plNorm in validNorms

                    val newLabels: List<String> = when {
                        filtered.isNotEmpty() -> filtered
                        plValid -> listOf(pl)
                        else -> emptyList()
                    }

                    val newPrinterLabel = newLabels.firstOrNull().orEmpty()

                    val updates = mutableMapOf<String, Any>()
                    val rawNorms = rawLabels.map { PrinterLabelKey.normalize(it) }.filter { it.isNotEmpty() }.toSet()
                    val newNorms = newLabels.map { PrinterLabelKey.normalize(it) }.filter { it.isNotEmpty() }.toSet()
                    if (rawNorms != newNorms) {
                        if (newLabels.isEmpty()) {
                            updates["labels"] = FieldValue.delete()
                        } else {
                            updates["labels"] = newLabels
                        }
                    }

                    val currentPl = doc.getString("printerLabel")?.trim().orEmpty()
                    if (currentPl != newPrinterLabel) {
                        if (newPrinterLabel.isEmpty()) {
                            updates["printerLabel"] = FieldValue.delete()
                        } else {
                            updates["printerLabel"] = newPrinterLabel
                        }
                    }

                    if (updates.isNotEmpty()) {
                        pending.add(doc.reference to updates)
                    }
                }

                if (pending.isEmpty()) return@addOnSuccessListener
                commitInChunks(db, pending, 0) {}
            }
            .addOnFailureListener { /* ignore; best-effort cleanup */ }
    }

    /**
     * Clears `kitchenLabel` on categories and subcategories when it no longer exists on any
     * saved printer (same rules as the item picker). Prevents "ghost" labels like Drinks
     * after that label is removed from printer configuration.
     */
    fun syncCategorySubcategoryKitchenLabelsToSavedPrinters(
        context: Context,
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        onDone: () -> Unit = {},
    ) {
        val app = context.applicationContext
        val validNorms = SelectedPrinterPrefs.allRoutingLabelsFromSavedPrinters(app)
            .map { PrinterLabelKey.normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        MerchantFirestore.col("Categories").get()
            .addOnSuccessListener { catSnap ->
                val pending = mutableListOf<Pair<DocumentReference, Map<String, Any>>>()
                for (doc in catSnap.documents) {
                    val kl = doc.getString("kitchenLabel")?.trim().orEmpty()
                    if (kl.isEmpty()) continue
                    if (PrinterLabelKey.normalize(kl) !in validNorms) {
                        pending.add(doc.reference to mapOf("kitchenLabel" to FieldValue.delete()))
                    }
                }
                MerchantFirestore.col("subcategories").get()
                    .addOnSuccessListener { subSnap ->
                        for (doc in subSnap.documents) {
                            val kl = doc.getString("kitchenLabel")?.trim().orEmpty()
                            if (kl.isEmpty()) continue
                            if (PrinterLabelKey.normalize(kl) !in validNorms) {
                                pending.add(doc.reference to mapOf("kitchenLabel" to FieldValue.delete()))
                            }
                        }
                        if (pending.isEmpty()) {
                            onDone()
                            return@addOnSuccessListener
                        }
                        commitInChunks(db, pending, 0, onDone)
                    }
                    .addOnFailureListener { onDone() }
            }
            .addOnFailureListener { onDone() }
    }

    private fun commitInChunks(
        db: FirebaseFirestore,
        pending: List<Pair<DocumentReference, Map<String, Any>>>,
        start: Int,
        onComplete: () -> Unit = {},
    ) {
        if (start >= pending.size) {
            onComplete()
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
                commitInChunks(db, pending, end, onComplete)
            }
            .addOnFailureListener { onComplete() }
    }
}
