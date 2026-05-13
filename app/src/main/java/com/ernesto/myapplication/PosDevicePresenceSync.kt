package com.ernesto.myapplication

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
import java.util.concurrent.Executors

/**
 * Writes a periodic heartbeat to Firestore [COLLECTION] while the POS app is in the foreground,
 * so the web dashboard can list devices and show online/offline from [FIELD_LAST_SEEN].
 */
object PosDevicePresenceSync {

    private const val TAG = "PosDevicePresence"
    private const val COLLECTION = "PosDevices"
    private const val FIELD_LAST_SEEN = "lastSeen"
    private const val HEARTBEAT_INTERVAL_MS = 45_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PosDeviceHeartbeat")
    }
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

    fun stop() {
        cancelHeartbeat()
        lifecycleObserver?.let {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        lifecycleObserver = null
        resolvedDocId = null
        app = null
        heartbeatExecutor.shutdownNow()
        heartbeatExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "PosDeviceHeartbeat")
        }
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
        val docId = resolvedDocId ?: return
        val executor = heartbeatExecutor
        executor.execute {
            if (app == null || resolvedDocId != docId) return@execute
            try {
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
                val deviceSerial = DeviceSerial.getBestEffort(application)
                val deviceStableId = DeviceSerial.getStableAndroidId(application)
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
                if (deviceStableId.isNotEmpty()) {
                    data["deviceStableId"] = deviceStableId
                }
                if (deviceSerial.isNotEmpty()) {
                    data["deviceSerial"] = deviceSerial
                }

                MerchantFirestore.doc(COLLECTION, docId)
                    .set(data, SetOptions.merge())
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Heartbeat failed: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat worker failed: ${e.message}")
            }
        }
    }
}
