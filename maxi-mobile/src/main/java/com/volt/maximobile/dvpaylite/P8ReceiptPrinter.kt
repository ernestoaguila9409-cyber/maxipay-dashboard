package com.volt.maximobile.dvpaylite

import android.content.Context
import android.util.Log
import com.denovo.app.invokekozen.printer.interfaces.PrintLauncherInterface
import com.denovo.app.invokekozen.printer.launcher.IntentPrintApplication
import com.denovo.app.invokekozen.printer.models.PrintErrorResult
import com.denovo.app.invokekozen.printer.models.PrintResult

/**
 * Wraps the Dejavoo Printer SDK (`printer_v3.0.aar`) to print on the P8's
 * built-in thermal printer. Accepts a list of [ReceiptSegment]s and converts
 * them to the XML format the SDK expects.
 *
 * XML tags: `<C>` center, `<L>` left, `<R>` right, `<B>` bold.
 * Paper width: 24 characters per line.
 */
object P8ReceiptPrinter {

    private const val TAG = "P8ReceiptPrinter"
    const val LINE_WIDTH = 24

    private var printApp: IntentPrintApplication? = null

    data class ReceiptSegment(
        val text: String,
        val bold: Boolean = false,
        val centered: Boolean = false,
    )

    fun init(ctx: Context) {
        if (printApp == null) {
            printApp = IntentPrintApplication(ctx.applicationContext)
        }
    }

    fun printReceipt(
        segments: List<ReceiptSegment>,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
    ) {
        val app = printApp ?: run {
            onFailure?.invoke("Printer not initialized")
            return
        }

        val xml = buildPrinterXml(segments)
        Log.d(TAG, "Printer XML length=${xml.length}")

        app.setLaunchInterface(object : PrintLauncherInterface {
            override fun onPrintSuccess(result: PrintResult?) {
                Log.d(TAG, "Print success: ${result?.printMessage}")
                onSuccess?.invoke()
            }

            override fun onPrintFailed(error: PrintErrorResult?) {
                val msg = error?.errorMessage ?: "Unknown print error"
                Log.e(TAG, "Print failed: $msg")
                onFailure?.invoke(msg)
            }
        })

        app.launchPrinter(xml)
    }

    private fun buildPrinterXml(segments: List<ReceiptSegment>): String {
        val sb = StringBuilder()
        sb.append("<request><printer width=\"24\">")
        for (seg in segments) {
            val escaped = escapeXml(seg.text)
            when {
                seg.bold && seg.centered -> sb.append("<B><C>").append(escaped).append("</C></B>")
                seg.bold -> sb.append("<B><L>").append(escaped).append("</L></B>")
                seg.centered -> sb.append("<C>").append(escaped).append("</C>")
                else -> sb.append("<L>").append(escaped).append("</L>")
            }
        }
        sb.append("</printer></request>")
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun formatLine(left: String, right: String, width: Int = LINE_WIDTH): String {
        val space = (width - left.length - right.length).coerceAtLeast(1)
        return left + " ".repeat(space) + right
    }
}
