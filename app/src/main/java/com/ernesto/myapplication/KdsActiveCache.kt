package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Lightweight singleton that listens to the `kds_devices` Firestore collection.
 * [hasActiveKds] is `true` only when at least one device document exists.
 *
 * Call [start] once at app startup (e.g. from [OrdersActivity] or Application).
 * The listener stays alive for the process lifetime and is cheap (tiny collection).
 */
object KdsActiveCache {

    private const val COLLECTION = "kds_devices"

    @Volatile
    var hasActiveKds: Boolean = false
        private set

    private var registration: ListenerRegistration? = null

    @Synchronized
    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        if (registration != null) return
        registration = db.collection(COLLECTION)
            .addSnapshotListener { snapshot, _ ->
                hasActiveKds = snapshot != null && !snapshot.isEmpty
            }
    }

    @Synchronized
    fun stop() {
        registration?.remove()
        registration = null
        hasActiveKds = false
    }
}
