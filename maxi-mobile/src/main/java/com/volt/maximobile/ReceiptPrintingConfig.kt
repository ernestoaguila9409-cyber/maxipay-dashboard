package com.volt.maximobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration

/**
 * Controls what happens on the register after an order is fully paid.
 * Synced per merchant via Firestore `Settings/receiptPrintingConfig`.
 */
object ReceiptPrintingConfig {

    const val MODE_AUTO_PRINT_AND_ASK = "AUTO_PRINT_AND_ASK"
    const val MODE_PROMPT_AFTER_ORDER = "PROMPT_AFTER_ORDER"
    const val MODE_DO_NOT_PRINT = "DO_NOT_PRINT"

    private const val PREFS_NAME = "receipt_printing_config"
    private const val KEY_MODE = "mode"
    private const val FIRESTORE_DOC_ID = "receiptPrintingConfig"
    private const val FIRESTORE_FIELD_MODE = "mode"

    @Volatile
    private var listener: ListenerRegistration? = null

    private var onModeChanged: ((String) -> Unit)? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalize(mode: String): String = when (mode) {
        MODE_AUTO_PRINT_AND_ASK -> MODE_AUTO_PRINT_AND_ASK
        MODE_DO_NOT_PRINT -> MODE_DO_NOT_PRINT
        else -> MODE_PROMPT_AFTER_ORDER
    }

    fun getMode(context: Context): String =
        normalize(prefs(context).getString(KEY_MODE, MODE_PROMPT_AFTER_ORDER) ?: MODE_PROMPT_AFTER_ORDER)

    fun setMode(context: Context, mode: String, pushToFirestore: Boolean = true) {
        val normalized = normalize(mode)
        prefs(context).edit().putString(KEY_MODE, normalized).apply()
        if (pushToFirestore) {
            saveToFirestore(normalized)
        }
        onModeChanged?.invoke(normalized)
    }

    fun setOnModeChangedListener(listener: ((String) -> Unit)?) {
        onModeChanged = listener
    }

    fun startSync(context: Context) {
        stopSync()
        if (!MerchantFirestore.isInitialized) {
            Log.w("ReceiptPrintingConfig", "startSync skipped: MerchantFirestore not initialized")
            return
        }
        val appContext = context.applicationContext
        listener = MerchantFirestore.doc("Settings", FIRESTORE_DOC_ID)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w("ReceiptPrintingConfig", "Sync error", err)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) return@addSnapshotListener
                val remote = snap.getString(FIRESTORE_FIELD_MODE) ?: return@addSnapshotListener
                val normalized = normalize(remote)
                if (getMode(appContext) == normalized) return@addSnapshotListener
                setMode(appContext, normalized, pushToFirestore = false)
                Log.d("ReceiptPrintingConfig", "Synced mode from Firestore: $normalized")
            }
    }

    fun stopSync() {
        listener?.remove()
        listener = null
    }

    private fun saveToFirestore(mode: String) {
        if (!MerchantFirestore.isInitialized) {
            Log.w("ReceiptPrintingConfig", "saveToFirestore skipped: MerchantFirestore not initialized")
            return
        }
        val data = hashMapOf<String, Any>(FIRESTORE_FIELD_MODE to mode)
        MerchantFirestore.doc("Settings", FIRESTORE_DOC_ID)
            .set(data)
            .addOnSuccessListener { Log.d("ReceiptPrintingConfig", "Saved to Firestore: $mode") }
            .addOnFailureListener { Log.w("ReceiptPrintingConfig", "Firestore save failed", it) }
    }

    fun shouldAutoPrintOnPayment(context: Context): Boolean =
        getMode(context) == MODE_AUTO_PRINT_AND_ASK

    fun shouldPromptAfterPayment(context: Context): Boolean =
        getMode(context) == MODE_PROMPT_AFTER_ORDER

    fun shouldSkipReceiptFlow(context: Context): Boolean =
        getMode(context) == MODE_DO_NOT_PRINT
}
