package com.ernesto.myapplication

import android.content.Context

data class SelectedPrinterDisplay(
    val name: String,
    val ipAddress: String,
    val modelLine: String,
)

object SelectedPrinterPrefs {
    private const val PREFS_NAME = "selected_printer_display"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(type: PrinterDeviceType, suffix: String) = "${type.name}_$suffix"

    fun save(
        context: Context,
        type: PrinterDeviceType,
        name: String,
        ipAddress: String,
        modelLine: String,
    ) {
        prefs(context).edit()
            .putString(key(type, "name"), name)
            .putString(key(type, "ip"), ipAddress)
            .putString(key(type, "info"), modelLine)
            .apply()
    }

    fun get(context: Context, type: PrinterDeviceType): SelectedPrinterDisplay? {
        val p = prefs(context)
        val name = p.getString(key(type, "name"), null) ?: return null
        val ip = p.getString(key(type, "ip"), null) ?: return null
        val info = p.getString(key(type, "info"), "").orEmpty()
        return SelectedPrinterDisplay(name, ip, info)
    }
}
