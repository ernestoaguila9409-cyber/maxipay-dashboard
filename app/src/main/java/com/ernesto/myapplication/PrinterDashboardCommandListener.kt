package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Handles remote printer actions from the MaxiPay web dashboard (e.g. test print).
 * Dashboard writes to `Printers/{printerDocId}/commands` with `type: "testConnection"`.
 */
object PrinterDashboardCommandListener {

    private const val TAG = "PrinterDashCmd"
    private const val MAX_COMMAND_AGE_MS = 180_000L
    private const val FUTURE_SKEW_MS = 120_000L

    private var registration: ListenerRegistration? = null
    private val handledPaths = LinkedHashSet<String>()
    private const val HANDLED_CAP = 200

    fun start(context: Context) {
        if (registration != null) return
        val app = context.applicationContext
        val db = FirebaseFirestore.getInstance()
        registration = db.collectionGroup("commands")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.w(TAG, "commands listener: ${e.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                for (change in snap.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    handleCommandDoc(app, change.document)
                }
            }
        Log.d(TAG, "Started collectionGroup(commands) listener")
    }

    fun stop() {
        registration?.remove()
        registration = null
        handledPaths.clear()
        Log.d(TAG, "Stopped commands listener")
    }

    private fun handleCommandDoc(context: Context, cmdDoc: DocumentSnapshot) {
        val path = cmdDoc.reference.path
        if (path in handledPaths) return

        val cmdType = cmdDoc.getString("type") ?: return
        if (cmdType != "testConnection") return

        val requestedAt = cmdDoc.getTimestamp("requestedAt") ?: run {
            Log.d(TAG, "Skip command without requestedAt: $path")
            return
        }
        val ageMs = System.currentTimeMillis() - requestedAt.toDate().time
        if (ageMs > MAX_COMMAND_AGE_MS) {
            Log.d(TAG, "Skip stale command (${ageMs}ms old): $path")
            return
        }
        if (ageMs < -FUTURE_SKEW_MS) {
            Log.d(TAG, "Skip future-dated command: $path")
            return
        }

        val printerRef = cmdDoc.reference.parent.parent
        if (printerRef == null) {
            Log.w(TAG, "No parent printer for $path")
            return
        }

        printerRef.get()
            .addOnSuccessListener { printerSnap ->
                if (!printerSnap.exists()) {
                    cmdDoc.reference.delete()
                    return@addOnSuccessListener
                }
                val ip = printerSnap.getString("ipAddress")?.trim().orEmpty()
                if (ip.isEmpty()) {
                    cmdDoc.reference.delete()
                    return@addOnSuccessListener
                }

                val typeStr = printerSnap.getString("type").orEmpty()
                val firestoreIsKitchen = typeStr.equals(PrinterDeviceType.KITCHEN.firestoreValue, ignoreCase = true)

                val hasReceipt = SelectedPrinterPrefs.getAll(context, PrinterDeviceType.RECEIPT)
                    .any { it.ipAddress.trim() == ip }
                val hasKitchen = SelectedPrinterPrefs.getAll(context, PrinterDeviceType.KITCHEN)
                    .any { it.ipAddress.trim() == ip }

                if (!hasReceipt && !hasKitchen) {
                    Log.d(TAG, "This terminal does not have printer $ip configured; ignoring")
                    return@addOnSuccessListener
                }

                val kitchenPrinter = when {
                    hasKitchen && !hasReceipt -> true
                    hasReceipt && !hasKitchen -> false
                    else -> firestoreIsKitchen
                }

                rememberHandled(path)
                if (kitchenPrinter) {
                    val style = KitchenTicketStyle.fromPrinterDocument(printerSnap)
                    val parsedCmd = PrinterCommandSet.fromFirestore(printerSnap.getString("commandSet"))
                    val inferredCmd = PrinterCommandSet.infer(
                        printerSnap.getString("manufacturer"),
                        printerSnap.getString("model"),
                    )
                    val cmdSet = if (inferredCmd == PrinterCommandSet.STAR_DOT_MATRIX
                        && parsedCmd == PrinterCommandSet.ESCPOS) inferredCmd else parsedCmd
                    val title = printerSnap.getString("name")?.trim().orEmpty()
                        .ifBlank { "Kitchen" }
                    val segments = KitchenTicketBuilder.buildSampleDemoTicketSegments(
                        printerTitle = title,
                        style = style,
                        demoRoutingLabel = firstPrinterRoutingLabel(printerSnap),
                    )
                    EscPosPrinter.printKitchenChitToLan(
                        context,
                        ipAddress = ip,
                        port = printerPort(printerSnap),
                        segments = segments,
                        commandSet = cmdSet,
                        showSuccessToast = true,
                    )
                } else {
                    EscPosPrinter.printLanTestPrint(
                        context,
                        ipAddress = ip,
                        port = printerPort(printerSnap),
                        kitchenPrinter = false,
                    )
                }
                cmdDoc.reference.delete()
                    .addOnFailureListener { err ->
                        Log.w(TAG, "Could not delete command doc: ${err.message}")
                    }
            }
            .addOnFailureListener { err ->
                Log.w(TAG, "Failed to load printer for command: ${err.message}")
            }
    }

    private fun rememberHandled(path: String) {
        handledPaths.add(path)
        while (handledPaths.size > HANDLED_CAP) {
            handledPaths.remove(handledPaths.first())
        }
    }

    private fun printerPort(doc: DocumentSnapshot): Int {
        return when (val p = doc.get("port")) {
            is Long -> p.toInt().coerceIn(1, 65_535)
            is Int -> p.coerceIn(1, 65_535)
            is Double -> p.toInt().coerceIn(1, 65_535)
            else -> 9100
        }
    }

    private fun firstPrinterRoutingLabel(doc: DocumentSnapshot): String? {
        val raw = doc.get("labels") ?: return null
        if (raw !is List<*>) return null
        for (x in raw) {
            val s = x?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return s
        }
        return null
    }
}
