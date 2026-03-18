package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source

object DashboardConfigManager {

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
                        onResult(modules.sortedBy { it.position })
                        return@addOnSuccessListener
                    }
                }
                onResult(DashboardModule.getDefaults())
            }
            .addOnFailureListener {
                onResult(emptyList())
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
            .addOnFailureListener {
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
                if (error != null || snapshot == null) return@addSnapshotListener
                if (snapshot.exists()) {
                    val modules = parseModules(snapshot.get(FIELD_MODULES))
                    if (modules.isNotEmpty()) {
                        val sorted = modules.sortedBy { it.position }
                        onUpdate(sorted)
                        if (snapshot.metadata.isFromCache && onCacheThenServer != null) {
                            loadFromServer(db) { serverModules ->
                                if (serverModules.isNotEmpty()) onCacheThenServer(serverModules)
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
            .addOnFailureListener { onComplete(false) }
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
