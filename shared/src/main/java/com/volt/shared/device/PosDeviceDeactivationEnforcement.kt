package com.volt.shared.device

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.volt.shared.MerchantFirestore

object PosDeviceDeactivationEnforcement {

    private const val TAG = "PosDeactivationEnforce"
    private const val COLLECTION = "PosDevices"
    private const val MIN_INTERVAL_MS = 2_500L

    private var lastServerFetchElapsed = 0L

    fun checkServerAndLockIfNeeded(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val mid = PosDeviceIdentity.getMerchantId(context).trim()
        if (mid.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastServerFetchElapsed < MIN_INTERVAL_MS) return
        lastServerFetchElapsed = now

        val appCtx = context.applicationContext
        if (!MerchantFirestore.isInitialized) {
            MerchantFirestore.init(mid)
        }

        PosDeviceIdentity.resolveInstallationDocId(context) { docId ->
            MerchantFirestore.doc(COLLECTION, docId)
                .get(Source.SERVER)
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) return@addOnSuccessListener
                    if (snap.getBoolean(PosDeviceDeactivationWatch.FIELD_DEACTIVATED) != true) {
                        return@addOnSuccessListener
                    }
                    skipNextThrottleWindow()
                    PosDeviceDeactivationWatch.lockIfDeactivated(appCtx as Application)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "SERVER check failed: ${e.message}")
                }
        }
    }

    fun skipNextThrottleWindow() {
        lastServerFetchElapsed = 0L
    }
}
