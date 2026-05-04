package com.ernesto.myapplication

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Real-time listener: **new** documents only ([DocumentChange.Type.ADDED]) matching
 * [sourceField]==[sourceValue] and [statusField]==[statusValue], skipping the first snapshot
 * so existing OPEN orders do not alert.
 *
 * **Collection path:** Your spec uses `"orders"`; this project historically uses `"Orders"`.
 * Pass [ordersCollectionPath] to match your Firestore console (case-sensitive).
 *
 * Composite index: Firestore may require a composite index on ([sourceField], [statusField]).
 */
class OnlineOrderAlertFirestoreListener(
    private val firestore: FirebaseFirestore,
    private val ordersCollectionPath: String,
    private val sourceField: String,
    private val sourceValue: String,
    private val statusField: String,
    private val statusValue: String,
    private val onNewOnlineOpenOrder: (OnlineOrderAlertPayload) -> Unit,
) : DefaultLifecycleObserver {

    private var registration: ListenerRegistration? = null
    private var skipFirstSnapshot: Boolean = true
    private val notifiedOrderIdsThisSession = LinkedHashSet<String>()

    override fun onStart(owner: LifecycleOwner) {
        attach()
    }

    override fun onStop(owner: LifecycleOwner) {
        detach()
    }

    private fun attach() {
        if (registration != null) return
        skipFirstSnapshot = true
        registration = firestore.collection(ordersCollectionPath)
            .whereEqualTo(sourceField, sourceValue)
            .whereEqualTo(statusField, statusValue)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (skipFirstSnapshot) {
                    skipFirstSnapshot = false
                    return@addSnapshotListener
                }
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    if (!notifiedOrderIdsThisSession.add(doc.id)) continue
                    val payload = doc.toOnlineOrderAlertPayloadOrNull() ?: continue
                    onNewOnlineOpenOrder(payload)
                }
            }
    }

    private fun detach() {
        registration?.remove()
        registration = null
    }

    companion object {
        /** Default matches common web / POS literal for online channel. */
        const val DEFAULT_SOURCE_FIELD = "source"
        const val DEFAULT_SOURCE_VALUE = "ONLINE"
        const val DEFAULT_STATUS_FIELD = "status"
        const val DEFAULT_STATUS_VALUE = "OPEN"

        /** Matches existing MyApplication Firestore collection name. */
        const val DEFAULT_ORDERS_COLLECTION = "Orders"
    }
}
