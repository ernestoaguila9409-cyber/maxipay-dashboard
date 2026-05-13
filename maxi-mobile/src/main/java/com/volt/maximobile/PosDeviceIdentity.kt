package com.volt.maximobile

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations

/** Same prefs + installation id logic as main POS [com.ernesto.myapplication.PosDeviceIdentity]. */
object PosDeviceIdentity {

    private const val TAG = "PosDeviceIdentity"
    private const val PREFS_NAME = "pos_device_identity"
    private const val KEY_DOC_ID = "firestore_doc_id"
    private const val KEY_MERCHANT_ID = "merchant_id"
    private const val KEY_MERCHANT_BUSINESS_NAME = "merchant_business_name"

    fun getMerchantId(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MERCHANT_ID, null)?.trim().orEmpty()
    }

    fun setMerchantId(context: Context, merchantId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MERCHANT_ID, merchantId)
            .commit()
    }

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
                }
                onDone?.invoke()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "syncMerchantBusinessNameFromFirestore failed: ${e.message}")
                onDone?.invoke()
            }
    }

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
