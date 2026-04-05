package com.ernesto.myapplication

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.ernesto.myapplication.engine.MoneyUtils
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object EscPosPrinter {

    private const val TAG = "EscPosPrinter"

    private const val LANDI_BT_ADDRESS = "00:01:02:03:0A:0B"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private const val PRINTER_WIDTH_PX = 384

    private val INIT         = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT   = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON      = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF     = byteArrayOf(0x1B, 0x45, 0x00)
    private val SIZE_NORMAL  = byteArrayOf(0x1D, 0x21, 0x00)
    private val LF           = byteArrayOf(0x0A)
    private val CUT          = byteArrayOf(0x1D, 0x56, 0x00)

    // ── Logo cache ────────────────────────────────────────────────

    private var cachedLogoUrl: String = ""
    private var cachedLogoBitmap: Bitmap? = null

    fun clearLogoCache() {
        cachedLogoBitmap?.recycle()
        cachedLogoBitmap = null
        cachedLogoUrl = ""
    }

    // ── Segment model ─────────────────────────────────────────────

    data class Segment(
        val text: String,
        val bold: Boolean = false,
        val fontSize: Int = 0,
        val centered: Boolean = false
    )

    /**
     * Business name, address, and optional email — each wrapped to thermal line width
     * (same as Receipt Settings preview and [wrapThermalText]).
     */
    fun appendHeaderSegments(
        segs: MutableList<Segment>,
        rs: ReceiptSettings,
        includeEmail: Boolean = true
    ) {
        val bizW = ReceiptSettings.lineWidthForSize(rs.fontSizeBizName)
        for (line in wrapThermalText(rs.businessName, bizW)) {
            segs += Segment(line, bold = rs.boldBizName, fontSize = rs.fontSizeBizName, centered = true)
        }
        val addrW = ReceiptSettings.lineWidthForSize(rs.fontSizeAddress)
        for (line in wrapThermalText(rs.addressText, addrW)) {
            segs += Segment(line, bold = rs.boldAddress, fontSize = rs.fontSizeAddress, centered = true)
        }
        if (includeEmail && rs.showEmail && rs.email.isNotBlank()) {
            for (line in wrapThermalText(rs.email.trim(), LINE_WIDTH)) {
                segs += Segment(line, bold = rs.boldAddress, fontSize = 0, centered = true)
            }
        }
    }

    // ── Cash drawer ────────────────────────────────────────────────

    private val DRAWER_KICK = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())

    @SuppressLint("MissingPermission")
    fun openCashDrawer(context: Context) {
        Log.d(TAG, "Opening cash drawer via Bluetooth printer")
        Thread {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    Log.w(TAG, "Bluetooth not available")
                    return@Thread
                }
                val device = adapter.getRemoteDevice(LANDI_BT_ADDRESS)
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                socket.outputStream.apply {
                    write(DRAWER_KICK)
                    flush()
                }
                Log.d(TAG, "Cash drawer opened successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open cash drawer: ${e.message}", e)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // ── Public API ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun print(context: Context, segments: List<Segment>, settings: ReceiptSettings? = null) {
        Log.d(TAG, "Print requested (${segments.size} segments) → $LANDI_BT_ADDRESS")

        Thread {
            var logoBitmap: Bitmap? = null
            if (settings != null && settings.showLogo && settings.logoUrl.isNotBlank()) {
                logoBitmap = downloadLogo(settings.logoUrl)
            }

            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    uiToast(context, "Bluetooth not available or disabled")
                    return@Thread
                }

                val device = adapter.getRemoteDevice(LANDI_BT_ADDRESS)
                Log.d(TAG, "Device: ${device.name ?: "(null)"}")

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                Log.d(TAG, "Connected")

                val out = socket.outputStream
                out.write(INIT)

                if (logoBitmap != null) {
                    out.write(ALIGN_CENTER)
                    out.write(SIZE_NORMAL)
                    out.write(BOLD_OFF)
                    printBitmap(out, logoBitmap)
                    out.write(LF)
                }

                for (seg in segments) {
                    out.write(if (seg.centered) ALIGN_CENTER else ALIGN_LEFT)
                    out.write(ReceiptSettings.escPosSizeBytes(seg.fontSize))
                    out.write(if (seg.bold) BOLD_ON else BOLD_OFF)
                    out.printLine(seg.text)
                }

                out.write(SIZE_NORMAL)
                out.write(BOLD_OFF)
                out.write(ALIGN_LEFT)
                repeat(4) { out.write(LF) }
                out.write(CUT)
                out.flush()

                Log.d(TAG, "Print complete")
                uiToast(context, "Receipt printed!")
            } catch (e: Exception) {
                Log.e(TAG, "Print failed: ${e.message}", e)
                uiToast(context, "Print failed: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // ── Test receipt ──────────────────────────────────────────────

    fun printTestReceipt(context: Context, settings: ReceiptSettings) {
        val segs = buildTestSegments(settings)
        print(context, segs, settings)
    }

    /**
     * Sends a short ESC/POS test page to a printer on the LAN (raw TCP, usually port 9100).
     * [kitchenPrinter]: skip thermal cut and use line feeds (better for impact/kitchen printers).
     */
    fun printLanTestPrint(
        context: Context,
        ipAddress: String,
        port: Int = 9100,
        kitchenPrinter: Boolean,
    ) {
        Log.d(TAG, "LAN test print → $ipAddress:$port kitchen=$kitchenPrinter")
        Thread {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), 8_000)
                socket.soTimeout = 10_000
                val out = socket.outputStream
                out.write(INIT)
                out.write(ALIGN_CENTER)
                out.write(BOLD_ON)
                out.printLine("TEST PRINT")
                out.write(BOLD_OFF)
                out.write(ALIGN_LEFT)
                out.printLine("")
                val ts = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US).format(Date())
                out.printLine(ts)
                out.printLine("IP: $ipAddress")
                out.printLine("MaxiPay printer test")
                out.printLine("")
                repeat(4) { out.write(LF) }
                if (kitchenPrinter) {
                    out.write(byteArrayOf(0x1B, 0x64, 8))
                } else {
                    out.write(CUT)
                }
                out.flush()
                uiToast(context, context.getString(R.string.printer_test_sent))
            } catch (e: Exception) {
                Log.e(TAG, "LAN test print failed: ${e.message}", e)
                uiToast(
                    context,
                    context.getString(R.string.printer_test_failed, e.message ?: ""),
                )
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    fun buildTestSegments(settings: ReceiptSettings): List<Segment> {
        val rs = settings
        val segs = mutableListOf<Segment>()

        val bo = rs.boldOrderInfo;    val fo = rs.fontSizeOrderInfo
        val bi = rs.boldItems;        val fi = rs.fontSizeItems
        val bt = rs.boldTotals;       val ft = rs.fontSizeTotals
        val bg = rs.boldGrandTotal;   val fg = rs.fontSizeGrandTotal
        val bf = rs.boldFooter;       val ff = rs.fontSizeFooter

        val lwi = ReceiptSettings.lineWidthForSize(fi)
        val lwt = ReceiptSettings.lineWidthForSize(ft)
        val lwg = ReceiptSettings.lineWidthForSize(fg)

        appendHeaderSegments(segs, rs)

        segs += Segment("")

        segs += Segment("RECEIPT", bold = bo, fontSize = fo, centered = true)
        segs += Segment("", fontSize = fo, centered = true)
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += Segment("Order #1042", bold = bo, fontSize = fo, centered = true)
        segs += Segment("Type: Dine In", bold = bo, fontSize = fo, centered = true)
        if (rs.showServerName) segs += Segment("Server: Ernesto", bold = bo, fontSize = fo, centered = true)
        if (rs.showDateTime) segs += Segment("Date: $dateStr", bold = bo, fontSize = fo, centered = true)
        segs += Segment("")

        segs += Segment("-".repeat(lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("2x Burger", "$19.98", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("  + Extra Cheese", "$1.50", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("1x Caesar Salad", "$12.50", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("1x Fries", "$5.99", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("2x Iced Tea", "$7.98", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("1x Chocolate Cake", "$8.50", lwi), bold = bi, fontSize = fi)
        segs += Segment("-".repeat(lwi), bold = bi, fontSize = fi)
        segs += Segment("")

        segs += Segment(formatLine("Subtotal", "$56.45", lwt), bold = bt, fontSize = ft)
        segs += Segment(formatLine("Tax (8.25%)", "$4.66", lwt), bold = bt, fontSize = ft)
        segs += Segment(formatLine("Tip", "$8.47", lwt), bold = bt, fontSize = ft)
        segs += Segment("=".repeat(lwt), bold = bt, fontSize = ft)

        segs += Segment(formatLine("TOTAL", "$69.58", lwg), bold = bg, fontSize = fg)
        segs += Segment("")

        segs += Segment("Visa **** 1234", bold = bf, fontSize = ff, centered = true)
        segs += Segment("Auth: 123456", bold = bf, fontSize = ff, centered = true)
        segs += Segment("Type: Credit", bold = bf, fontSize = ff, centered = true)
        segs += Segment("")

        segs += Segment("Thank you for dining with us!", bold = bf, fontSize = ff, centered = true)

        return segs
    }

    // ── Logo download + cache ─────────────────────────────────────

    private fun downloadLogo(url: String): Bitmap? {
        if (url == cachedLogoUrl && cachedLogoBitmap != null) {
            Log.d(TAG, "Using cached logo bitmap")
            return cachedLogoBitmap
        }
        return try {
            Log.d(TAG, "Downloading logo: $url")
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes() ?: return null
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val maxW = PRINTER_WIDTH_PX / 2
            val scaled = if (original.width > maxW) {
                val ratio = maxW.toFloat() / original.width
                val newH = (original.height * ratio).toInt()
                Bitmap.createScaledBitmap(original, maxW, newH, true).also {
                    if (it !== original) original.recycle()
                }
            } else {
                original
            }

            cachedLogoBitmap?.recycle()
            cachedLogoUrl = url
            cachedLogoBitmap = scaled
            Log.d(TAG, "Logo ready: ${scaled.width}x${scaled.height}")
            scaled
        } catch (e: Exception) {
            Log.e(TAG, "Logo download failed: ${e.message}", e)
            null
        }
    }

    // ── ESC/POS raster image (GS v 0) ────────────────────────────

    private fun printBitmap(out: OutputStream, bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (bytesPerRow and 0xFF).toByte(),
            ((bytesPerRow shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(),
            ((height shr 8) and 0xFF).toByte()
        )
        out.write(header)

        val rowBuf = ByteArray(bytesPerRow)
        for (y in 0 until height) {
            rowBuf.fill(0)
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val a = Color.alpha(pixel)
                if (a < 128) continue
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (luminance < 128) {
                    rowBuf[x / 8] = (rowBuf[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
            out.write(rowBuf)
        }
        out.flush()
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun OutputStream.printLine(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
        write(LF)
    }

    private fun uiToast(context: Context, msg: String) {
        if (context is android.app.Activity) {
            context.runOnUiThread {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Segments printed after the grand TOTAL line for credit card sales: suggested tip guide (no tip yet)
 * or a Tip + Total summary after tip adjust. Uses [TipConfig] for calculation base, [TipConfig.getPresets]
 * for percentage rows, and [TipConfig.isCustomTipEnabled] for the Custom Tip line.
 */
fun buildCreditTipReceiptFollowUpSegments(
    context: Context,
    rs: ReceiptSettings,
    subtotalCents: Long,
    taxTotalCents: Long,
    orderTotalInCents: Long,
    tipAmountInCents: Long,
    payments: List<Map<String, Any>>,
    transactionStatus: String?,
    transactionVoided: Boolean
): List<EscPosPrinter.Segment> {
    if (!TipConfig.isTipsEnabled(context) || transactionVoided) return emptyList()
    val hasCredit = payments.any { p ->
        p["paymentType"]?.toString()?.equals("Credit", ignoreCase = true) == true
    }
    if (!hasCredit) return emptyList()

    val lwt = ReceiptSettings.lineWidthForSize(rs.fontSizeTotals)
    val lwg = ReceiptSettings.lineWidthForSize(rs.fontSizeGrandTotal)

    fun totalSeg(left: String, right: String) = EscPosPrinter.Segment(
        formatLine(left, right, lwt),
        bold = rs.boldTotals,
        fontSize = rs.fontSizeTotals
    )

    fun grandSeg(left: String, right: String) = EscPosPrinter.Segment(
        formatLine(left, right, lwg),
        bold = rs.boldGrandTotal,
        fontSize = rs.fontSizeGrandTotal
    )

    val out = mutableListOf<EscPosPrinter.Segment>()

    if (tipAmountInCents > 0L) {
        out += totalSeg("Tip:", MoneyUtils.centsToDisplay(tipAmountInCents))
        out += grandSeg("Total:", MoneyUtils.centsToDisplay(orderTotalInCents))
        out += EscPosPrinter.Segment("")
        return out
    }

    val st = transactionStatus?.trim()?.uppercase(Locale.US).orEmpty()
    // Firestore sale docs use COMPLETED; APPROVED matches gateway-style payloads.
    if (st != "APPROVED" && st != "COMPLETED") return emptyList()

    val presets = TipConfig.getPresets(context)
    val customTipEnabled = TipConfig.isCustomTipEnabled(context)

    val subtotal = MoneyUtils.centsToDouble(subtotalCents)
    val tax = MoneyUtils.centsToDouble(taxTotalCents)
    fun tipAmountDisplay(percent: Double): String {
        val dollars = TipConfig.calculateTip(subtotal, tax, percent, context)
        return MoneyUtils.centsToDisplay(MoneyUtils.dollarsToCents(dollars))
    }

    val writeInSuffix = "   ______"
    if (presets.isNotEmpty() || customTipEnabled) {
        out += EscPosPrinter.Segment("Tip Guide:", bold = rs.boldTotals, fontSize = rs.fontSizeTotals)
        out += EscPosPrinter.Segment("")
    }
    for (pct in presets) {
        val label = "$pct%"
        val right = tipAmountDisplay(pct.toDouble()) + writeInSuffix
        out += totalSeg(label, right)
        out += EscPosPrinter.Segment("")
    }
    if (customTipEnabled) {
        out += totalSeg("Custom Tip:", "__________")
        out += EscPosPrinter.Segment("")
    }
    out += totalSeg("TOTAL:", "__________")
    out += EscPosPrinter.Segment("")
    val sigRight = "_".repeat((lwt - "Signature:".length - 1).coerceIn(8, 20))
    out += totalSeg("Signature:", sigRight)
    out += EscPosPrinter.Segment("")
    return out
}
