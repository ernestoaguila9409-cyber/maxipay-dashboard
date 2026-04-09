package com.ernesto.myapplication

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Keeps [COLLECTION] in sync with the MaxiPay web dashboard: metadata from local prefs and
 * reachability (status + lastSeen) from LAN probes.
 */
object PrinterFirestoreSync {

    const val COLLECTION = "Printers"
    const val DEFAULT_PORT = 9100

    private const val TAG = "PrinterFirestoreSync"

    /** One stable document per LAN IP so POS upserts do not create duplicate rows. */
    const val UNKNOWN_LAN_DOC_ID = "lan_unknown"

    fun documentIdForLanIp(ip: String): String {
        val safe = ip.trim().replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_')
        return if (safe.isNotEmpty()) "lan_$safe" else UNKNOWN_LAN_DOC_ID
    }

    private fun isValidLanDocId(docId: String): Boolean =
        docId.isNotEmpty() && docId != UNKNOWN_LAN_DOC_ID

    /**
     * Deletes the canonical `lan_*` doc and any legacy rows with the same [ipAddress] (dashboard UUID ids).
     */
    fun deleteAllDocumentsForIp(
        db: FirebaseFirestore,
        ip: String,
        onComplete: (Exception?) -> Unit,
    ) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) {
            onComplete(null)
            return
        }
        val col = db.collection(COLLECTION)
        val primaryId = documentIdForLanIp(trimmed)
        if (!isValidLanDocId(primaryId)) {
            onComplete(null)
            return
        }
        col.document(primaryId).delete()
        col.whereEqualTo("ipAddress", trimmed).get()
            .addOnSuccessListener { snap ->
                for (d in snap.documents) {
                    d.reference.delete()
                }
                onComplete(null)
            }
            .addOnFailureListener { onComplete(it) }
    }

    /**
     * Name, IP, port, type, model line — merged so reachability fields are not cleared.
     *
     * @param includeLabels When true the "labels" field is written; when false (default)
     *   existing Firestore labels are left untouched.  Pass true only from code paths
     *   that explicitly change labels (e.g. printer detail screen).
     */
    fun mergeRegistration(
        db: FirebaseFirestore,
        type: PrinterDeviceType,
        display: SelectedPrinterDisplay,
        model: String? = null,
        manufacturer: String? = null,
        includeLabels: Boolean = false,
        includeCommandSet: Boolean = false,
    ) {
        val ip = display.ipAddress.trim()
        if (ip.isEmpty()) return
        val docId = documentIdForLanIp(ip)
        if (!isValidLanDocId(docId)) return
        val data = buildRegistrationData(type, display, ip, model, manufacturer, includeLabels, includeCommandSet)

        val col = db.collection(COLLECTION)
        col.whereEqualTo("ipAddress", ip).get()
            .addOnSuccessListener { snap ->
                val dupes = snap.documents.filter { it.id != docId }
                if (dupes.isEmpty()) {
                    col.document(docId).set(data, SetOptions.merge())
                        .addOnFailureListener { e ->
                            Log.w(TAG, "mergeRegistration failed for $docId: ${e.message}")
                        }
                } else {
                    val batch = db.batch()
                    batch.set(col.document(docId), data, SetOptions.merge())
                    for (d in dupes) {
                        batch.delete(d.reference)
                        Log.d(TAG, "Batched delete of duplicate doc ${d.id} for IP $ip")
                    }
                    batch.commit()
                        .addOnFailureListener { e ->
                            Log.w(TAG, "mergeRegistration batch failed for $docId: ${e.message}")
                        }
                }
            }
            .addOnFailureListener {
                col.document(docId).set(data, SetOptions.merge())
                    .addOnFailureListener { e ->
                        Log.w(TAG, "mergeRegistration fallback failed for $docId: ${e.message}")
                    }
            }
    }

    private fun buildRegistrationData(
        type: PrinterDeviceType,
        display: SelectedPrinterDisplay,
        ip: String,
        model: String?,
        manufacturer: String?,
        includeLabels: Boolean,
        includeCommandSet: Boolean,
    ): Map<String, Any> {
        val data = mutableMapOf<String, Any>(
            "name" to display.name.trim(),
            "ipAddress" to ip,
            "port" to DEFAULT_PORT,
            "type" to type.firestoreValue,
            "isActive" to true,
        )
        if (includeLabels) {
            data["labels"] = display.labels
        }
        if (display.modelLine.isNotBlank()) {
            data["model"] = display.modelLine.trim()
        }
        model?.takeIf { it.isNotBlank() }?.let { data["model"] = it.trim() }
        manufacturer?.takeIf { it.isNotBlank() }?.let { data["manufacturer"] = it.trim() }
        if (includeCommandSet) {
            val effectiveMfr = manufacturer?.trim()?.takeIf { it.isNotBlank() }
                ?: display.modelLine.trim().takeIf { it.isNotBlank() }
            val effectiveModel = model?.trim()?.takeIf { it.isNotBlank() }
                ?: display.modelLine.trim().takeIf { it.isNotBlank() }
            data["commandSet"] = PrinterCommandSet.infer(effectiveMfr, effectiveModel).firestoreValue
        }
        return data
    }

    fun mergeRegistrationFromDetected(
        db: FirebaseFirestore,
        type: PrinterDeviceType,
        name: String,
        printer: DetectedPrinter,
    ) {
        val display = SelectedPrinterDisplay(
            name = name,
            ipAddress = printer.ipAddress,
            modelLine = printer.displayLabel,
            labels = emptyList(),
        )
        mergeRegistration(
            db,
            type,
            display,
            model = printer.model,
            manufacturer = printer.manufacturer,
            includeCommandSet = true,
        )
    }

    /** POS-reported reachability for the dashboard (status + lastSeen when online). */
    fun updateReachability(db: FirebaseFirestore, ip: String, online: Boolean, nowMs: Long) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return
        val docId = documentIdForLanIp(trimmed)
        if (!isValidLanDocId(docId)) return
        val data = mutableMapOf<String, Any>(
            "status" to if (online) "ONLINE" else "OFFLINE",
        )
        if (online) {
            data["lastSeen"] = Timestamp(
                nowMs / 1000,
                ((nowMs % 1000) * 1_000_000).toInt(),
            )
        }
        db.collection(COLLECTION).document(docId).set(data, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.w(TAG, "updateReachability failed for $docId: ${e.message}")
            }
    }
}
