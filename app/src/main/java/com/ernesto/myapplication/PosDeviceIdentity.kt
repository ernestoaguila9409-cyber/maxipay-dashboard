package com.ernesto.myapplication

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.installations.FirebaseInstallations

/** Stable Firestore document id for this app install ([PosDevices] / activation). */
object PosDeviceIdentity {

    private const val TAG = "PosDeviceIdentity"

    fun sanitizeDocId(raw: String): String =
        raw.replace(Regex("[/#.\\[\\]]"), "_").take(700).ifBlank { "device_unknown" }

    /**
     * Resolves the same id used by [PosDevicePresenceSync] heartbeats (Firebase Installation ID
     * when available, else ANDROID_ID-based fallback).
     */
    fun resolveInstallationDocId(context: Context, onResult: (String) -> Unit) {
        val appContext = context.applicationContext
        FirebaseInstallations.getInstance().id
            .addOnSuccessListener { fid ->
                if (fid.isNotBlank()) onResult(sanitizeDocId(fid))
                else onResult(fallbackDocId(appContext))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Installations id failed: ${e.message}")
                onResult(fallbackDocId(appContext))
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
