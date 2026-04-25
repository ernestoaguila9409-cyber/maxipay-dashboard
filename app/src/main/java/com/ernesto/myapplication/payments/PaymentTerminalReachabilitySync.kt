package com.ernesto.myapplication.payments

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * While the POS is signed in, periodically probes the active SPIn terminal and
 * mirrors reachability to Firestore so the web dashboard can show online/offline.
 */
object PaymentTerminalReachabilitySync {

    private const val TAG = "PaymentTermReachSync"
    private const val PROBE_INTERVAL_MS = 25_000L
    private const val ONLINE_LAST_SEEN_MIN_INTERVAL_MS = 45_000L

    private const val FIELD_POS_STATUS = "posConnectionStatus"
    private const val FIELD_POS_LAST_SEEN = "posLastSeen"
    private const val LEGACY_FIELD_STATUS = "status"
    private const val LEGACY_FIELD_LAST_SEEN = "lastSeen"

    private const val COLLECTION_PAYMENT = "payment_terminals"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var runnable: Runnable? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val db get() = FirebaseFirestore.getInstance()

    private var lastFirestoreWriteMs = 0L
    private var lastKnownOnlineWallMs: Long? = null
    private var wasOnline = false
    private var lastProbedConfigId: String? = null

    fun start(context: Context) {
        stop()
        appContext = context.applicationContext
        runnable = object : Runnable {
            override fun run() {
                val ctx = appContext ?: return
                tick(ctx)
                mainHandler.postDelayed(this, PROBE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(runnable!!, PROBE_INTERVAL_MS)
    }

    fun stop() {
        runnable?.let { mainHandler.removeCallbacks(it) }
        runnable = null
        appContext = null
        lastFirestoreWriteMs = 0L
        lastKnownOnlineWallMs = null
        wasOnline = false
        lastProbedConfigId = null
    }

    private fun tick(context: Context) {
        val cfg = PaymentTerminalRepository.getActiveConfig(context) ?: return
        if (!cfg.provider.equals(PaymentTerminalConfig.PROVIDER_SPIN, ignoreCase = true)) return

        if (cfg.id != lastProbedConfigId) {
            lastProbedConfigId = cfg.id
            wasOnline = false
            lastKnownOnlineWallMs = null
            lastFirestoreWriteMs = 0L
        }

        val collection = PaymentTerminalRepository.firestoreCollectionForConfigId(cfg.id) ?: return

        SpinTerminalConnectionProbe.enqueueCheck(httpClient, cfg) { connected ->
            val now = System.currentTimeMillis()
            if (connected) {
                lastKnownOnlineWallMs = now
                val becameOnline = !wasOnline
                wasOnline = true
                val shouldPersist =
                    becameOnline || (now - lastFirestoreWriteMs >= ONLINE_LAST_SEEN_MIN_INTERVAL_MS)
                if (shouldPersist) {
                    persistOnline(collection, cfg, nowMs = now)
                }
            } else if (wasOnline) {
                wasOnline = false
                persistOffline(collection, cfg, lastKnownOnlineWallMs)
            }
        }
    }

    private fun persistOnline(collection: String, cfg: PaymentTerminalConfig, nowMs: Long) {
        lastFirestoreWriteMs = System.currentTimeMillis()
        val ts = Timestamp(nowMs / 1000, ((nowMs % 1000) * 1_000_000).toInt())
        val posFields = mapOf(
            FIELD_POS_STATUS to "ONLINE",
            FIELD_POS_LAST_SEEN to ts,
        )
        val data: Map<String, Any> = if (collection == COLLECTION_PAYMENT) {
            posFields
        } else {
            mapOf(
                LEGACY_FIELD_STATUS to "ONLINE",
                LEGACY_FIELD_LAST_SEEN to ts,
            )
        }
        val docRef = db.collection(collection).document(cfg.id)
        docRef.update(data)
            .addOnSuccessListener {
                if (collection != COLLECTION_PAYMENT) {
                    mirrorPosFieldsToPaymentTerminalDashboard(cfg.id, cfg, posFields)
                }
            }
            .addOnFailureListener { Log.w(TAG, "Reachability update failed: ${it.message}") }
    }

    private fun persistOffline(collection: String, cfg: PaymentTerminalConfig, lastOnlineMs: Long?) {
        val posFields = hashMapOf<String, Any>(FIELD_POS_STATUS to "OFFLINE")
        if (lastOnlineMs != null) {
            posFields[FIELD_POS_LAST_SEEN] = Timestamp(
                lastOnlineMs / 1000,
                ((lastOnlineMs % 1000) * 1_000_000).toInt(),
            )
        }
        val data: Map<String, Any> = if (collection == COLLECTION_PAYMENT) {
            posFields
        } else {
            val m = hashMapOf<String, Any>(LEGACY_FIELD_STATUS to "OFFLINE")
            if (lastOnlineMs != null) {
                m[LEGACY_FIELD_LAST_SEEN] = Timestamp(
                    lastOnlineMs / 1000,
                    ((lastOnlineMs % 1000) * 1_000_000).toInt(),
                )
            }
            m
        }
        db.collection(collection).document(cfg.id).update(data)
            .addOnSuccessListener {
                if (collection != COLLECTION_PAYMENT) {
                    mirrorPosFieldsToPaymentTerminalDashboard(cfg.id, cfg, posFields)
                }
            }
            .addOnFailureListener { Log.w(TAG, "Offline reachability update failed: ${it.message}") }
    }

    /**
     * Dashboard reads `payment_terminals`; legacy-only POS writes `Terminals/{id}`.
     * Copy POS fields onto dashboard rows linked by [PaymentTerminalDoc.legacyTerminalId] or TPN.
     */
    private fun mirrorPosFieldsToPaymentTerminalDashboard(
        legacyDocId: String,
        cfg: PaymentTerminalConfig,
        posFields: Map<String, Any>,
    ) {
        db.collection(COLLECTION_PAYMENT)
            .whereEqualTo("legacyTerminalId", legacyDocId)
            .limit(10)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    for (d in snap.documents) {
                        d.reference.update(posFields)
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Mirror by legacyTerminalId failed: ${e.message}")
                            }
                    }
                    return@addOnSuccessListener
                }
                val tpn = cfg.credential(PaymentTerminalConfig.Companion.ConfigKey.TPN).trim()
                if (tpn.isEmpty()) return@addOnSuccessListener
                db.collection(COLLECTION_PAYMENT)
                    .whereEqualTo("config.tpn", tpn)
                    .limit(5)
                    .get()
                    .addOnSuccessListener { byTpn ->
                        if (byTpn.size() > 1) {
                            Log.w(
                                TAG,
                                "Skipping TPN mirror — multiple payment_terminals use TPN $tpn",
                            )
                            return@addOnSuccessListener
                        }
                        val target = byTpn.documents.firstOrNull() ?: return@addOnSuccessListener
                        target.reference.update(posFields)
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Mirror by config.tpn failed: ${e.message}")
                            }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Mirror TPN query failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Mirror legacyTerminalId query failed: ${e.message}")
            }
    }
}
