package com.ernesto.myapplication

import android.content.Context
import android.os.Build
import android.util.Log
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarDeviceDiscoveryManager
import com.starmicronics.stario10.StarDeviceDiscoveryManagerFactory
import com.starmicronics.stario10.StarPrinter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object StarPrinterDiscovery {

    private const val TAG = "StarPrinterDiscovery"
    private const val DISCOVERY_TIME_MS = 8_000

    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun discoverLan(context: Context): List<DetectedPrinter> {
        if (!isAvailable) return emptyList()

        return try {
            discoverLanInternal(context)
        } catch (e: Exception) {
            Log.w(TAG, "Star SDK discovery failed, falling back", e)
            emptyList()
        }
    }

    private suspend fun discoverLanInternal(context: Context): List<DetectedPrinter> =
        suspendCancellableCoroutine { cont ->
            val found = mutableListOf<DetectedPrinter>()
            try {
                val manager = StarDeviceDiscoveryManagerFactory.create(
                    listOf(InterfaceType.Lan),
                    context.applicationContext,
                )
                manager.discoveryTime = DISCOVERY_TIME_MS

                manager.callback = object : StarDeviceDiscoveryManager.Callback {
                    override fun onPrinterFound(printer: StarPrinter) {
                        val identifier = printer.connectionSettings.identifier
                        val ip = extractIpFromIdentifier(identifier) ?: return
                        val model = extractModelFromIdentifier(identifier)

                        found.add(
                            DetectedPrinter(
                                ipAddress = ip,
                                port = 9100,
                                isReachable = true,
                                name = model,
                                model = model,
                                manufacturer = "Star Micronics",
                            ),
                        )
                        Log.d(TAG, "Star printer found: $identifier")
                    }

                    override fun onDiscoveryFinished() {
                        Log.d(TAG, "Star discovery finished, found ${found.size}")
                        if (cont.isActive) cont.resume(found.toList())
                    }
                }

                cont.invokeOnCancellation {
                    try {
                        manager.stopDiscovery()
                    } catch (_: Exception) {
                    }
                }

                manager.startDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "Star SDK create/start failed", e)
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    private fun extractIpFromIdentifier(identifier: String): String? {
        val ipRegex = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
        return ipRegex.find(identifier)?.value
    }

    private fun extractModelFromIdentifier(identifier: String): String? {
        val parts = identifier.split(" ", "_", "-")
        if (parts.size < 2) return null
        val modelParts = parts.dropLast(1)
        val candidate = modelParts.joinToString(" ").trim()
        return candidate.takeIf { it.isNotBlank() }
    }
}
