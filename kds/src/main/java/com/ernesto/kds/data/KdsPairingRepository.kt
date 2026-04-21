package com.ernesto.kds.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class KdsPairingRepository(
    context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(KdsDevicePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Links this tablet to the KDS devices collection using a 6-digit pairing code.
     * Persists device doc id and station id from the matched document.
     */
    suspend fun pairWithCode(rawCode: String): Result<String> {
        val digits = rawCode.filter { it.isDigit() }
        if (digits.length != 6) {
            return Result.failure(IllegalArgumentException("Enter all 6 digits"))
        }
        return runCatching {
            val snapshot = db.collection(KdsDevicePresence.KDS_DEVICES_COLLECTION)
                .whereEqualTo("pairingCode", digits)
                .limit(1)
                .get()
                .await()

            if (snapshot.isEmpty) {
                throw IllegalStateException("Invalid code")
            }

            val doc = snapshot.documents.first()
            if (doc.getBoolean("isPaired") == true) {
                throw IllegalStateException("Code already used")
            }

            doc.reference.update(
                mapOf(
                    "isPaired" to true,
                    "deviceType" to "tablet",
                    "deviceModel" to TabletHardware.modelLabel(),
                    "registeredFromWeb" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "pairingCode" to FieldValue.delete(),
                ),
            ).await()

            val stationId = doc.getString("stationId")?.trim().orEmpty()
            // commit() so the id is readable before returning; apply() can race the heartbeat loop.
            prefs.edit()
                .putString(KdsDevicePrefs.KEY_DEVICE_DOC_ID, doc.id)
                .putString(KdsDevicePrefs.KEY_STATION_ID, stationId)
                .commit()

            doc.id
        }
    }
}
