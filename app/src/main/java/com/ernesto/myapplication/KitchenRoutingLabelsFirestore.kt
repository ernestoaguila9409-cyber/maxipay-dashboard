package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

/**
 * Canonical list of kitchen routing label names for menu items and POS pickers.
 * Document: [COLLECTION]/[DOCUMENT_ID] field [FIELD_LABELS]: List<String>
 */
object KitchenRoutingLabelsFirestore {
    const val COLLECTION = "Settings"
    const val DOCUMENT_ID = "kitchenRoutingLabels"
    const val FIELD_LABELS = "labels"

    fun documentRef(db: FirebaseFirestore) =
        db.collection(COLLECTION).document(DOCUMENT_ID)

    /**
     * Merges trimmed labels into the shared list (deduped by [PrinterLabelKey.normalize]).
     */
    fun mergeLabelsIntoFirestore(db: FirebaseFirestore, labels: Collection<String>) {
        val trimmed = labels.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return
        val ref = documentRef(db)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            @Suppress("UNCHECKED_CAST")
            val existing = (snap.get(FIELD_LABELS) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val merged = dedupeMerged(existing + trimmed)
            tx.set(ref, hashMapOf(FIELD_LABELS to merged), SetOptions.merge())
        }
    }

    fun fetchCloudLabels(
        db: FirebaseFirestore,
        onSuccess: (List<String>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        documentRef(db).get()
            .addOnSuccessListener { snap ->
                @Suppress("UNCHECKED_CAST")
                val list = (snap.get(FIELD_LABELS) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                onSuccess(list)
            }
            .addOnFailureListener(onFailure)
    }

    /**
     * Labels the user may assign to a menu item:
     * - Every routing label from saved kitchen **and** receipt LAN printers.
     * - Plus any [currentlyAssigned] values that are **not** on a printer (legacy / typos like "Food")
     *   so they stay visible until the user clears them — **Settings/kitchenRoutingLabels is not used** here,
     *   so orphan cloud-only names no longer appear as new options.
     */
    fun labelsForItemAssignmentPicker(
        context: android.content.Context,
        currentlyAssigned: List<String>,
    ): List<String> {
        val local = SelectedPrinterPrefs.allRoutingLabelsFromSavedPrinters(context)
        val localNorm = local.map { PrinterLabelKey.normalize(it) }.toSet()
        val orphans = currentlyAssigned
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { assigned -> PrinterLabelKey.normalize(assigned) !in localNorm }
        return dedupeMerged(local + orphans).sortedBy { it.lowercase(Locale.ROOT) }
    }

    private fun dedupeMerged(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (s in raw) {
            val t = s.trim()
            if (t.isEmpty()) continue
            val k = PrinterLabelKey.normalize(t)
            if (k in seen) continue
            seen.add(k)
            out.add(t)
        }
        return out
    }
}
