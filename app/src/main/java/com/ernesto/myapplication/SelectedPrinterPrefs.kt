package com.ernesto.myapplication

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class SelectedPrinterDisplay(
    val name: String,
    val ipAddress: String,
    val modelLine: String,
    val isOnline: Boolean = false,
    /** Kitchen routing labels (exact display strings; compare with [PrinterLabelKey.normalize]). */
    val labels: List<String> = emptyList(),
)

object PrinterLabelKey {
    fun normalize(raw: String?): String = raw?.trim()?.lowercase().orEmpty()
}

object SelectedPrinterPrefs {
    private const val PREFS_NAME = "selected_printer_display"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun legacyKey(type: PrinterDeviceType, suffix: String) = "${type.name}_$suffix"

    private fun printersJsonKey(type: PrinterDeviceType) = "${type.name}_printers_json"

    /**
     * All LAN printers saved for this role. New printers are appended; same IP updates the entry.
     */
    fun getAll(context: Context, type: PrinterDeviceType): List<SelectedPrinterDisplay> {
        val p = prefs(context)
        val json = p.getString(printersJsonKey(type), null)
        if (!json.isNullOrBlank()) {
            return parsePrintersJson(json)
        }
        return migrateLegacySinglePrinter(context, type, p)
    }

    /** First saved receipt printer, or null — prefer [getAll] when multiple are configured. */
    fun get(context: Context, type: PrinterDeviceType): SelectedPrinterDisplay? =
        getAll(context, type).firstOrNull()

    /**
     * Adds or updates by IP so additional printers of the same type do not replace existing ones.
     */
    /**
     * @param labels If null when updating an existing printer by IP, existing labels are kept.
     * If null for a new printer, starts with no labels.
     */
    fun add(
        context: Context,
        type: PrinterDeviceType,
        name: String,
        ipAddress: String,
        modelLine: String,
        labels: List<String>? = null,
    ) {
        val ip = ipAddress.trim()
        if (ip.isEmpty()) return
        val current = getAll(context, type).toMutableList()
        val idx = current.indexOfFirst { it.ipAddress.trim() == ip }
        val keepLabels = when {
            labels != null -> dedupeLabels(labels)
            idx >= 0 -> current[idx].labels
            else -> emptyList()
        }
        val entry = SelectedPrinterDisplay(name.trim(), ip, modelLine.trim(), labels = keepLabels)
        if (idx >= 0) {
            current[idx] = entry
        } else {
            current.add(entry)
        }
        persistPrintersJson(context, type, current)
    }

    /**
     * Removes this IP from receipt and kitchen lists (e.g. deleted on dashboard or POS).
     * @param broadcastChange When false, skips [ACTION_PRINTERS_PREFS_CHANGED] (e.g. detail screen already finishing).
     */
    fun removePrinterByIp(context: Context, ipAddress: String, broadcastChange: Boolean = true) {
        val ip = ipAddress.trim()
        if (ip.isEmpty()) return
        var removedAny = false
        for (type in PrinterDeviceType.values()) {
            val cur = getAll(context, type).toMutableList()
            if (cur.removeAll { it.ipAddress.trim() == ip }) {
                persistPrintersJson(context, type, cur)
                removedAny = true
            }
        }
        if (removedAny) {
            if (broadcastChange) {
                sendPrintersPrefsChangedBroadcast(context.applicationContext, ip)
            }
            MenuItemRoutingLabelCleanup.syncMenuItemLabelsToSavedPrinters(context.applicationContext)
        }
    }

    private fun sendPrintersPrefsChangedBroadcast(appContext: Context, removedIp: String) {
        val intent = Intent(ACTION_PRINTERS_PREFS_CHANGED).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_REMOVED_PRINTER_IP, removedIp)
        }
        appContext.sendBroadcast(intent)
    }

    /** General broadcast for additions/updates from cloud sync (no removed IP). */
    fun sendPrintersChangedBroadcast(context: Context) {
        val intent = Intent(ACTION_PRINTERS_PREFS_CHANGED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    /** Sent when printers change (add, update, remove); [EXTRA_REMOVED_PRINTER_IP] is set only for removals. */
    const val ACTION_PRINTERS_PREFS_CHANGED = "com.ernesto.myapplication.action.PRINTERS_PREFS_CHANGED"
    const val EXTRA_REMOVED_PRINTER_IP = "removedPrinterIp"

    fun updateLabelsForIp(
        context: Context,
        type: PrinterDeviceType,
        ipAddress: String,
        labels: List<String>,
    ) {
        val ip = ipAddress.trim()
        if (ip.isEmpty()) return
        val current = getAll(context, type).toMutableList()
        val idx = current.indexOfFirst { it.ipAddress.trim() == ip }
        if (idx < 0) return
        val p = current[idx]
        current[idx] = p.copy(labels = dedupeLabels(labels))
        persistPrintersJson(context, type, current)
    }

    /** Distinct kitchen label names from all saved kitchen printers (for menu item picker). */
    fun allKitchenLabelOptions(context: Context): List<String> {
        val seen = linkedSetOf<String>()
        for (p in getAll(context, PrinterDeviceType.KITCHEN)) {
            for (l in p.labels) {
                val t = l.trim()
                if (t.isNotEmpty()) seen.add(t)
            }
        }
        return seen.sortedBy { it.lowercase() }
    }

    /**
     * Labels from all saved **kitchen** and **receipt** LAN printers (any device with routing labels).
     * Kitchen stations misconfigured as "receipt" still contribute labels (e.g. "burgers") so item pickers match printers.
     */
    fun allRoutingLabelsFromSavedPrinters(context: Context): List<String> {
        val seen = linkedSetOf<String>()
        for (type in listOf(PrinterDeviceType.KITCHEN, PrinterDeviceType.RECEIPT)) {
            for (p in getAll(context, type)) {
                for (l in p.labels) {
                    val t = l.trim()
                    if (t.isNotEmpty()) seen.add(t)
                }
            }
        }
        return seen.sortedBy { it.lowercase() }
    }

    /** @see add */
    fun save(
        context: Context,
        type: PrinterDeviceType,
        name: String,
        ipAddress: String,
        modelLine: String,
    ) {
        add(context, type, name, ipAddress, modelLine)
    }

    private fun migrateLegacySinglePrinter(
        context: Context,
        type: PrinterDeviceType,
        p: android.content.SharedPreferences,
    ): List<SelectedPrinterDisplay> {
        val name = p.getString(legacyKey(type, "name"), null) ?: return emptyList()
        val ip = p.getString(legacyKey(type, "ip"), null) ?: return emptyList()
        val info = p.getString(legacyKey(type, "info"), "").orEmpty()
        val list = listOf(SelectedPrinterDisplay(name, ip, info, labels = emptyList()))
        persistPrintersJson(context, type, list)
        p.edit()
            .remove(legacyKey(type, "name"))
            .remove(legacyKey(type, "ip"))
            .remove(legacyKey(type, "info"))
            .apply()
        return list
    }

    private fun parsePrintersJson(json: String): List<SelectedPrinterDisplay> {
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<SelectedPrinterDisplay>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                val ip = o.optString("ip", "").trim()
                if (ip.isEmpty()) continue
                val info = o.optString("info", "")
                val labelArr = o.optJSONArray("labels")
                val labels = if (labelArr != null) {
                    (0 until labelArr.length()).mapNotNull { i ->
                        labelArr.optString(i).trim().takeIf { it.isNotEmpty() }
                    }
                } else {
                    emptyList()
                }
                out.add(SelectedPrinterDisplay(name, ip, info, labels = dedupeLabels(labels)))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistPrintersJson(context: Context, type: PrinterDeviceType, list: List<SelectedPrinterDisplay>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("ip", p.ipAddress)
                    put("info", p.modelLine)
                    val la = JSONArray()
                    for (l in p.labels) la.put(l)
                    put("labels", la)
                },
            )
        }
        prefs(context).edit()
            .putString(printersJsonKey(type), arr.toString())
            .apply()
    }

    private fun dedupeLabels(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (s in raw) {
            val t = s.trim()
            if (t.isEmpty()) continue
            val k = PrinterLabelKey.normalize(t)
            if (k.isEmpty() || k in seen) continue
            seen.add(k)
            out.add(t)
        }
        return out
    }
}
