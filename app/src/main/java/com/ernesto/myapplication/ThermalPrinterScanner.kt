package com.ernesto.myapplication

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DetectedPrinter(
    val ipAddress: String,
    val port: Int = 9100,
    val isReachable: Boolean = true,
    val name: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
) {
    val displayLabel: String
        get() {
            if (!manufacturer.isNullOrBlank() && !model.isNullOrBlank())
                return "$manufacturer $model"
            if (!model.isNullOrBlank()) return model
            if (!manufacturer.isNullOrBlank()) return manufacturer
            if (!name.isNullOrBlank()) return name
            return "Unknown Printer"
        }
}

object ThermalPrinterScanner {

    private const val TAG = "PrinterScanner"
    private const val DEFAULT_SUBNET_PREFIX = "10.0.0"
    private const val RAW_PORT = 9100
    private const val TCP_TIMEOUT_MS = 200
    private const val SNMP_TIMEOUT_MS = 500
    private const val HTTP_TIMEOUT_MS = 2000
    private const val ESCPOS_TIMEOUT_MS = 1500
    private const val MAX_PARALLEL = 48
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val printer: DetectedPrinter, val timestamp: Long)

    fun clearCache() = cache.clear()

    suspend fun scanSubnet10_0_0(context: android.content.Context? = null): List<DetectedPrinter> {
        // TCP scan runs FIRST so ESC/POS probes get exclusive access to port 9100.
        // Most receipt printers only accept one TCP connection at a time, and the
        // Star SDK would hold that connection during its 8-second discovery window.
        val tcpResults = scanLastOctetRange(DEFAULT_SUBNET_PREFIX, 1..255)

        // Star SDK supplements for printers the TCP scan couldn't identify
        val starResults = if (context != null) {
            try { StarPrinterDiscovery.discoverLan(context) } catch (_: Exception) { emptyList() }
        } else emptyList()

        // TCP identification is authoritative (ENPC / ESC/POS / SNMP / HTTP).
        // Star SDK can discover non-Star printers on the LAN, so only use its
        // manufacturer/model when our own probes couldn't determine anything.
        val merged = LinkedHashMap<String, DetectedPrinter>()
        for (p in tcpResults) merged[p.ipAddress] = p

        for (star in starResults) {
            val existing = merged[star.ipAddress]
            if (existing == null) {
                merged[star.ipAddress] = star
                continue
            }

            val tcpIdentified = existing.manufacturer != null
                    || existing.model != null || existing.name != null

            if (!tcpIdentified && !star.model.isNullOrBlank() && isKnownStarModel(star.model)) {
                merged[star.ipAddress] = star
            } else if (existing.model == null && !star.model.isNullOrBlank()
                && isKnownStarModel(star.model)
            ) {
                merged[star.ipAddress] = existing.copy(
                    model = star.model,
                    manufacturer = star.manufacturer ?: existing.manufacturer,
                )
            }
        }

        return merged.values.sortedWith(compareBy { it.ipAddress.substringAfterLast('.').toInt() })
    }

    suspend fun scanLastOctetRange(
        subnetPrefix: String,
        lastOctetRange: IntRange,
        port: Int = RAW_PORT,
        tcpTimeoutMs: Int = TCP_TIMEOUT_MS,
        skipIps: Set<String> = emptySet(),
    ): List<DetectedPrinter> = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = Collections.synchronizedList(mutableListOf<DetectedPrinter>())
            val sem = Semaphore(MAX_PARALLEL)
            lastOctetRange.map { last ->
                async {
                    sem.withPermit {
                        val ip = "$subnetPrefix.$last"
                        if (ip in skipIps) return@withPermit

                        val cached = cache[ip]
                        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                            if (cached.printer.isReachable) results.add(cached.printer)
                            return@withPermit
                        }

                        if (!probeTcpPort(ip, port, tcpTimeoutMs)) {
                            cache[ip] = CacheEntry(
                                DetectedPrinter(ip, port, isReachable = false),
                                System.currentTimeMillis(),
                            )
                            return@withPermit
                        }

                        val printer = identifyPrinter(ip, port)
                        cache[ip] = CacheEntry(printer, System.currentTimeMillis())
                        results.add(printer)
                    }
                }
            }.awaitAll()
            results.sortedWith(compareBy { it.ipAddress.substringAfterLast('.').toInt() })
        }
    }

    /**
     * Layered identification: try each strategy until we get manufacturer+model.
     * 1. ENPC (Epson proprietary UDP 3289)
     * 2. ESC/POS GS I command (port 9100)
     * 3. Star Line Mode firmware query (port 9100)
     * 4. HTTP probe (port 80 web interface — also reads 401 pages)
     * 5. SNMP (fallback)
     */
    private fun identifyPrinter(ip: String, port: Int): DetectedPrinter {
        var manufacturer: String? = null
        var model: String? = null
        var name: String? = null

        val enpc = probeEnpc(ip)
        if (enpc != null) {
            manufacturer = "Epson"
            model = enpc
            Log.d(TAG, "$ip → ENPC: Epson $enpc")
        }

        if (model == null) {
            val escpos = probeEscPosIdentity(ip, port)
            if (escpos != null) {
                if (escpos.maker != null) manufacturer = escpos.maker
                if (escpos.model != null) model = escpos.model
                Log.d(TAG, "$ip → ESC/POS: ${escpos.maker} ${escpos.model}")
            }
        }

        // ESC/POS GS I sometimes returns firmware strings like "30.50 ESC/POS" as maker
        // and the bare manufacturer name "EPSON" as model. Clean up firmware junk so later
        // strategies (HTTP, SNMP) still get a chance to fill the real model.
        if (manufacturer != null && looksLikeFirmwareString(manufacturer!!)) {
            val realMfr = extractManufacturerFromFirmwareString(manufacturer!!)
            val realModel = extractModelFromFirmwareString(manufacturer!!)
            manufacturer = realMfr
            if (model == null) model = realModel
        }
        if (model != null && looksLikeFirmwareString(model!!)) {
            if (manufacturer == null) manufacturer = extractManufacturerFromFirmwareString(model!!)
            model = extractModelFromFirmwareString(model!!)
        }
        // When model holds a bare manufacturer name (e.g. "EPSON"), promote it
        if (model != null && manufacturer == null) {
            val mfr = recognizeAsBareManufacturerName(model!!)
            if (mfr != null) { manufacturer = mfr; model = null }
        }

        val alreadyConfidentEpson = manufacturer != null
                && manufacturer!!.lowercase().contains("epson")
        if (!alreadyConfidentEpson) {
            val starResult = probeStarLineMode(ip, port)
            if (starResult != null) {
                manufacturer = starResult.manufacturer
                model = starResult.model ?: model
                Log.d(TAG, "$ip → Star Line Mode: ${starResult.manufacturer} ${starResult.model}")
            }
        }

        if (model == null) {
            val http = probeHttp(ip)
            if (http != null) {
                if (http.manufacturer != null) {
                    if (manufacturer == null) {
                        manufacturer = http.manufacturer
                    } else if (http.manufacturer != manufacturer) {
                        // HTTP web-interface identification is high-confidence (e.g. Epson
                        // web UI title or Server header).  Override a Star Line Mode guess
                        // that may have false-positived on an Epson intelligent printer.
                        Log.d(TAG, "$ip → HTTP manufacturer override: $manufacturer → ${http.manufacturer}")
                        manufacturer = http.manufacturer
                    }
                }
                if (http.model != null) model = http.model
                if (http.fallbackLabel != null && manufacturer == null && model == null && name == null) {
                    name = http.fallbackLabel
                }
                Log.d(TAG, "$ip → HTTP: ${http.manufacturer} ${http.model} fallback=${http.fallbackLabel}")
            }
        }

        val snmp = querySnmpInfo(ip, SNMP_TIMEOUT_MS)
        if (snmp != null) {
            val preferSnmpModel = model == null || shouldPreferSnmpModelOverCurrent(snmp.model, model)
            if (preferSnmpModel) {
                if (!snmp.model.isNullOrBlank()) model = snmp.model
            }
            // SNMP manufacturer: only fill when still unknown. Earlier probes
            // (ENPC, ESC/POS, HTTP) are higher-confidence than SNMP heuristics.
            if (!snmp.manufacturer.isNullOrBlank() && manufacturer == null) {
                manufacturer = snmp.manufacturer
            }
            if (snmp.model != null && model == null) model = snmp.model
            if (name == null && snmp.name != null) name = snmp.name
            Log.d(TAG, "$ip → SNMP merged: $manufacturer $model name=$name")
        }

        if (manufacturer == null && model == null && name == null) {
            val hostHint = tryReverseDnsHostname(ip)
            if (hostHint != null) name = hostHint
        }

        // Model pattern is the strongest signal — override manufacturer if it
        // contradicts the model (e.g. Star Line Mode probe guessed "Star" but
        // HTTP/SNMP later found a TM-T88 model string).
        if (model != null) {
            val epsonModel = EPSON_MODEL_REGEX.containsMatchIn(model!!)
            val starModel = STAR_MODEL_PATTERN.containsMatchIn(model!!)
            if (epsonModel && manufacturer != "Epson") {
                Log.d(TAG, "$ip → sanity override: manufacturer $manufacturer → Epson (model=$model)")
                manufacturer = "Epson"
            } else if (starModel && manufacturer != "Star Micronics") {
                Log.d(TAG, "$ip → sanity override: manufacturer $manufacturer → Star Micronics (model=$model)")
                manufacturer = "Star Micronics"
            }
        }

        return DetectedPrinter(
            ipAddress = ip,
            port = port,
            isReachable = true,
            name = name,
            model = model,
            manufacturer = manufacturer,
        )
    }

    private fun probeTcpPort(ip: String, port: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    // ── Strategy 1: ENPC (Epson Network Printer Control, UDP 3289) ─────

    private const val ENPC_PORT = 3289

    private fun probeEnpc(ip: String): String? {
        val request = byteArrayOf(
            0x45, 0x50, 0x53, 0x4F, 0x4E, // "EPSON"
            0x51,                           // 'Q' = query
            0x03, 0x00, 0x00, 0x00,
            0x10, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )

        var sock: DatagramSocket? = null
        return try {
            val addr = InetAddress.getByName(ip)
            sock = DatagramSocket()
            sock.soTimeout = SNMP_TIMEOUT_MS
            sock.send(DatagramPacket(request, request.size, addr, ENPC_PORT))

            val buf = ByteArray(256)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)

            if (resp.length < 7) return null
            val data = buf.copyOf(resp.length)
            if (data[0] != 0x45.toByte() || data[5] != 0x71.toByte()) return null

            val ascii = String(data, Charsets.US_ASCII)
            val modelRegex = Regex("""(TM-[A-Za-z0-9\-]+)""")
            modelRegex.find(ascii)?.value
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) { }
        }
    }

    // ── Strategy 2: ESC/POS GS I command (port 9100) ───────────────────

    private data class EscPosIdentity(val maker: String?, val model: String?)

    private fun probeEscPosIdentity(ip: String, port: Int): EscPosIdentity? {
        // Epson / many clones: GS I 65 ('A') manufacturer, 66 ('B') model. Others use 66/67 or 49/50.
        probeEscPosIdentityPair(ip, port, 65, 66)?.let { return it }
        probeEscPosIdentityPair(ip, port, 66, 67)?.let { return it }
        probeEscPosIdentityPair(ip, port, 49, 50)?.let { return it }
        return null
    }

    private fun probeEscPosIdentityPair(ip: String, port: Int, nMaker: Int, nModel: Int): EscPosIdentity? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), ESCPOS_TIMEOUT_MS)
            socket.soTimeout = ESCPOS_TIMEOUT_MS
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            out.write(byteArrayOf(0x1D, 0x49, nMaker.toByte()))
            out.flush()
            Thread.sleep(350)
            val makerRaw = readAvailableAscii(inp)
            val maker = cleanGsIResponse(makerRaw)

            out.write(byteArrayOf(0x1D, 0x49, nModel.toByte()))
            out.flush()
            Thread.sleep(350)
            val modelRaw = readAvailableAscii(inp)
            val model = cleanGsIResponse(modelRaw)

            if (maker == null && model == null) null
            else EscPosIdentity(maker, model)
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun readAvailableAscii(inp: java.io.InputStream): String? {
        return try {
            val acc = StringBuilder()
            var waited = 0
            while (waited < 600) {
                val avail = inp.available()
                if (avail > 0) {
                    val buf = ByteArray(avail.coerceAtMost(160))
                    val read = inp.read(buf)
                    if (read > 0) acc.append(String(buf, 0, read, Charsets.US_ASCII))
                    waited = 0
                } else {
                    if (acc.isNotEmpty()) break
                    Thread.sleep(50)
                    waited += 50
                }
            }
            acc.toString().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanGsIResponse(raw: String?): String? {
        if (raw == null) return null
        val printable = raw
            .filter { it.code in 0x20..0x7E }
            .replace(Regex("""^[_\s]+"""), "")
            .replace(Regex("""[_\s]+$"""), "")
            .trim()
        if (printable.length < 2) return null
        return printable
    }

    // ── Firmware-string cleanup helpers ─────────────────────────────────

    private fun looksLikeFirmwareString(s: String): Boolean {
        val t = s.trim()
        if (t.contains("ESC/POS", ignoreCase = true)) return true
        if (t.matches(Regex("""^\d+\.\d+\s.*"""))) return true
        if (t.matches(Regex("""^\d+(\.\d+)+$"""))) return true
        return false
    }

    private fun extractManufacturerFromFirmwareString(s: String): String? {
        val lower = s.lowercase()
        if (lower.contains("epson")) return "Epson"
        if (lower.contains("star")) return "Star Micronics"
        if (lower.contains("bixolon")) return "Bixolon"
        if (lower.contains("citizen")) return "Citizen"
        if (lower.contains("zebra")) return "Zebra"
        if (lower.contains("rongta")) return "Rongta"
        if (lower.contains("xprinter")) return "Xprinter"
        if (lower.contains("sunmi")) return "Sunmi"
        return null
    }

    private fun extractModelFromFirmwareString(s: String): String? {
        Regex("""(TM-[A-Za-z0-9\-]+)""", RegexOption.IGNORE_CASE).find(s)?.let { return it.value }
        Regex(
            """(SP\s?7\d{2}|TSP\d{3,4}[A-Z]*|mC-Print\d?|mPOP|BSC\d+|SM-[A-Z0-9]+|SK[15]-\d+)""",
            RegexOption.IGNORE_CASE,
        ).find(s)?.let { return it.value }
        return null
    }

    private val BARE_MANUFACTURER_NAMES = mapOf(
        "epson" to "Epson", "seiko epson" to "Epson", "seiko epson corp" to "Epson",
        "star micronics" to "Star Micronics", "star" to "Star Micronics",
        "bixolon" to "Bixolon", "citizen" to "Citizen", "zebra" to "Zebra",
        "brother" to "Brother", "rongta" to "Rongta", "xprinter" to "Xprinter",
        "sunmi" to "Sunmi", "gprinter" to "Gprinter", "custom" to "Custom",
    )

    private fun recognizeAsBareManufacturerName(s: String): String? =
        BARE_MANUFACTURER_NAMES[s.trim().lowercase()]

    private val EPSON_MODEL_REGEX = Regex(
        """TM-[A-Za-z0-9\-]+""",
        RegexOption.IGNORE_CASE,
    )

    private val STAR_MODEL_PATTERN = Regex(
        """(SP\s?7\d{2}|SP742|SP712|SP747|TSP\d{3,4}[A-Z]*|mC-Print\d?|mPOP|BSC\d+|SM-[A-Z0-9]+|SK[15]-\d+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun isKnownStarModel(model: String?): Boolean =
        model != null && STAR_MODEL_PATTERN.containsMatchIn(model)

    // ── Strategy 3: Star Line Mode firmware query (port 9100) ──────────

    private data class StarProbeResult(val manufacturer: String, val model: String?)

    private fun probeStarLineMode(ip: String, port: Int): StarProbeResult? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), ESCPOS_TIMEOUT_MS)
            socket.soTimeout = ESCPOS_TIMEOUT_MS
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            out.write(byteArrayOf(0x1B, 0x23))
            out.flush()
            Thread.sleep(400)
            val fw = readAvailableBytes(inp)
            if (fw != null) {
                val ascii = String(fw, Charsets.US_ASCII)
                    .filter { it.code in 0x20..0x7E }
                    .trim()
                Log.d(TAG, "$ip Star FW response: [$ascii] raw=${fw.joinToString(" ") { "%02X".format(it) }}")

                val starModel = parseStarFirmwareForModel(ascii)
                if (starModel != null) return StarProbeResult("Star Micronics", starModel)

                // Status-byte pattern: first byte is 0x23 echo and ALL remaining
                // bytes are non-printable (< 0x20 or > 0x7F).  A mix of printable
                // ASCII and control codes typically means an Epson or other
                // ESC/POS printer echoed the '#' and appended its own response.
                if (fw.size in 3..16 && fw[0] == 0x23.toByte()) {
                    val tail = fw.drop(1)
                    val allNonPrintable = tail.all { b ->
                        val v = b.toInt() and 0xFF
                        v < 0x20 || v > 0x7F
                    }
                    val hasHighBytes = tail.any { it.toInt() and 0xFF > 0x7F }
                    if (allNonPrintable && hasHighBytes) {
                        Log.d(TAG, "$ip confirmed Star Line Mode via status bytes")
                        val model = inferStarModelFromStatus(fw)
                        return StarProbeResult("Star Micronics", model)
                    }
                }
            }

            out.write(byteArrayOf(0x05))
            out.flush()
            Thread.sleep(300)
            val enq = readAvailableBytes(inp)
            if (enq != null && enq.size >= 2) {
                val enqAscii = String(enq, Charsets.US_ASCII)
                    .filter { it.code in 0x20..0x7E }
                    .trim()
                Log.d(TAG, "$ip Star ENQ response: [$enqAscii] raw=${enq.joinToString(" ") { "%02X".format(it) }}")
                val m = parseStarFirmwareForModel(enqAscii)
                if (m != null) return StarProbeResult("Star Micronics", m)
            }

            null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    /**
     * The SP700 series returns ~10 status bytes after ESC #. Other Star
     * impact models (SP500, SP298) return a different byte count or pattern.
     * TSP series (thermal) typically returns an ASCII firmware string instead.
     */
    private fun inferStarModelFromStatus(raw: ByteArray): String? {
        val statusLen = raw.size - 1
        // SP700 series returns exactly 10 status bytes after the 0x23 echo
        if (statusLen == 10) return "SP700"
        return null
    }

    private fun readAvailableBytes(inp: java.io.InputStream): ByteArray? {
        return try {
            val avail = inp.available()
            if (avail <= 0) {
                Thread.sleep(200)
                val avail2 = inp.available()
                if (avail2 <= 0) return null
                val buf = ByteArray(avail2.coerceAtMost(256))
                val read = inp.read(buf)
                if (read <= 0) null else buf.copyOf(read)
            } else {
                val buf = ByteArray(avail.coerceAtMost(256))
                val read = inp.read(buf)
                if (read <= 0) null else buf.copyOf(read)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStarFirmwareForModel(text: String): String? {
        val starModelRegex = Regex(
            """(SP\s?7\d{2}|SP742|SP712|SP747|TSP\d{3,4}[A-Z]*|mC-Print\d?|mPOP|BSC\d+|SM-[A-Z0-9]+|SK[15]-\d+)""",
            RegexOption.IGNORE_CASE,
        )
        return starModelRegex.find(text)?.value
    }

    // ── Strategy 4: HTTP probe (port 80 web interface) ─────────────────

    private data class HttpPrinterInfo(
        val manufacturer: String?,
        val model: String?,
        /** When vendor/model cannot be parsed, still show e.g. web UI title or "EPSON TM-T88" from HTML */
        val fallbackLabel: String? = null,
    )

    private fun probeHttp(ip: String): HttpPrinterInfo? {
        return try {
            val url = URL("http://$ip/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MaxiPay-PrinterScan/1.0")

            try {
                val code = conn.responseCode
                val server = conn.getHeaderField("Server").orEmpty()
                val wwwAuth = conn.getHeaderField("WWW-Authenticate").orEmpty()
                val allHeaders = buildString {
                    for (i in 0..20) {
                        val key = conn.getHeaderFieldKey(i) ?: continue
                        val value = conn.getHeaderField(i) ?: continue
                        append("$key: $value\n")
                    }
                }

                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val body = try {
                    if (stream != null) {
                        val reader = BufferedReader(InputStreamReader(stream))
                        val sb = StringBuilder()
                        var line: String?
                        var chars = 0
                        while (reader.readLine().also { line = it } != null && chars < 8000) {
                            sb.appendLine(line)
                            chars += line!!.length
                        }
                        reader.close()
                        sb.toString()
                    } else ""
                } catch (_: Exception) { "" }

                Log.d(TAG, "$ip HTTP $code server=[$server] auth=[$wwwAuth] body=${body.take(200)}")
                parseHttpForPrinter(server, body, wwwAuth, allHeaders, code)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHttpForPrinter(
        server: String, body: String, wwwAuth: String, allHeaders: String,
        httpCode: Int,
    ): HttpPrinterInfo? {
        val combined = "$server $body $wwwAuth $allHeaders"

        val titleRaw = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trim()
        val titleMatch = titleRaw?.let { stripHtmlTagsMinimal(it) }

        val epsonModelRegex = Regex("""(TM-[A-Za-z0-9\-]+[iv]*)""", RegexOption.IGNORE_CASE)
        val starModelRegex = Regex(
            """(SP\s?7\d{2}|SP742|SP712|TSP\d{3,4}[A-Z]*|mC-Print\d?|mPOP|BSC\d+|SM-[A-Z0-9]+|SK[15]-\d+)""",
            RegexOption.IGNORE_CASE,
        )

        val starIdentifiers = listOf(
            "star micronics", "starmicronics", "star_micronics", "webprnt",
            "star ethernet", "ifbd-h", "star printer", "star http",
        )
        val epsonIdentifiers = listOf(
            "epson", "seiko epson", "epos", "tm-t", "tm-u", "tm-m", "tm-l", "tm-h",
        )

        val lowerCombined = combined.lowercase()
        val lowerTitle = titleMatch?.lowercase().orEmpty()

        val realm = Regex("""realm\s*=\s*"([^"]*)"?""", RegexOption.IGNORE_CASE)
            .find(wwwAuth)?.groupValues?.get(1).orEmpty().lowercase()

        val isEpson = epsonIdentifiers.any { lowerCombined.contains(it) || lowerTitle.contains(it) }
        val isStar = starIdentifiers.any {
            lowerCombined.contains(it) || lowerTitle.contains(it) || realm.contains(it)
        } || realm.contains("star") || server.lowercase().contains("star")

        if (isEpson) {
            val model = epsonModelRegex.find(combined)?.value
                ?: epsonModelRegex.find(titleMatch.orEmpty())?.value
            val fb = httpTitleAsFallbackLabel(titleMatch, httpCode)?.takeIf { model == null }
            return HttpPrinterInfo("Epson", model, fb)
        }

        if (isStar) {
            val model = starModelRegex.find(combined)?.value
                ?: starModelRegex.find(titleMatch.orEmpty())?.value
            val fb = httpTitleAsFallbackLabel(titleMatch, httpCode)?.takeIf { model == null }
            return HttpPrinterInfo("Star Micronics", model, fb)
        }

        val modelFromTitle = starModelRegex.find(titleMatch.orEmpty())?.value
            ?: epsonModelRegex.find(titleMatch.orEmpty())?.value
        if (modelFromTitle != null) {
            val mfr = if (epsonModelRegex.matches(modelFromTitle)) "Epson" else "Star Micronics"
            return HttpPrinterInfo(mfr, modelFromTitle)
        }

        if (server.isNotBlank() || body.length > 20) {
            return HttpPrinterInfo(null, null, httpTitleAsFallbackLabel(titleMatch, httpCode))
        }

        return null
    }

    private fun stripHtmlTagsMinimal(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "").replace(Regex("""\s+"""), " ").trim()

    private fun httpTitleAsFallbackLabel(title: String?, httpCode: Int): String? {
        if (title.isNullOrBlank()) return null
        val t = title.replace(Regex("""\s+"""), " ").trim()
        if (t.length < 3 || t.length > 80) return null
        val low = t.lowercase()
        val bannedExact = setOf(
            "401 unauthorized", "403 forbidden", "404 not found", "500 internal server error",
            "error", "log in", "login", "index", "index of", "untitled document",
            "welcome to nginx", "home", "sign in",
        )
        if (low in bannedExact) return null
        if (listOf("401 ", "403 ", "404 ").any { low.startsWith(it) } && low.length < 24) return null
        if (httpCode >= 400 && t.length < 10) return null
        return t
    }

    private fun tryReverseDnsHostname(ip: String): String? {
        return try {
            val ia = InetAddress.getByName(ip)
            val hn = ia.hostName?.trim().orEmpty()
            if (hn.isEmpty() || hn.equals(ip, ignoreCase = true)) return null
            if (hn.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return null
            hn
        } catch (_: Exception) {
            null
        }
    }

    // ── Strategy 4: SNMP ───────────────────────────────────────────────

    /**
     * ESC/POS GS I often returns firmware strings (e.g. `30.50 ESC/POS EPSON`) while SNMP
     * sysDescr carries a stable model (`TM-T88V`). We always query SNMP and upgrade weak labels.
     */
    private fun isLowConfidencePrinterModel(manufacturer: String?, model: String?): Boolean {
        val m = model?.trim().orEmpty()
        if (m.isEmpty()) return true
        val low = m.lowercase()
        if (low.contains("esc/pos")) return true
        if (m.matches(Regex("""^\d+(\.\d+)+\b.*"""))) return true
        val mfr = manufacturer?.lowercase().orEmpty()
        val epsonLike = mfr.contains("epson") || low.contains("epson")
        if (epsonLike && !Regex("""TM-[A-Z0-9\-]+""", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
            return true
        }
        return false
    }

    private fun shouldPreferSnmpModelOverCurrent(snmpModel: String?, currentModel: String?): Boolean {
        if (snmpModel.isNullOrBlank()) return false
        if (currentModel.isNullOrBlank()) return true
        val tm = Regex("""(TM-[A-Z0-9\-]+)""", RegexOption.IGNORE_CASE)
        val snmpTm = tm.find(snmpModel)?.value
        val curTm = tm.find(currentModel)?.value
        if (snmpTm != null && curTm != null && snmpTm.equals(curTm, ignoreCase = true)) return false
        if (snmpTm != null && curTm == null) return true
        if (isLowConfidencePrinterModel(null, currentModel) &&
            !isLowConfidencePrinterModel(null, snmpModel)
        ) {
            return true
        }
        return false
    }

    private data class SnmpPrinterInfo(
        val name: String?,
        val model: String?,
        val manufacturer: String?,
    )

    private fun querySnmpInfo(ip: String, timeoutMs: Int): SnmpPrinterInfo? {
        return try {
            val sysName = snmpGet(ip, timeoutMs, OID_SYS_NAME)
            val sysDescr = snmpGet(ip, timeoutMs, OID_SYS_DESCR)
            val hrDeviceDescr = snmpGet(ip, timeoutMs, OID_HR_DEVICE_DESCR)

            Log.d(TAG, "$ip SNMP raw → sysName=[$sysName] sysDescr=[$sysDescr] hrDeviceDescr=[$hrDeviceDescr]")

            if (sysName == null && sysDescr == null && hrDeviceDescr == null) return null

            val manufacturer = extractManufacturer(sysDescr, sysName, hrDeviceDescr)
            val model = extractModel(sysDescr, sysName, hrDeviceDescr, manufacturer)

            Log.d(TAG, "$ip SNMP parsed → manufacturer=[$manufacturer] model=[$model]")

            SnmpPrinterInfo(
                name = sysName?.takeIf { it.isNotBlank() },
                model = model?.takeIf { it.isNotBlank() },
                manufacturer = manufacturer?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractManufacturer(
        sysDescr: String?, sysName: String?, hrDeviceDescr: String?,
    ): String? {
        val all = listOfNotNull(sysDescr, sysName, hrDeviceDescr).joinToString(" ").lowercase()
        if (all.isBlank()) return null

        val known = listOf(
            "epson" to "Epson", "star micronics" to "Star Micronics",
            "bixolon" to "Bixolon", "citizen" to "Citizen",
            "zebra" to "Zebra", "brother" to "Brother",
            "hewlett-packard" to "HP", "samsung" to "Samsung",
            "toshiba" to "Toshiba", "oki" to "OKI",
            "seiko" to "Seiko", "fujitsu" to "Fujitsu",
            "rongta" to "Rongta", "xprinter" to "Xprinter", "gprinter" to "Gprinter",
            "sunmi" to "Sunmi", "custom" to "Custom", "daruma" to "Daruma",
        )
        val hit = known.firstOrNull { all.contains(it.first) }
        if (hit != null) return hit.second

        if (isLikelyStarPrinter(sysDescr, sysName)) return "Star Micronics"
        return null
    }

    private fun isLikelyStarPrinter(sysDescr: String?, sysName: String?): Boolean {
        val name = sysName?.lowercase().orEmpty()
        val descr = sysDescr?.lowercase().orEmpty()
        val combined = "$descr $name"
        // "Linux armv" alone is NOT Star evidence — Epson intelligent printers
        // (TM-T88V-i, TM-T88VI-i, etc.) also run Linux on ARM.  Only claim Star
        // when SNMP data explicitly mentions Star identifiers.
        return STAR_SNMP_HINTS.any { combined.contains(it) }
    }

    private val STAR_SNMP_HINTS = listOf(
        "star micronics", "starmicronics", "star_micronics",
        "sp700", "sp742", "sp712", "sp747",
        "tsp100", "tsp143", "tsp650", "tsp700", "tsp800",
        "mc-print", "mpop",
    )

    private fun extractModel(
        sysDescr: String?, sysName: String?, hrDeviceDescr: String?, manufacturer: String?,
    ): String? {
        if (!hrDeviceDescr.isNullOrBlank() && hrDeviceDescr.trim().length < 80)
            return hrDeviceDescr.trim()

        val starModelRegex = Regex(
            """(SP\s?7\d{2}|TSP\d{3,4}|mC-Print\d?|mPOP|BSC\d+|SM-[A-Z0-9]+|SK[15]-\d+)""",
            RegexOption.IGNORE_CASE,
        )
        val epsonModelRegex = Regex("""(TM-[A-Z0-9\-]+)""", RegexOption.IGNORE_CASE)

        for (src in listOfNotNull(sysName, sysDescr, hrDeviceDescr)) {
            starModelRegex.find(src)?.let { return it.value }
            epsonModelRegex.find(src)?.let { return it.value }
        }

        if (manufacturer != null && sysDescr != null) {
            val idx = sysDescr.indexOf(manufacturer, ignoreCase = true)
            if (idx >= 0) {
                val token = sysDescr.substring(idx + manufacturer.length).trim()
                    .split(";", ",", "\n", " ").firstOrNull()?.trim()
                if (!token.isNullOrBlank() && token.length < 40) return token
            }
        }
        return null
    }

    // ── Raw SNMP v1 ────────────────────────────────────────────────────

    private val OID_SYS_NAME = intArrayOf(1, 3, 6, 1, 2, 1, 1, 5, 0)
    private val OID_SYS_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 1, 1, 0)
    private val OID_HR_DEVICE_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 25, 3, 2, 1, 3, 1)
    private const val SNMP_PORT = 161

    private fun snmpGet(ip: String, timeoutMs: Int, oid: IntArray): String? {
        val packet = buildSnmpGetRequest(oid)
        val addr = InetAddress.getByName(ip)
        val sendDg = DatagramPacket(packet, packet.size, addr, SNMP_PORT)
        val buf = ByteArray(512)
        val recvDg = DatagramPacket(buf, buf.size)

        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            sock.soTimeout = timeoutMs
            sock.send(sendDg)
            sock.receive(recvDg)
            parseSnmpResponse(buf, recvDg.length)
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) { }
        }
    }

    private fun buildSnmpGetRequest(oid: IntArray): ByteArray {
        val community = "public".toByteArray()
        val oidBytes = encodeOid(oid)
        val nullVal = byteArrayOf(0x05, 0x00)
        val varbind = tlv(0x30, oidBytes + nullVal)
        val varbindList = tlv(0x30, varbind)
        val requestId = tlv(0x02, byteArrayOf(0x01))
        val errorStatus = tlv(0x02, byteArrayOf(0x00))
        val errorIndex = tlv(0x02, byteArrayOf(0x00))
        val pdu = tlv(0xA0.toByte(), requestId + errorStatus + errorIndex + varbindList)
        val version = tlv(0x02, byteArrayOf(0x00))
        val comm = tlv(0x04, community)
        return tlv(0x30, version + comm + pdu)
    }

    private fun parseSnmpResponse(data: ByteArray, length: Int): String? {
        try {
            var pos = 0
            if (pos >= length || data[pos] != 0x30.toByte()) return null
            pos++; val (_, seqEnd) = readLength(data, pos, length); pos = seqEnd
            pos = skipTlv(data, pos, length)
            pos = skipTlv(data, pos, length)
            if (pos >= length) return null
            pos++; val (_, pduEnd) = readLength(data, pos, length); pos = pduEnd
            pos = skipTlv(data, pos, length)
            pos = skipTlv(data, pos, length)
            pos = skipTlv(data, pos, length)
            if (pos >= length || data[pos] != 0x30.toByte()) return null
            pos++; val (_, vblEnd) = readLength(data, pos, length); pos = vblEnd
            if (pos >= length || data[pos] != 0x30.toByte()) return null
            pos++; val (_, vbEnd) = readLength(data, pos, length); pos = vbEnd
            pos = skipTlv(data, pos, length)
            if (pos >= length) return null
            val valueTag = data[pos].toInt() and 0xFF; pos++
            val (valueLen, valueDataStart) = readLength(data, pos, length)
            if (valueTag == 0x04 || valueTag == 0x06)
                return String(data, valueDataStart, valueLen, Charsets.US_ASCII).trim()
            return null
        } catch (_: Exception) { return null }
    }

    private fun encodeOid(oid: IntArray): ByteArray {
        val body = mutableListOf<Byte>()
        if (oid.size < 2) return byteArrayOf(0x06, 0x00)
        body.add((oid[0] * 40 + oid[1]).toByte())
        for (i in 2 until oid.size) {
            val v = oid[i]
            if (v < 128) { body.add(v.toByte()) } else {
                val stack = mutableListOf<Byte>()
                var tmp = v
                stack.add((tmp and 0x7F).toByte()); tmp = tmp shr 7
                while (tmp > 0) { stack.add(((tmp and 0x7F) or 0x80).toByte()); tmp = tmp shr 7 }
                stack.reversed().forEach { body.add(it) }
            }
        }
        return byteArrayOf(0x06, body.size.toByte()) + body.toByteArray()
    }

    private fun tlv(tag: Byte, value: ByteArray): ByteArray =
        byteArrayOf(tag) + encodeAsnLength(value.size) + value

    private fun encodeAsnLength(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        val bytes = mutableListOf<Byte>()
        var tmp = length
        while (tmp > 0) { bytes.add(0, (tmp and 0xFF).toByte()); tmp = tmp shr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun readLength(data: ByteArray, pos: Int, limit: Int): Pair<Int, Int> {
        if (pos >= limit) return 0 to pos + 1
        val b = data[pos].toInt() and 0xFF
        if (b < 128) return b to (pos + 1)
        val numBytes = b and 0x7F
        var len = 0
        for (i in 1..numBytes) {
            if (pos + i >= limit) break
            len = (len shl 8) or (data[pos + i].toInt() and 0xFF)
        }
        return len to (pos + 1 + numBytes)
    }

    private fun skipTlv(data: ByteArray, pos: Int, limit: Int): Int {
        if (pos >= limit) return pos
        val p = pos + 1
        val (len, dataStart) = readLength(data, p, limit)
        return dataStart + len
    }
}
