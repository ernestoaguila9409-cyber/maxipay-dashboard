package com.ernesto.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Best-effort hardware serial for the dashboard. [Build.getSerial] is often "unknown" for
 * third-party apps on Android 10+; many POS ROMs still expose the value via [ro.serialno].
 */
object DeviceSerial {

    private val UNKNOWN = setOf("", Build.UNKNOWN, "unknown", "UNKNOWN", "null", "[unknown]")

    @Volatile
    private var cachedValue: String? = null

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getBestEffort(@Suppress("UNUSED_PARAMETER") context: Context): String {
        cachedValue?.let { return it }
        val found = resolveSerial()
        if (found.isNotEmpty()) {
            cachedValue = found
            return found
        }
        return ""
    }

    /** Call after runtime permission changes so we retry [Build.getSerial]. */
    fun clearCache() {
        cachedValue = null
    }

    private fun resolveSerial(): String {
        for (candidate in listOf(
            { serialFromSystemProperty() },
            { serialFromGetprop() },
            { serialFromBuild() },
        )) {
            val s = normalize(candidate.invoke())
            if (s.isNotEmpty()) return s.take(128)
        }
        return ""
    }

    private fun normalize(s: String): String {
        val t = s.trim()
        return if (t.isEmpty() || t in UNKNOWN) "" else t
    }

    private fun serialFromSystemProperty(): String {
        val keys = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "ril.serialnumber",
            "sys.serialnumber",
        )
        for (key in keys) {
            val v = normalize(systemPropertyGet(key))
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun systemPropertyGet(key: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun serialFromGetprop(): String {
        val keys = listOf("ro.serialno", "ro.boot.serialno")
        val bins = listOf("/system/bin/getprop", "/vendor/bin/getprop", "getprop")
        for (key in keys) {
            for (bin in bins) {
                val v = normalize(runGetprop(bin, key))
                if (v.isNotEmpty()) return v
            }
            val shell = normalize(runGetpropShell(key))
            if (shell.isNotEmpty()) return shell
        }
        return ""
    }

    private fun runGetpropShell(prop: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/sh", "-c", "getprop $prop")
                .redirectErrorStream(true)
                .start()
            val line = BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                r.readLine()?.trim().orEmpty()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    return ""
                }
            } else {
                process.waitFor()
            }
            line
        } catch (_: Exception) {
            ""
        } finally {
            process?.destroy()
        }
    }

    private fun runGetprop(bin: String, prop: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder(bin, prop).redirectErrorStream(true).start()
            val line = BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                r.readLine()?.trim().orEmpty()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    return ""
                }
            } else {
                process.waitFor()
            }
            line
        } catch (_: Exception) {
            ""
        } finally {
            process?.destroy()
        }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun serialFromBuild(): String {
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
        return raw
    }

    /**
     * Always available on real devices; used when hardware serial cannot be read (common on
     * targetSdk 29+). Not the factory sticker serial but uniquely identifies the install.
     */
    @SuppressLint("HardwareIds")
    fun getStableAndroidId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.trim()
                ?.take(64)
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
