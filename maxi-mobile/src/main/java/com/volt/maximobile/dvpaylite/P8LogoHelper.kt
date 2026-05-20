package com.volt.maximobile.dvpaylite

import com.volt.shared.receipt.ReceiptLogoLoader

/** P8 built-in printer: keep logo small — large IMG payloads print as garbage text. */
private const val P8_LOGO_MAX_WIDTH_PX = 96

/**
 * Loads logo JPEG base64 for the Dejavoo P8 Printer SDK `<IMG>` tag.
 */
object P8LogoHelper {

    fun clearCache() = ReceiptLogoLoader.clearCache()

    fun loadLogoBase64(url: String): String? =
        ReceiptLogoLoader.downloadBase64Jpeg(url, P8_LOGO_MAX_WIDTH_PX, quality = 80)
}
