package com.volt.shared.receipt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads merchant logo images from Firebase Storage HTTPS URLs for thermal printers.
 */
object ReceiptLogoLoader {

    private const val TAG = "ReceiptLogoLoader"
    private const val MAX_LOGO_WIDTH_PX = 192

    private var cachedUrl: String = ""
    private var cachedBitmap: Bitmap? = null
    private var cachedBase64: String? = null

    fun clearCache() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedUrl = ""
        cachedBase64 = null
    }

    fun onLogoUrlChanged(newUrl: String, previousUrl: String) {
        if (newUrl.trim() != previousUrl.trim()) clearCache()
    }

    fun downloadBitmap(url: String, maxWidthPx: Int = MAX_LOGO_WIDTH_PX): Bitmap? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == cachedUrl && cachedBitmap != null) {
            return cachedBitmap
        }
        return try {
            Log.d(TAG, "Downloading logo: $trimmed")
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(
                Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", "MaxiPay-POS/1.0")
                    .build()
            ).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Logo download failed HTTP ${response.code}")
                return null
            }
            val bytes = response.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                Log.e(TAG, "Logo download empty body")
                return null
            }
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (original == null) {
                Log.e(TAG, "Logo decode failed (${bytes.size} bytes)")
                return null
            }

            val scaled = if (original.width > maxWidthPx) {
                val ratio = maxWidthPx.toFloat() / original.width
                val newH = (original.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(original, maxWidthPx, newH, true).also {
                    if (it !== original) original.recycle()
                }
            } else {
                original
            }

            cachedBitmap?.recycle()
            cachedUrl = trimmed
            cachedBitmap = scaled
            cachedBase64 = null
            Log.d(TAG, "Logo ready: ${scaled.width}x${scaled.height}")
            scaled
        } catch (e: Exception) {
            Log.e(TAG, "Logo download failed: ${e.message}", e)
            null
        }
    }

    fun downloadBase64Png(url: String, maxWidthPx: Int = MAX_LOGO_WIDTH_PX): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == cachedUrl && cachedBase64 != null) {
            return cachedBase64
        }
        val bitmap = downloadBitmap(trimmed, maxWidthPx) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        cachedBase64 = encoded
        Log.d(TAG, "Logo base64 encoded (${encoded.length} chars)")
        return encoded
    }
}
