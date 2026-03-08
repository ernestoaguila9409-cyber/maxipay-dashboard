package com.ernesto.myapplication

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

object TerminalPrefs {
    const val PREFS_NAME = "terminal_prefs"
    const val KEY_TPN = "tpn"
    const val KEY_REGISTER_ID = "register_id"
    const val KEY_AUTH_KEY = "auth_key"

    private const val DEFAULT_TPN = "11881706541A"
    private const val DEFAULT_REGISTER_ID = "134909005"
    private const val DEFAULT_AUTH_KEY = "Qt9N7CxhDs"

    private var cachedTpn: String? = null
    private var cachedRegisterId: String? = null
    private var cachedAuthKey: String? = null
    private var cacheLoaded = false

    fun initFromFirestore() {
        FirebaseFirestore.getInstance()
            .collection("Terminals")
            .orderBy("name")
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val doc = snap.documents.firstOrNull()
                if (doc != null) {
                    cachedTpn = doc.getString("tpn")?.takeIf { it.isNotBlank() }
                    cachedRegisterId = doc.getString("registerId")?.takeIf { it.isNotBlank() }
                    cachedAuthKey = doc.getString("authKey")?.takeIf { it.isNotBlank() }
                }
                cacheLoaded = true
            }
            .addOnFailureListener { cacheLoaded = true }
    }

    fun getTpn(context: Context): String {
        return cachedTpn
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TPN, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TPN
    }

    fun getRegisterId(context: Context): String {
        return cachedRegisterId
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_REGISTER_ID, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_REGISTER_ID
    }

    fun getAuthKey(context: Context): String {
        return cachedAuthKey
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_AUTH_KEY, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AUTH_KEY
    }

    fun refreshCache() {
        cacheLoaded = false
        cachedTpn = null
        cachedRegisterId = null
        cachedAuthKey = null
        initFromFirestore()
    }
}
