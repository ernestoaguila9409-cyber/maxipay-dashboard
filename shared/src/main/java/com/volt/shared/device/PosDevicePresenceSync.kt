package com.volt.shared.device

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.volt.shared.MerchantFirestore

object PosDevicePresenceSync {

    private const val TAG = "PosDevicePresence"
    private const val COLLECTION = "PosDevices"
    private const val FIELD_LAST_SEEN = "lastSeen"
    private const val HEARTBEAT_INTERVAL_MS = 45_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var app: Application? = null
    private var resolvedDocId: String? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val a = app ?: return
            pushHeartbeat(a)
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start(application: Application) {
        if (app != null) return
        app = application

        PosDeviceIdentity.resolveInstallationDocId(application) { id ->
            resolvedDocId = id
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                scheduleHeartbeat()
            }
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scheduleHeartbeat()
            }

            override fun onStop(owner: LifecycleOwner) {
                cancelHeartbeat()
            }
        }
        lifecycleObserver = observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    /** Installation doc id under [PosDevices], when [start] has resolved it. */
    fun currentDeviceId(): String? = resolvedDocId?.trim()?.takeIf { it.isNotEmpty() }

    fun stop() {
        cancelHeartbeat()
        lifecycleObserver?.let {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        lifecycleObserver = null
        resolvedDocId = null
        app = null
    }

    private fun scheduleHeartbeat() {
        cancelHeartbeat()
        val a = app ?: return
        pushHeartbeat(a)
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun cancelHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun pushHeartbeat(application: Application) {
        if (!MerchantFirestore.isInitialized) return
        val docId = resolvedDocId ?: return
        val pm = application.packageManager
        val pkg = application.packageName
        val appVer = try {
            pm.getPackageInfo(pkg, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val verCode = try {
            val pi = pm.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }

        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val data = hashMapOf<String, Any>(
            "platform" to "android",
            "deviceModel" to deviceLabel,
            "manufacturer" to Build.MANUFACTURER.orEmpty(),
            "model" to Build.MODEL.orEmpty(),
            "osVersion" to "Android ${Build.VERSION.RELEASE}",
            "sdkInt" to Build.VERSION.SDK_INT,
            "appVersion" to appVer,
            "appVersionCode" to verCode,
            FIELD_LAST_SEEN to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        MerchantFirestore.doc(COLLECTION, docId)
            .set(data, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.w(TAG, "Heartbeat failed: ${e.message}")
            }
    }
}
