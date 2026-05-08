package com.ernesto.myapplication

import android.app.Application
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
 */
object PosDeviceDeactivationWatch {

    private const val TAG = "PosDeactivationWatch"
    private const val COLLECTION = "PosDevices"
    const val FIELD_DEACTIVATED = "deactivated"

    private var registration: ListenerRegistration? = null
    private var app: Application? = null
    private val armed = AtomicBoolean(false)

    fun start(application: Application) {
        stop()
        app = application
        PosDeviceIdentity.resolveInstallationDocId(application) { docId ->
            val ctx = app ?: return@resolveInstallationDocId
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
                Handler(Looper.getMainLooper()).post {
                    DeviceActivationActivity.launchForceLock(a)
                }
            }
        }
    }

    fun stop() {
        registration?.remove()
        registration = null
        armed.set(false)
        app = null
    }
}
