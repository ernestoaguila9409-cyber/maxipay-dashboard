package com.ernesto.myapplication

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source

object DashboardConfigManager {

    private const val TAG = "DashboardConfig"
    private const val COLLECTION = "Settings"
    private const val DOCUMENT = "dashboard"
    private const val FIELD_MODULES = "modules"

    /**
     * Force-fetch from server to ensure we have the latest config (e.g. after user saves
     * icon changes on web dashboard). Use when app resumes to avoid stale cache.
     */
    fun loadFromServer(
        db: FirebaseFirestore,
        onResult: (List<DashboardModule>) -> Unit
    ) {
        db.collection(COLLECTION).document(DOCUMENT)
            .get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val modules = parseModules(doc.get(FIELD_MODULES))
                    if (modules.isNotEmpty()) {
                        Log.d(TAG, "Server returned ${modules.size} modules")
                        onResult(modules.sortedBy { it.position })
                        return@addOnSuccessListener
                    }
                }
                Log.d(TAG, "Server doc empty or missing, using defaults")
                onResult(DashboardModule.getDefaults())
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Server fetch failed, using defaults", e)
                onResult(DashboardModule.getDefaults())
            }
    }

    fun loadConfig(
        db: FirebaseFirestore,
        onResult: (List<DashboardModule>) -> Unit
    ) {
        db.collection(COLLECTION).document(DOCUMENT).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val modules = parseModules(doc.get(FIELD_MODULES))
                    if (modules.isNotEmpty()) {
                        onResult(modules.sortedBy { it.position })
                        return@addOnSuccessListener
                    }
                }
                val defaults = DashboardModule.getDefaults()
                saveConfig(db, defaults) {}
                onResult(defaults)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "loadConfig failed", e)
                onResult(DashboardModule.getDefaults())
            }
    }

    fun listenConfig(
        db: FirebaseFirestore,
        onUpdate: (List<DashboardModule>) -> Unit,
        onCacheThenServer: ((List<DashboardModule>) -> Unit)? = null
    ): ListenerRegistration {
        return db.collection(COLLECTION).document(DOCUMENT)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Listener error, forcing server fetch", error)
                    loadFromServer(db) { modules -> onUpdate(modules) }
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val fromCache = snapshot.metadata.isFromCache
                Log.d(TAG, "Snapshot received (fromCache=$fromCache, exists=${snapshot.exists()})")

                if (snapshot.exists()) {
                    val modules = parseModules(snapshot.get(FIELD_MODULES))
                    if (modules.isNotEmpty()) {
                        val sorted = modules.sortedBy { it.position }
                        onUpdate(sorted)
                        if (fromCache) {
                            loadFromServer(db) { serverModules ->
                                if (serverModules.isNotEmpty()) {
                                    (onCacheThenServer ?: onUpdate).invoke(serverModules)
                                }
                            }
                        }
                        return@addSnapshotListener
                    }
                }
                onUpdate(DashboardModule.getDefaults())
            }
    }

    fun saveConfig(
        db: FirebaseFirestore,
        modules: List<DashboardModule>,
        onComplete: (Boolean) -> Unit
    ) {
        val indexed = modules.mapIndexed { index, m ->
            m.copy(position = index).toMap()
        }
        db.collection(COLLECTION).document(DOCUMENT)
            .set(mapOf(FIELD_MODULES to indexed))
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { e ->
                Log.e(TAG, "saveConfig failed", e)
                onComplete(false)
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseModules(raw: Any?): List<DashboardModule> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            DashboardModule.fromMap(map)
        }
    }
}
