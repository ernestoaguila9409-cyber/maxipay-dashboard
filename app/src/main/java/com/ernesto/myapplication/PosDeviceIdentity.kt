package com.ernesto.myapplication

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.installations.FirebaseInstallations

/** Stable Firestore document id for this app install ([PosDevices] / activation). */
object PosDeviceIdentity {

    private const val TAG = "PosDeviceIdentity"
    private const val PREFS_NAME = "pos_device_identity"
    private const val KEY_DOC_ID = "firestore_doc_id"

    fun sanitizeDocId(raw: String): String =
        raw.replace(Regex("[/#.\\[\\]]"), "_").take(700).ifBlank { "device_unknown" }

    /**
     * Resolves the same id used by [PosDevicePresenceSync] heartbeats (Firebase Installation ID
     * when available, else ANDROID_ID-based fallback). The first resolved value is **persisted** so
     * all callers (presence, deactivation watch, activation redeem) use one document path.
     */
    fun resolveInstallationDocId(context: Context, onResult: (String) -> Unit) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DOC_ID, null)?.trim()
        if (!cached.isNullOrEmpty()) {
            onResult(cached)
            return
        }

        FirebaseInstallations.getInstance().id
            .addOnSuccessListener { fid ->
                val id = if (fid.isNotBlank()) sanitizeDocId(fid) else fallbackDocId(appContext)
                prefs.edit().putString(KEY_DOC_ID, id).apply()
                onResult(id)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Installations id failed: ${e.message}")
                val id = fallbackDocId(appContext)
                prefs.edit().putString(KEY_DOC_ID, id).apply()
                onResult(id)
            }
    }

    private fun fallbackDocId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val raw = androidId?.trim()?.takeIf { it.isNotEmpty() && it != "9774d56d682e549c" }
        return sanitizeDocId("android_${raw ?: Build.FINGERPRINT.hashCode()}")
    }
}
