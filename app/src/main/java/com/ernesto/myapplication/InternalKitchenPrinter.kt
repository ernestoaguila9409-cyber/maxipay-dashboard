package com.ernesto.myapplication

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context

/**
 * Kitchen printer slot that targets the Landi C20 Pro built-in thermal printer over Bluetooth SPP
 * (same transport as [EscPosPrinter] receipt printing), not the LAN scan list.
 *
 * Persisted in [SelectedPrinterDisplay.ipAddress] as [ADDRESS_KEY] — not a real IPv4 address.
 */
object InternalKitchenPrinter {

    /** Stable prefs / routing key; must not be a valid LAN IP. */
    const val ADDRESS_KEY: String = "_LANDI_INTERNAL_KITCHEN_"

    fun isInternalAddress(ip: String?): Boolean =
        ADDRESS_KEY.equals(ip?.trim(), ignoreCase = false)

    /**
     * True when the device can attempt Bluetooth SPP to the built-in printer (adapter on).
     * Does not verify pairing; actual success is determined on print.
     */
    @SuppressLint("MissingPermission")
    fun isBluetoothPathAvailable(): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            adapter.isEnabled
        } catch (_: Exception) {
            false
        }
    }

    fun displayConnectionLine(context: Context): String =
        context.getString(R.string.printer_connection_internal)

    fun modelLine(context: Context): String =
        context.getString(R.string.internal_kitchen_printer_model_line)

    fun defaultDisplayName(context: Context): String =
        context.getString(R.string.internal_kitchen_printer_default_name)
}
