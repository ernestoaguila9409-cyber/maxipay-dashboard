package com.ernesto.myapplication

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations

/** Stable Firestore document id for this app install ([PosDevices] / activation). */
object PosDeviceIdentity {

    private const val TAG = "PosDeviceIdentity"
    private const val PREFS_NAME = "pos_device_identity"
    private const val KEY_DOC_ID = "firestore_doc_id"
    private const val KEY_MERCHANT_ID = "merchant_id"
    /** [Merchants] root [businessName] — shown on PIN login before receipt prefs sync. */
    private const val KEY_MERCHANT_BUSINESS_NAME = "merchant_business_name"

    /** Returns the persisted merchant id (set after activation), or empty string. */
    fun getMerchantId(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MERCHANT_ID, null)?.trim().orEmpty()
    }

    /** Persists the merchant id after a successful activation code redemption. */
    fun setMerchantId(context: Context, merchantId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MERCHANT_ID, merchantId)
            .commit()
    }

    /** Display name from [Merchants] / activation; used on the PIN login header. */
    fun getMerchantBusinessName(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MERCHANT_BUSINESS_NAME, null)?.trim().orEmpty()
    }

    fun setMerchantBusinessName(context: Context, businessName: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MERCHANT_BUSINESS_NAME, businessName.trim())
            .commit()
    }

    /**
     * Reads [Merchants]/{merchantId}.businessName (allowed for anonymous auth) and caches it
     * locally, plus merges into [ReceiptSettings] prefs so the login title matches the activated
     * merchant even before [ReceiptSettings.startBusinessInfoSync] delivers a snapshot.
     */
    fun syncMerchantBusinessNameFromFirestore(appContext: Context, onDone: (() -> Unit)? = null) {
        val mid = getMerchantId(appContext).trim()
        if (mid.isEmpty()) {
            onDone?.invoke()
            return
        }
        FirebaseFirestore.getInstance().collection("Merchants").document(mid).get()
            .addOnSuccessListener { snap ->
                val name = snap.getString("businessName")?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    setMerchantBusinessName(appContext, name)
                    val cur = ReceiptSettings.load(appContext)
                    if (cur.businessName != name) {
                        ReceiptSettings.save(appContext, cur.copy(businessName = name))
                    }
                }
                onDone?.invoke()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "syncMerchantBusinessNameFromFirestore failed: ${e.message}")
                onDone?.invoke()
            }
    }

    /** Clears all persisted identity (for full device reset / re-activation). */
    fun clearAll(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        MerchantFirestore.reset()
    }

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
