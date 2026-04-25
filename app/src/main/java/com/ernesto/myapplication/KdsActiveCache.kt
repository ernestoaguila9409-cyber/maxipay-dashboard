package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight singleton that listens to the `kds_devices` Firestore collection.
 * [hasActiveKds] is `true` only when at least one device document exists.
 * [hasOnlineKds] is `true` when at least one device has a recent [lastSeen] (KDS app heartbeats).
 *
 * Call [start] once at app startup (e.g. from [OrdersActivity] or Application).
 * The listener stays alive for the process lifetime and is cheap (tiny collection).
 */
object KdsActiveCache {

    private const val COLLECTION = "kds_devices"

    /**
     * KDS tablets heartbeat about every 5s; allow slack for latency and transient failures.
     */
    private const val ONLINE_LAST_SEEN_MAX_MS = 45_000L

    @Volatile
    var hasActiveKds: Boolean = false
        private set

    @Volatile
    var hasOnlineKds: Boolean = false
        private set

    private var registration: ListenerRegistration? = null

    private val availabilityListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addAvailabilityListener(listener: () -> Unit) {
        availabilityListeners.add(listener)
    }

    fun removeAvailabilityListener(listener: () -> Unit) {
        availabilityListeners.remove(listener)
    }

    private fun applySnapshot(snapshot: QuerySnapshot?) {
        val prevActive = hasActiveKds
        val prevOnline = hasOnlineKds
        hasActiveKds = snapshot != null && !snapshot.isEmpty
        hasOnlineKds = if (snapshot == null || snapshot.isEmpty) {
            false
        } else {
            val now = System.currentTimeMillis()
            snapshot.documents.any { doc ->
                val ts = doc.getTimestamp("lastSeen") ?: return@any false
                now - ts.toDate().time <= ONLINE_LAST_SEEN_MAX_MS
            }
        }
        if (prevActive != hasActiveKds || prevOnline != hasOnlineKds) {
            for (l in availabilityListeners) {
                runCatching { l() }
            }
        }
    }

    @Synchronized
    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        if (registration != null) return
        registration = db.collection(COLLECTION)
            .addSnapshotListener { snapshot, _ ->
                applySnapshot(snapshot)
            }
    }

    @Synchronized
    fun stop() {
        registration?.remove()
        registration = null
        hasActiveKds = false
        hasOnlineKds = false
    }
}
