package com.ernesto.myapplication

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Caches `Settings/onlineOrdering` → `onlineRoutingPrinterIds` and `onlineRoutingKdsDeviceIds`
 * so the POS print path and KDS can check whether to add online-specific destinations.
 */
object OnlineOrderKitchenRoutingCache {

    private const val TAG = "OnlineKitchenRoute"

    @Volatile var onlineRoutingPrinterIds: Set<String> = emptySet()
        private set

    @Volatile var onlineRoutingKdsDeviceIds: Set<String> = emptySet()
        private set

    private var registration: ListenerRegistration? = null

    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        registration?.remove()
        registration = db.collection("Settings").document("onlineOrdering")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "listener error", err)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    onlineRoutingPrinterIds = emptySet()
                    onlineRoutingKdsDeviceIds = emptySet()
                    return@addSnapshotListener
                }
                onlineRoutingPrinterIds = parseIdList(snap.get("onlineRoutingPrinterIds"))
                onlineRoutingKdsDeviceIds = parseIdList(snap.get("onlineRoutingKdsDeviceIds"))
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
        onlineRoutingPrinterIds = emptySet()
        onlineRoutingKdsDeviceIds = emptySet()
    }

    private fun parseIdList(raw: Any?): Set<String> {
        val list = raw as? List<*> ?: return emptySet()
        return list.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.toSet()
    }
}
