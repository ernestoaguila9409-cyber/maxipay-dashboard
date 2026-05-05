package com.ernesto.myapplication.payments

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Loads and caches the active payment terminal configuration from Firestore.
 *
 * - Primary source: `payment_terminals` (written by the web dashboard).
 * - Fallback: legacy `Terminals` collection (still read if no `payment_terminals`
 *   exist so the POS keeps working on first launch after the refactor).
 *
 * The app picks a single "active" terminal per device; selection is persisted
 * in SharedPreferences and auto-defaults to the first active doc.
 */
object PaymentTerminalRepository {

    private const val TAG = "PaymentTerminalRepo"
    private const val COLLECTION_PAYMENT_TERMINALS = "payment_terminals"
    private const val COLLECTION_LEGACY_TERMINALS = "Terminals"

    private const val PREFS_NAME = "payment_terminal_prefs"
    private const val KEY_ACTIVE_TERMINAL_ID = "active_terminal_id"

    private val db get() = FirebaseFirestore.getInstance()

    @Volatile private var terminalsListener: ListenerRegistration? = null
    @Volatile private var legacyListener: ListenerRegistration? = null

    private val terminalsById = linkedMapOf<String, PaymentTerminalConfig>()
    private val legacyById = linkedMapOf<String, PaymentTerminalConfig>()

    @Volatile private var loaded = false

    fun start() {
        stop()
        terminalsListener = db.collection(COLLECTION_PAYMENT_TERMINALS)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "payment_terminals listener error: ${err.message}")
                    return@addSnapshotListener
                }
                snap ?: return@addSnapshotListener
                terminalsById.clear()
                for (doc in snap.documents) {
                    parseNew(doc)?.let { terminalsById[it.id] = it }
                }
                loaded = true
            }

        legacyListener = db.collection(COLLECTION_LEGACY_TERMINALS)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "Terminals (legacy) listener error: ${err.message}")
                    return@addSnapshotListener
                }
                snap ?: return@addSnapshotListener
                legacyById.clear()
                for (doc in snap.documents) {
                    parseLegacy(doc)?.let { legacyById[it.id] = it }
                }
                loaded = true
            }
    }

    fun stop() {
        terminalsListener?.remove()
        terminalsListener = null
        legacyListener?.remove()
        legacyListener = null
        terminalsById.clear()
        legacyById.clear()
        loaded = false
    }

    fun isLoaded(): Boolean = loaded

    /** True once Firestore has reported at least one terminal document (new or legacy collection). */
    fun hasAnyTerminalDocument(): Boolean =
        terminalsById.isNotEmpty() || legacyById.isNotEmpty()

    fun getAllTerminals(): List<PaymentTerminalConfig> =
        if (terminalsById.isNotEmpty()) terminalsById.values.toList()
        else legacyById.values.toList()

    /**
     * Parses a [payment_terminals] document for screens that list or edit
     * terminals (same shape as the web dashboard).
     */
    fun parsePaymentTerminalDocument(doc: DocumentSnapshot): PaymentTerminalConfig? =
        parseNew(doc)

    fun setActiveTerminalId(context: Context, id: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (id.isNullOrBlank()) prefs.remove(KEY_ACTIVE_TERMINAL_ID)
        else prefs.putString(KEY_ACTIVE_TERMINAL_ID, id)
        prefs.apply()
    }

    fun getActiveTerminalId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_TERMINAL_ID, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * Active terminal for this device: the pinned row if it exists and [PaymentTerminalConfig.active]
     * is true, otherwise the first Firestore row with `active: true`.
     *
     * If the dashboard disables the pinned terminal (or every terminal), this returns **null** so
     * the POS does not fall back to a disabled terminal or baked-in credentials.
     */
    fun getActiveConfig(context: Context): PaymentTerminalConfig? {
        val source = if (terminalsById.isNotEmpty()) terminalsById else legacyById
        if (source.isEmpty()) return null
        val pinnedId = getActiveTerminalId(context)
        if (!pinnedId.isNullOrBlank()) {
            val pinned = source[pinnedId]
            if (pinned != null) {
                return if (pinned.active) pinned else null
            }
        }
        return source.values.firstOrNull { it.active }
    }

    /**
     * Firestore collection that owns [configId] for reachability patches (`payment_terminals`
     * when the new collection is in use, otherwise legacy `Terminals`).
     */
    fun firestoreCollectionForConfigId(configId: String): String? {
        if (terminalsById.isNotEmpty()) {
            return if (terminalsById.containsKey(configId)) COLLECTION_PAYMENT_TERMINALS else null
        }
        if (legacyById.isNotEmpty()) {
            return if (legacyById.containsKey(configId)) COLLECTION_LEGACY_TERMINALS else null
        }
        return null
    }

    /**
     * Dashboard uses boolean `active`; tolerate string/number from imports or older writes.
     * Missing field defaults to enabled so existing docs keep working.
     */
    private fun readActiveFlag(doc: DocumentSnapshot): Boolean {
        if (!doc.contains("active")) return true
        return when (val v = doc.get("active")) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> true
            }
            is Number -> v.toDouble() != 0.0
            else -> true
        }
    }

    private fun parseNew(doc: DocumentSnapshot): PaymentTerminalConfig? {
        if (!doc.exists()) return null
        val baseUrl = doc.getString("baseUrl").orEmpty()
        val provider = doc.getString("provider").orEmpty()

        @Suppress("UNCHECKED_CAST")
        val endpoints = (doc.get("endpoints") as? Map<String, Any?>).orEmpty()
            .mapValues { (_, v) -> v?.toString().orEmpty() }
        @Suppress("UNCHECKED_CAST")
        val capabilities = (doc.get("capabilities") as? Map<String, Any?>).orEmpty()
            .mapValues { (_, v) -> (v as? Boolean) ?: false }
        @Suppress("UNCHECKED_CAST")
        val config = (doc.get("config") as? Map<String, Any?>).orEmpty()
            .mapValues { (_, v) -> v?.toString().orEmpty() }

        return PaymentTerminalConfig(
            id = doc.id,
            name = doc.getString("name") ?: doc.id,
            provider = provider.ifBlank { PaymentTerminalConfig.PROVIDER_SPIN_Z },
            deviceModel = doc.getString("deviceModel").orEmpty(),
            baseUrl = baseUrl,
            endpoints = endpoints,
            capabilities = capabilities,
            config = config,
            active = readActiveFlag(doc),
        )
    }

    /** Legacy shape: `Terminals/{id}` only had `name`, `tpn`, `registerId`, `authKey`, `active`. */
    private fun parseLegacy(doc: DocumentSnapshot): PaymentTerminalConfig? {
        if (!doc.exists()) return null
        val tpn = doc.getString("tpn").orEmpty()
        val registerId = doc.getString("registerId").orEmpty()
        val authKey = doc.getString("authKey").orEmpty()
        if (tpn.isBlank() && registerId.isBlank() && authKey.isBlank()) return null

        return PaymentTerminalConfig(
            id = doc.id,
            name = doc.getString("name") ?: doc.id,
            provider = PaymentTerminalConfig.PROVIDER_SPIN,
            deviceModel = doc.getString("deviceModel").orEmpty(),
            baseUrl = SpinDefaults.BASE_URL,
            endpoints = SpinDefaults.ENDPOINTS,
            capabilities = SpinDefaults.CAPABILITIES,
            config = mapOf(
                PaymentTerminalConfig.Companion.ConfigKey.TPN to tpn,
                PaymentTerminalConfig.Companion.ConfigKey.REGISTER_ID to registerId,
                PaymentTerminalConfig.Companion.ConfigKey.AUTH_KEY to authKey,
            ),
            active = readActiveFlag(doc),
        )
    }
}
