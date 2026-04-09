package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot

/**
 * Two-way cloud sync for the [PrinterFirestoreSync.COLLECTION] collection:
 *
 * - **Additions**: printers added on the web dashboard are saved into local [SelectedPrinterPrefs].
 * - **Updates**: name / type changes made on the dashboard are applied locally.
 * - **Deletions**: printers removed from the dashboard are removed locally.
 */
object PrinterFirestoreDeletionSync {

    private const val TAG = "PrinterCloudSync"

    private var registration: ListenerRegistration? = null
    private var lastCloudIps: Set<String>? = null

    private data class CloudPrinter(
        val ip: String,
        val name: String,
        val type: PrinterDeviceType,
        val model: String,
        val labels: List<String>,
    )

    fun start(context: Context) {
        if (registration != null) return
        val app = context.applicationContext
        val db = FirebaseFirestore.getInstance()
        registration = db.collection(PrinterFirestoreSync.COLLECTION)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "Printers listener: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener

                val cloudPrinters = parseCloudPrinters(snap)
                val currentIps = cloudPrinters.map { it.ip }.toSet()

                syncAdditionsAndUpdates(app, cloudPrinters)

                val prev = lastCloudIps
                lastCloudIps = currentIps

                if (prev != null) {
                    val removed = prev - currentIps
                    for (ip in removed) {
                        SelectedPrinterPrefs.removePrinterByIp(app, ip)
                        Log.d(TAG, "Removed printer (deleted in cloud): $ip")
                    }
                }
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
        lastCloudIps = null
    }

    private fun parseCloudPrinters(snap: QuerySnapshot): List<CloudPrinter> {
        val result = mutableListOf<CloudPrinter>()
        for (doc in snap.documents) {
            val ip = doc.getString("ipAddress")?.trim()?.takeIf { it.isNotEmpty() }
                ?: doc.getString("ip")?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue

            val name = doc.getString("name")?.trim().orEmpty()
            val typeStr = doc.getString("type")?.trim().orEmpty()
            val type = when {
                typeStr.equals(PrinterDeviceType.KITCHEN.firestoreValue, ignoreCase = true) ->
                    PrinterDeviceType.KITCHEN
                else -> PrinterDeviceType.RECEIPT
            }
            val model = doc.getString("model")?.trim().orEmpty()

            val labelsRaw = try {
                @Suppress("UNCHECKED_CAST")
                (doc.get("labels") as? List<String>).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            result.add(CloudPrinter(ip, name, type, model, labelsRaw))
        }
        return result
    }

    /**
     * For each cloud printer, add it locally if missing or update if name/type/labels changed.
     */
    private fun syncAdditionsAndUpdates(context: Context, cloudPrinters: List<CloudPrinter>) {
        val localReceipt = SelectedPrinterPrefs.getAll(context, PrinterDeviceType.RECEIPT)
        val localKitchen = SelectedPrinterPrefs.getAll(context, PrinterDeviceType.KITCHEN)
        val localByIp = mutableMapOf<String, Pair<PrinterDeviceType, SelectedPrinterDisplay>>()
        for (p in localReceipt) localByIp[p.ipAddress.trim()] = PrinterDeviceType.RECEIPT to p
        for (p in localKitchen) localByIp[p.ipAddress.trim()] = PrinterDeviceType.KITCHEN to p

        var changed = false

        for (cloud in cloudPrinters) {
            if (cloud.name.isEmpty()) continue

            val local = localByIp[cloud.ip]

            if (local == null) {
                SelectedPrinterPrefs.add(
                    context, cloud.type,
                    name = cloud.name,
                    ipAddress = cloud.ip,
                    modelLine = cloud.model,
                    labels = cloud.labels,
                )
                Log.d(TAG, "Added printer from cloud: ${cloud.name} (${cloud.ip}) as ${cloud.type}")
                changed = true
                continue
            }

            val (localType, localDisplay) = local

            if (localType != cloud.type) {
                SelectedPrinterPrefs.removePrinterByIp(context, cloud.ip, broadcastChange = false)
                SelectedPrinterPrefs.add(
                    context, cloud.type,
                    name = cloud.name,
                    ipAddress = cloud.ip,
                    modelLine = cloud.model.ifEmpty { localDisplay.modelLine },
                    labels = cloud.labels,
                )
                Log.d(TAG, "Moved printer ${cloud.ip} from $localType to ${cloud.type}")
                changed = true
                continue
            }

            val nameChanged = localDisplay.name != cloud.name
            val labelsChanged = cloud.labels.isNotEmpty()
                && normalizedLabels(cloud.labels) != normalizedLabels(localDisplay.labels)

            if (nameChanged || labelsChanged) {
                SelectedPrinterPrefs.add(
                    context, cloud.type,
                    name = cloud.name,
                    ipAddress = cloud.ip,
                    modelLine = cloud.model.ifEmpty { localDisplay.modelLine },
                    labels = if (cloud.labels.isNotEmpty()) cloud.labels else localDisplay.labels,
                )
                if (nameChanged) Log.d(TAG, "Updated printer name from cloud: ${cloud.ip} → ${cloud.name}")
                if (labelsChanged) Log.d(TAG, "Updated printer labels from cloud: ${cloud.ip} → ${cloud.labels}")
                changed = true
            }
        }

        if (changed) {
            SelectedPrinterPrefs.sendPrintersChangedBroadcast(context)
        }
    }

    private fun normalizedLabels(labels: List<String>): Set<String> =
        labels.mapNotNull { PrinterLabelKey.normalize(it).takeIf { k -> k.isNotEmpty() } }.toSet()
}
