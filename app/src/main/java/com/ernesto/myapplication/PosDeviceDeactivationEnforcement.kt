package com.ernesto.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/**
 * Fetches [PosDevices] from the **server** (bypasses stale local cache) and opens the forced
 * activation screen when the dashboard set [PosDeviceDeactivationWatch.FIELD_DEACTIVATED].
 */
object PosDeviceDeactivationEnforcement {

    private const val TAG = "PosDeactivationEnforce"
    private const val COLLECTION = "PosDevices"
    private const val MIN_INTERVAL_MS = 2_500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastServerFetchElapsed = 0L

    fun checkServerAndLockIfNeeded(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastServerFetchElapsed < MIN_INTERVAL_MS) return
        lastServerFetchElapsed = now

        val appCtx = context.applicationContext
        PosDeviceIdentity.resolveInstallationDocId(context) { docId ->
            FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(docId)
                .get(Source.SERVER)
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) return@addOnSuccessListener
                    val deactivated = snap.getBoolean(PosDeviceDeactivationWatch.FIELD_DEACTIVATED)
                    if (deactivated != true) return@addOnSuccessListener
                    mainHandler.post {
                        DeviceActivationActivity.launchForceLock(appCtx)
                    }
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
