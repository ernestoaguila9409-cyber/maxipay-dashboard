package com.ernesto.myapplication

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens to [PosDevices] for this installation; when the dashboard sets [FIELD_DEACTIVATED], clears
 * the task and opens [DeviceActivationActivity] in force mode. Re-armed when [FIELD_DEACTIVATED]
 * clears (e.g. after a new activation code).
 *
 * Also registers an [Application.ActivityLifecycleCallbacks] hook that periodically re-checks the
 * server (see [PosDeviceDeactivationEnforcement]) so deactivation is picked up even if the local
 * snapshot cache lags.
 */
object PosDeviceDeactivationWatch {

    private const val TAG = "PosDeactivationWatch"
    private const val COLLECTION = "PosDevices"
    const val FIELD_DEACTIVATED = "deactivated"

    private var registration: ListenerRegistration? = null
    private var app: Application? = null
    private var registeredApp: Application? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private val armed = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(application: Application) {
        stop()
        app = application
        registeredApp = application

        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity is DeviceActivationActivity) return
                PosDeviceDeactivationEnforcement.checkServerAndLockIfNeeded(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        activityCallbacks = cb
        application.registerActivityLifecycleCallbacks(cb)

        PosDeviceIdentity.resolveInstallationDocId(application) { docId ->
            if (app == null) return@resolveInstallationDocId
            val ref = FirebaseFirestore.getInstance().collection(COLLECTION).document(docId)
            registration = ref.addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.w(TAG, "listen: ${e.message}")
                    return@addSnapshotListener
                }
                val doc = snap?.takeIf { it.exists() } ?: run {
                    armed.set(false)
                    return@addSnapshotListener
                }
                val isDeactivated = doc.getBoolean(FIELD_DEACTIVATED) == true
                if (!isDeactivated) {
                    armed.set(false)
                    return@addSnapshotListener
                }
                if (!armed.compareAndSet(false, true)) {
                    return@addSnapshotListener
                }
                val a = app ?: return@addSnapshotListener
                PosDeviceDeactivationEnforcement.skipNextThrottleWindow()
                mainHandler.post {
                    DeviceActivationActivity.launchForceLock(a)
                }
            }
        }
    }

    fun stop() {
        activityCallbacks?.let { cb ->
            try {
                registeredApp?.unregisterActivityLifecycleCallbacks(cb)
            } catch (_: Exception) {
            }
        }
        activityCallbacks = null
        registeredApp = null

        registration?.remove()
        registration = null
        armed.set(false)
        app = null
    }
}
