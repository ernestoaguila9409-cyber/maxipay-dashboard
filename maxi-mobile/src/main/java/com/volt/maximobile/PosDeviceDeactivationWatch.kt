package com.volt.maximobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens to [Merchants/{mid}/posDevices/{installationId}] for [FIELD_DEACTIVATED], matching main POS
 * [com.ernesto.myapplication.PosDeviceDeactivationWatch]. Clears local activation prefs and notifies
 * the UI to return to the activation code screen.
 */
object PosDeviceDeactivationWatch {

    private const val TAG = "PosDeviceDeactivation"
    private const val COLLECTION = "PosDevices"

    const val FIELD_DEACTIVATED = "deactivated"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appRef: Application? = null
    private var lifecycleInstalled = false
    private var registration: ListenerRegistration? = null
    private val armed = AtomicBoolean(false)

    fun install(application: Application) {
        appRef = application
        if (lifecycleInstalled) return
        lifecycleInstalled = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    PosDeviceDeactivationEnforcement.checkServerAndLockIfNeeded(activity)
                }
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /**
     * (Re)attach the Firestore listener for the current [PosDeviceIdentity] merchant id, if any.
     * Call after anonymous auth, after redeeming an activation code, or when the app process starts
     * with a stored merchant id.
     */
    fun syncFirestoreListener(application: Application) {
        appRef = application
        registration?.remove()
        registration = null
        armed.set(false)

        val mid = PosDeviceIdentity.getMerchantId(application).trim()
        if (mid.isEmpty()) {
            PosDevicePresenceSync.stop()
            Log.d(TAG, "syncFirestoreListener: no merchant id")
            return
        }
        if (!MerchantFirestore.isInitialized) {
            MerchantFirestore.init(mid)
        }

        // Heartbeats require a signed-in Firebase user (anonymous is fine). Starting before auth
        // completes yields permission-denied writes and a stale dashboard "Last seen".
        if (FirebaseAuth.getInstance().currentUser != null) {
            PosDevicePresenceSync.start(application)
        } else {
            PosDevicePresenceSync.stop()
        }

        PosDeviceIdentity.resolveInstallationDocId(application) { docId ->
            if (PosDeviceIdentity.getMerchantId(application).trim().isEmpty()) {
                return@resolveInstallationDocId
            }
            val ref = MerchantFirestore.doc(COLLECTION, docId)
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
                val app = appRef ?: return@addSnapshotListener
                /*
                 * Local snapshot cache can still show [deactivated]=true briefly right after a fresh
                 * redeem (merge sets false on the server). Confirm from the server before locking.
                 */
                ref.get(Source.SERVER)
                    .addOnSuccessListener { serverSnap ->
                        if (!serverSnap.exists()) {
                            armed.set(false)
                            return@addOnSuccessListener
                        }
                        if (serverSnap.getBoolean(FIELD_DEACTIVATED) != true) {
                            armed.set(false)
                            return@addOnSuccessListener
                        }
                        PosDeviceDeactivationEnforcement.skipNextThrottleWindow()
                        lockIfDeactivated(app)
                    }
                    .addOnFailureListener { ex ->
                        Log.w(TAG, "deactivated server verify failed: ${ex.message}")
                        armed.set(false)
                    }
            }
        }
    }

    internal fun lockIfDeactivated(appContext: Application) {
        if (!armed.compareAndSet(false, true)) return
        mainHandler.post {
            try {
                registration?.remove()
                registration = null
                PosDevicePresenceSync.stop()
                PosDeviceIdentity.clearAll(appContext)
                MerchantFirestore.reset()
                PosDeviceDeactivationNotifier.notifyForceActivation()
            } catch (t: Throwable) {
                Log.w(TAG, "lockIfDeactivated: ${t.message}")
            }
        }
    }
}
