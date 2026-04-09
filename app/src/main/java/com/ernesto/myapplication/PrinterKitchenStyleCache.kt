package com.ernesto.myapplication

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
     * Kitchen LAN chits only. If Firestore has not populated [commandSetByIp] for this IP yet,
     * still use [STAR_DOT_MATRIX] when the saved [modelLine] matches a Star impact printer (SP700).
     */
    fun commandSetForKitchenLan(ip: String, savedModelLine: String?): PrinterCommandSet {
        val k = ip.trim()
        if (k.isEmpty()) return PrinterCommandSet.ESCPOS
        val cached = commandSetByIp[k]
        if (cached == PrinterCommandSet.STAR_DOT_MATRIX) return PrinterCommandSet.STAR_DOT_MATRIX
        if (PrinterCommandSet.infer(null, savedModelLine) == PrinterCommandSet.STAR_DOT_MATRIX) {
            return PrinterCommandSet.STAR_DOT_MATRIX
        }
        return cached ?: PrinterCommandSet.ESCPOS
    }

    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
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
                        val storedCmd = doc.getString("commandSet")
                        val docModel = doc.getString("model")
                        val docMfr = doc.getString("manufacturer")
                        val parsed = PrinterCommandSet.fromFirestore(storedCmd)
                        val inferred = PrinterCommandSet.infer(docMfr, docModel)
                        val effective = if (inferred == PrinterCommandSet.STAR_DOT_MATRIX
                            && parsed == PrinterCommandSet.ESCPOS) inferred else parsed
                        nextCmd[ip] = effective
                        if (effective != parsed) {
                            Log.w(TAG, "Auto-repairing commandSet for $ip: " +
                                    "$storedCmd → ${effective.firestoreValue} " +
                                    "(model=$docModel, mfr=$docMfr)")
                            doc.reference.update("commandSet", effective.firestoreValue)
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
