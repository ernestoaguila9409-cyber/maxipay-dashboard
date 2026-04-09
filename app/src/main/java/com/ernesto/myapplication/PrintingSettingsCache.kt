package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * In-memory mirror of [PrintingSettingsFirestore] for synchronous reads on the POS
 * (kitchen chit path). Updated in real time while signed in.
 */
object PrintingSettingsCache {

    @Volatile
    var printItemFilterMode: String = PrintingSettingsFirestore.BY_LABEL

    @Volatile
    var printTriggerMode: String = PrintingSettingsFirestore.FIRST_EVENT

    private var registration: ListenerRegistration? = null

    fun start(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        synchronized(this) {
            if (registration != null) return
            val ref = PrintingSettingsFirestore.documentRef(db)
            registration = ref.addSnapshotListener { snap, _ ->
                printItemFilterMode = when {
                    snap == null || !snap.exists() -> PrintingSettingsFirestore.BY_LABEL
                    else -> PrintingSettingsFirestore.printItemFilterModeFromSnapshot(snap)
                }
                printTriggerMode = when {
                    snap == null || !snap.exists() -> PrintingSettingsFirestore.FIRST_EVENT
                    else -> PrintingSettingsFirestore.printTriggerModeFromSnapshot(snap)
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            registration?.remove()
            registration = null
            printItemFilterMode = PrintingSettingsFirestore.BY_LABEL
            printTriggerMode = PrintingSettingsFirestore.FIRST_EVENT
        }
    }
}
