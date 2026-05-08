package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors `Printers` kitchen ticket styles and command sets keyed by LAN IP so
 * [KitchenPrintHelper] can apply dashboard typography without a per-print Firestore read.
 */
object PrinterKitchenStyleCache {

    private const val TAG = "PrinterKitchenStyle"

    private val styleByIp = ConcurrentHashMap<String, KitchenTicketStyle>()
    private val commandSetByIp = ConcurrentHashMap<String, PrinterCommandSet>()
    private var registration: ListenerRegistration? = null

    fun styleForIp(ip: String): KitchenTicketStyle {
        val k = ip.trim()
        if (k.isEmpty()) return KitchenTicketStyle.DEFAULT
        return styleByIp[k] ?: KitchenTicketStyle.DEFAULT
    }

    fun commandSetForIp(ip: String): PrinterCommandSet {
        val k = ip.trim()
        if (k.isEmpty()) return PrinterCommandSet.ESCPOS
        return commandSetByIp[k] ?: PrinterCommandSet.ESCPOS
    }

    /**
     * Kitchen LAN chits only. Resolution order:
     * 1. Firestore-cached value (real-time listener)
     * 2. Model-line inference (Star SP7xx detection)
     * 3. Locally persisted commandSet from [SelectedPrinterDisplay] (survives app restarts
     *    and covers the window before the Firestore listener fires)
     * 4. Default [ESCPOS]
     */
    fun commandSetForKitchenLan(ip: String, printer: SelectedPrinterDisplay?): PrinterCommandSet {
        val k = ip.trim()
        if (InternalKitchenPrinter.isInternalAddress(k)) return PrinterCommandSet.ESCPOS
        if (k.isEmpty()) return PrinterCommandSet.ESCPOS
        val cached = commandSetByIp[k]
        if (cached != null) return cached
        if (PrinterCommandSet.infer(null, printer?.modelLine) == PrinterCommandSet.STAR_DOT_MATRIX) {
            return PrinterCommandSet.STAR_DOT_MATRIX
        }
        val local = printer?.commandSet?.trim()?.uppercase().orEmpty()
        if (local.isNotEmpty()) {
            return PrinterCommandSet.fromFirestore(local)
        }
        return PrinterCommandSet.ESCPOS
    }

    @Deprecated("Use commandSetForKitchenLan(ip, printer) instead")
    fun commandSetForKitchenLan(ip: String, savedModelLine: String?): PrinterCommandSet {
        val k = ip.trim()
        if (k.isEmpty()) return PrinterCommandSet.ESCPOS
        val cached = commandSetByIp[k]
        if (cached != null) return cached
        if (PrinterCommandSet.infer(null, savedModelLine) == PrinterCommandSet.STAR_DOT_MATRIX) {
            return PrinterCommandSet.STAR_DOT_MATRIX
        }
        return PrinterCommandSet.ESCPOS
    }

    private var appContext: Context? = null

    fun start(context: Context, db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        appContext = context.applicationContext
        startInternal(db)
    }

    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        startInternal(db)
    }

    private fun startInternal(db: FirebaseFirestore) {
        synchronized(this) {
            if (registration != null) return
            registration = db.collection(PrinterFirestoreSync.COLLECTION)
                .addSnapshotListener { snap, e ->
                    if (e != null) {
                        Log.w(TAG, "listen failed: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snap == null) return@addSnapshotListener
                    val nextStyle = HashMap<String, KitchenTicketStyle>()
                    val nextCmd = HashMap<String, PrinterCommandSet>()
                    for (doc in snap.documents) {
                        val ip = doc.getString("ipAddress")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: continue
                        when {
                            doc.contains("kitchenTicketStyle") ->
                                nextStyle[ip] = KitchenTicketStyle.fromPrinterDocument(doc)
                            !nextStyle.containsKey(ip) ->
                                nextStyle[ip] = KitchenTicketStyle.DEFAULT
                        }
                        val storedCmd = doc.getString("commandSet")?.trim().orEmpty()
                        val docModel = doc.getString("model")
                        val docMfr = doc.getString("manufacturer")
                        val parsed = PrinterCommandSet.fromFirestore(storedCmd.ifEmpty { null })
                        val inferred = PrinterCommandSet.infer(docMfr, docModel)
                        val effective = when {
                            storedCmd.isEmpty() -> inferred
                            inferred == PrinterCommandSet.STAR_DOT_MATRIX
                                && parsed == PrinterCommandSet.ESCPOS -> inferred
                            else -> parsed
                        }
                        nextCmd[ip] = effective
                        if (effective.firestoreValue != storedCmd) {
                            Log.w(TAG, "Auto-repairing commandSet for $ip: " +
                                    "'$storedCmd' → ${effective.firestoreValue} " +
                                    "(model=$docModel, mfr=$docMfr)")
                            doc.reference.update("commandSet", effective.firestoreValue)
                        }
                        appContext?.let { ctx ->
                            SelectedPrinterPrefs.updateCommandSetForIp(ctx, ip, effective)
                        }
                    }
                    styleByIp.clear()
                    styleByIp.putAll(nextStyle)
                    commandSetByIp.clear()
                    commandSetByIp.putAll(nextCmd)
                }
        }
    }

    fun stop() {
        synchronized(this) {
            registration?.remove()
            registration = null
            styleByIp.clear()
            commandSetByIp.clear()
        }
    }
}
