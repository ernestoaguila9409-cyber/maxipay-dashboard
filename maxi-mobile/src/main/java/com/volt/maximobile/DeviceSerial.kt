package com.volt.maximobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build

/**
 * Best-effort hardware serial for dashboard display. Many builds return [android.os.Build.UNKNOWN]
 * or throw without [android.Manifest.permission.READ_PHONE_STATE]; callers should treat empty as
 * "not available".
 */
object DeviceSerial {

    private val UNKNOWN = setOf("", Build.UNKNOWN, "unknown", "UNKNOWN", "null")

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getBestEffort(@Suppress("UNUSED_PARAMETER") context: Context): String {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Build.getSerial()
            } catch (_: SecurityException) {
                ""
            }
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
        val serial = raw.trim()
        if (serial.isNotEmpty() && serial !in UNKNOWN) {
            return serial.take(128)
        }
        return ""
    }
}
