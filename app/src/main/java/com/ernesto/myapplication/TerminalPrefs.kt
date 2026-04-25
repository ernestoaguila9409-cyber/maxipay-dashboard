package com.ernesto.myapplication

import android.content.Context
import com.ernesto.myapplication.payments.PaymentTerminalConfig
import com.ernesto.myapplication.payments.PaymentTerminalRepository

/**
 * Backwards-compatible accessor for the current SPIn credentials.
 *
 * Historically this object read the first doc in the legacy `Terminals`
 * collection. It now delegates to [PaymentTerminalRepository], which loads the
 * active terminal from the dashboard-managed `payment_terminals` collection
 * (falling back to the legacy collection if nothing has been migrated yet).
 *
 * Existing callers (`PaymentService`, `PaymentActivity`, etc.) keep using
 * `TerminalPrefs.getTpn(context)` and friends unchanged.
 */
object TerminalPrefs {
    const val PREFS_NAME = "terminal_prefs"
    const val KEY_TPN = "tpn"
    const val KEY_REGISTER_ID = "register_id"
    const val KEY_AUTH_KEY = "auth_key"

    private const val DEFAULT_TPN = "11881706541A"
    private const val DEFAULT_REGISTER_ID = "134909005"
    private const val DEFAULT_AUTH_KEY = "Qt9N7CxhDs"

    /**
     * Kept for source compatibility — the repository now starts as part of
     * [PosApplication] and streams updates, so this is a no-op. Safe to call.
     */
    fun initFromFirestore() {
        // PaymentTerminalRepository.start() is invoked from PosApplication.
    }

    /** Kept for source compatibility — repository updates live. */
    fun refreshCache() {
        // No-op. The listener in PaymentTerminalRepository keeps data fresh.
    }

    fun getTpn(context: Context): String =
        readCredential(context, PaymentTerminalConfig.Companion.ConfigKey.TPN, KEY_TPN, DEFAULT_TPN)

    fun getRegisterId(context: Context): String =
        readCredential(context, PaymentTerminalConfig.Companion.ConfigKey.REGISTER_ID, KEY_REGISTER_ID, DEFAULT_REGISTER_ID)

    fun getAuthKey(context: Context): String =
        readCredential(context, PaymentTerminalConfig.Companion.ConfigKey.AUTH_KEY, KEY_AUTH_KEY, DEFAULT_AUTH_KEY)

    private fun readCredential(
        context: Context,
        configKey: String,
        legacyPrefKey: String,
        defaultValue: String,
    ): String {
        val fromRepo = PaymentTerminalRepository.getActiveConfig(context)
            ?.credential(configKey)
            ?.takeIf { it.isNotBlank() }
        if (fromRepo != null) return fromRepo

        val fromPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(legacyPrefKey, null)
            ?.takeIf { it.isNotBlank() }
        if (fromPrefs != null) return fromPrefs

        return defaultValue
    }
}
