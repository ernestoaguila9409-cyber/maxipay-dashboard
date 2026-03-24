package com.ernesto.myapplication

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Settings-driven ESC/POS Bluetooth printer targeting the LANDI
 * built-in virtual printer at 00:01:02:03:0A:0B.
 */
object EscPosPrinter {

    private const val TAG = "EscPosPrinter"

    private const val LANDI_BT_ADDRESS = "00:01:02:03:0A:0B"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // ── ESC/POS constants ────────────────────────────────────────

    private val INIT         = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT   = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON      = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF     = byteArrayOf(0x1B, 0x45, 0x00)
    private val SIZE_NORMAL  = byteArrayOf(0x1D, 0x21, 0x00)
    private val LF           = byteArrayOf(0x0A)
    private val CUT          = byteArrayOf(0x1D, 0x56, 0x00)

    // ── Segment model ────────────────────────────────────────────

    data class Segment(
        val text: String,
        val bold: Boolean = false,
        val fontSize: Int = 0,      // 0=Normal, 1=Large, 2=X-Large
        val centered: Boolean = false
    )

    // ── Public API ───────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun print(context: Context, segments: List<Segment>) {
        Log.d(TAG, "Print requested (${segments.size} segments) → $LANDI_BT_ADDRESS")

        Thread {
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

                for (seg in segments) {
                    out.write(if (seg.centered) ALIGN_CENTER else ALIGN_LEFT)
                    out.write(ReceiptSettings.escPosSizeBytes(seg.fontSize))
                    out.write(if (seg.bold) BOLD_ON else BOLD_OFF)
                    out.printLine(seg.text)
                }

                // Reset to normal, feed, cut
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

    // ── Test receipt (sample data, driven by settings) ───────────

    fun printTestReceipt(context: Context, settings: ReceiptSettings) {
        val segs = buildTestSegments(settings)
        print(context, segs)
    }

    fun buildTestSegments(settings: ReceiptSettings): List<Segment> {
        val rs = settings
        val segs = mutableListOf<Segment>()

        val bn = rs.boldBizName;      val fn = rs.fontSizeBizName
        val ba = rs.boldAddress;      val fa = rs.fontSizeAddress
        val bo = rs.boldOrderInfo;    val fo = rs.fontSizeOrderInfo
        val bi = rs.boldItems;        val fi = rs.fontSizeItems
        val bt = rs.boldTotals;       val ft = rs.fontSizeTotals
        val bg = rs.boldGrandTotal;   val fg = rs.fontSizeGrandTotal
        val bf = rs.boldFooter;       val ff = rs.fontSizeFooter

        val lwi = ReceiptSettings.lineWidthForSize(fi)
        val lwt = ReceiptSettings.lineWidthForSize(ft)
        val lwg = ReceiptSettings.lineWidthForSize(fg)

        // Business Name
        segs += Segment(rs.businessName, bold = bn, fontSize = fn, centered = true)

        // Address
        for (line in rs.addressText.split("\n")) {
            segs += Segment(line, bold = ba, fontSize = fa, centered = true)
        }
        segs += Segment("")

        // Order Info (includes RECEIPT label)
        segs += Segment("RECEIPT", bold = bo, fontSize = fo, centered = true)
        segs += Segment("", fontSize = fo, centered = true)
        val dateStr = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(Date())
        segs += Segment("Order #1042", bold = bo, fontSize = fo, centered = true)
        segs += Segment("Type: Dine In", bold = bo, fontSize = fo, centered = true)
        if (rs.showServerName) segs += Segment("Server: Ernesto", bold = bo, fontSize = fo, centered = true)
        if (rs.showDateTime) segs += Segment("Date: $dateStr", bold = bo, fontSize = fo, centered = true)
        segs += Segment("")

        // Items
        segs += Segment("-".repeat(lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("2x Burger", "$19.98", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("  + Extra Cheese", "$1.50", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("1x Caesar Salad", "$12.50", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("1x Fries", "$5.99", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("2x Iced Tea", "$7.98", lwi), bold = bi, fontSize = fi)
        segs += Segment(formatLine("1x Chocolate Cake", "$8.50", lwi), bold = bi, fontSize = fi)
        segs += Segment("-".repeat(lwi), bold = bi, fontSize = fi)
        segs += Segment("")

        // Totals
        segs += Segment(formatLine("Subtotal", "$56.45", lwt), bold = bt, fontSize = ft)
        segs += Segment(formatLine("Tax (8.25%)", "$4.66", lwt), bold = bt, fontSize = ft)
        segs += Segment(formatLine("Tip", "$8.47", lwt), bold = bt, fontSize = ft)
        segs += Segment("=".repeat(lwt), bold = bt, fontSize = ft)

        // Grand total
        segs += Segment(formatLine("TOTAL", "$69.58", lwg), bold = bg, fontSize = fg)
        segs += Segment("")

        // Payment info
        segs += Segment("Visa **** 1234", bold = bf, fontSize = ff, centered = true)
        segs += Segment("Auth: 123456", bold = bf, fontSize = ff, centered = true)
        segs += Segment("Type: Credit", bold = bf, fontSize = ff, centered = true)
        segs += Segment("")

        // Footer
        segs += Segment("Thank you for dining with us!", bold = bf, fontSize = ff, centered = true)

        return segs
    }

    // ── Helpers ──────────────────────────────────────────────────

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
