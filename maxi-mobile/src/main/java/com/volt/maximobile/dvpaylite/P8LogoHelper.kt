package com.volt.maximobile.dvpaylite

import com.volt.shared.receipt.ReceiptLogoLoader

/**
 * P8 printer logo segments via shared [ReceiptLogoLoader].
 */
object P8LogoHelper {

    fun clearCache() = ReceiptLogoLoader.clearCache()

    fun loadLogoSegment(url: String): P8ReceiptPrinter.ReceiptSegment? {
        val base64 = ReceiptLogoLoader.downloadBase64Png(url) ?: return null
        return P8ReceiptPrinter.ReceiptSegment(imageBase64 = base64)
    }
}
