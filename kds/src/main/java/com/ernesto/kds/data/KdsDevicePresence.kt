package com.ernesto.kds.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

sealed class HeartbeatOutcome {
    object Ok : HeartbeatOutcome()
    /** Document was deleted or this client lost access — clear local pairing. */
    object RevokedOrDeleted : HeartbeatOutcome()
    object TransientFailure : HeartbeatOutcome()
    object NoDeviceConfigured : HeartbeatOutcome()
}

/**
 * After pairing, sends lastSeen heartbeats on the paired KDS device document.
 * Uses Firestore update only (never set), so a deleted dashboard row cannot be recreated by the app.
 */
class KdsDevicePresence(
    private val appContext: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private val prefs by lazy {
        appContext.getSharedPreferences(KdsDevicePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun heartbeatOnce(): HeartbeatOutcome {
        val docId = prefs.getString(KdsDevicePrefs.KEY_DEVICE_DOC_ID, null)?.trim().orEmpty()
        if (docId.isEmpty()) return HeartbeatOutcome.NoDeviceConfigured
        val ref = db.collection(KDS_DEVICES_COLLECTION).document(docId)
        return try {
            val snap = ref.get(Source.SERVER).await()
            if (!snap.exists()) {
                return HeartbeatOutcome.RevokedOrDeleted
            }
            ref.update(
                mapOf(
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "deviceModel" to TabletHardware.modelLabel(),
                ),
            ).await()
            HeartbeatOutcome.Ok
        } catch (e: FirebaseFirestoreException) {
            when (e.code) {
                FirebaseFirestoreException.Code.NOT_FOUND,
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                -> HeartbeatOutcome.RevokedOrDeleted
                else -> HeartbeatOutcome.TransientFailure
            }
        } catch (_: Exception) {
            HeartbeatOutcome.TransientFailure
        }
    }

    companion object {
        const val KDS_DEVICES_COLLECTION = "kds_devices"
    }
}
