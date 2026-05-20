package com.volt.maximobile.dvpaylite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.denovo.app.invokekozen.printer.interfaces.PrintLauncherInterface
import com.denovo.app.invokekozen.printer.launcher.IntentPrintApplication
import com.denovo.app.invokekozen.printer.models.PrintErrorResult
import com.denovo.app.invokekozen.printer.models.PrintResult

/**
 * Wraps the Dejavoo Printer SDK (`printer_v3.0.aar`) to print on the P8's
 * built-in thermal printer.
 *
 * Text XML tags: `<C>`, `<L>`, `<R>`, `<B>`. Logo is sent in a separate print job
 * via `<IMG>base64</IMG>` only — embedding a large IMG in the same job as text
 * causes the P8 to print the base64 string as literal characters after the image.
 */
object P8ReceiptPrinter {

    private const val TAG = "P8ReceiptPrinter"
    const val LINE_WIDTH = 24

    private var printApp: IntentPrintApplication? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ReceiptSegment(
        val text: String = "",
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
        settings: com.volt.maximobile.ReceiptSettings? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
        onLogoSkipped: ((String) -> Unit)? = null,
    ) {
        if (settings != null && settings.logoUrl.isNotBlank()) {
            Thread {
                val base64 = P8LogoHelper.loadLogoBase64(settings.logoUrl)
                if (base64 == null) {
                    onLogoSkipped?.let { callback ->
                        mainHandler.post { callback("Could not load logo image") }
                    }
                }
                mainHandler.post {
                    printReceiptInternal(segments, base64, onSuccess, onFailure)
                }
            }.start()
            return
        }

        printReceiptInternal(segments, null, onSuccess, onFailure)
    }

    private fun printReceiptInternal(
        segments: List<ReceiptSegment>,
        logoBase64: String?,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?,
    ) {
        val app = printApp ?: run {
            onFailure?.invoke("Printer not initialized")
            return
        }

        if (!logoBase64.isNullOrBlank()) {
            Log.d(TAG, "Printing logo job (${logoBase64.length} base64 chars)")
            launchPrinterXml(
                app = app,
                xml = buildLogoOnlyXml(logoBase64),
                onSuccess = {
                    Log.d(TAG, "Logo job done; printing receipt body")
                    launchPrinterXml(
                        app = app,
                        xml = buildPrinterXml(segments),
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                    )
                },
                onFailure = onFailure,
            )
        } else {
            launchPrinterXml(
                app = app,
                xml = buildPrinterXml(segments),
                onSuccess = onSuccess,
                onFailure = onFailure,
            )
        }
    }

    private fun launchPrinterXml(
        app: IntentPrintApplication,
        xml: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?,
    ) {
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

    /** Logo-only job — must not mix with text lines in the same XML payload on P8. */
    private fun buildLogoOnlyXml(base64: String): String =
        "<request><printer width=\"24\"><IMG>$base64</IMG></printer></request>"

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
