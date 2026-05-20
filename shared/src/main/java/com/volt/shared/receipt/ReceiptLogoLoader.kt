package com.volt.shared.receipt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads merchant logo images from Firebase Storage HTTPS URLs for thermal printers.
 */
object ReceiptLogoLoader {

    private const val TAG = "ReceiptLogoLoader"
    /** ~58mm head; keep logo modest for P8 IMG tag payload limits. */
    private const val MAX_LOGO_WIDTH_PX = 128

    private var cachedUrl: String = ""
    private var cachedMaxWidthPx: Int = -1
    private var cachedBitmap: Bitmap? = null
    private var cachedBase64: String? = null

    fun clearCache() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedUrl = ""
        cachedMaxWidthPx = -1
        cachedBase64 = null
    }

    fun onLogoUrlChanged(newUrl: String, previousUrl: String) {
        if (newUrl.trim() != previousUrl.trim()) clearCache()
    }

    fun downloadBitmap(url: String, maxWidthPx: Int = MAX_LOGO_WIDTH_PX): Bitmap? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == cachedUrl && maxWidthPx == cachedMaxWidthPx && cachedBitmap != null) {
            return cachedBitmap
        }

        val bytes = downloadBytes(trimmed) ?: return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            Log.e(TAG, "Logo decode failed (${bytes.size} bytes)")
            return null
        }

        val flattened = flattenOnWhite(decoded)
        if (flattened !== decoded) decoded.recycle()

        val cropped = trimWhitespace(flattened).also {
            if (it !== flattened) flattened.recycle()
        }

        val scaled = when {
            cropped.width > maxWidthPx -> {
                val ratio = maxWidthPx.toFloat() / cropped.width
                val newH = (cropped.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(cropped, maxWidthPx, newH, false).also {
                    if (it !== cropped) cropped.recycle()
                }
            }
            cropped.width < maxWidthPx -> {
                val ratio = maxWidthPx.toFloat() / cropped.width
                val newH = (cropped.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(cropped, maxWidthPx, newH, false).also {
                    if (it !== cropped) cropped.recycle()
                }
            }
            else -> cropped
        }

        val logoBitmap = trimWhitespace(scaled).also {
            if (it !== scaled) scaled.recycle()
        }

        cachedBitmap?.recycle()
        cachedUrl = trimmed
        cachedMaxWidthPx = maxWidthPx
        cachedBitmap = logoBitmap
        cachedBase64 = null
        Log.d(TAG, "Logo ready: ${logoBitmap.width}x${logoBitmap.height}")
        return logoBitmap
    }

    fun downloadBase64Jpeg(
        url: String,
        maxWidthPx: Int = MAX_LOGO_WIDTH_PX,
        quality: Int = 85,
    ): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == cachedUrl && maxWidthPx == cachedMaxWidthPx && cachedBase64 != null) {
            return cachedBase64
        }
        val bitmap = downloadBitmap(trimmed, maxWidthPx) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        cachedBase64 = encoded
        Log.d(TAG, "Logo JPEG base64 encoded (${encoded.length} chars, q=$quality)")
        return encoded
    }

    /** @deprecated Use [downloadBase64Jpeg] */
    fun downloadBase64Png(url: String, maxWidthPx: Int = MAX_LOGO_WIDTH_PX): String? =
        downloadBase64Jpeg(url, maxWidthPx)

    private fun downloadBytes(url: String): ByteArray? {
        if (url.contains("firebasestorage.googleapis.com") || url.contains("firebase")) {
            try {
                Log.d(TAG, "Downloading logo via Firebase Storage SDK")
                val ref = FirebaseStorage.getInstance().getReferenceFromUrl(url)
                val bytes = Tasks.await(ref.getBytes(2 * 1024 * 1024L))
                if (bytes.isNotEmpty()) return bytes
            } catch (e: Exception) {
                Log.w(TAG, "Firebase Storage SDK download failed: ${e.message}")
            }
        }

        return try {
            Log.d(TAG, "Downloading logo via HTTP: $url")
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "MaxiPay-POS/1.0")
                    .build()
            ).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Logo HTTP download failed: ${response.code}")
                return null
            }
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Logo HTTP download failed: ${e.message}", e)
            null
        }
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    /** Extra crop pass before Landi raster print (cheap if already tight). */
    fun trimForThermalPrint(source: Bitmap): Bitmap = trimWhitespace(source)

    /**
     * Crops empty margins so thermal raster rows do not feed blank paper before/after the logo.
     */
    private fun trimWhitespace(source: Bitmap, luminanceThreshold: Int = 250): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source

        fun isInk(x: Int, y: Int): Boolean {
            val pixel = source.getPixel(x, y)
            if (Color.alpha(pixel) < 128) return false
            val lum = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
            return lum < luminanceThreshold
        }

        val minInkPerRow = maxOf(2, w / 400)

        fun rowInkCount(y: Int): Int {
            var count = 0
            var x = 0
            while (x < w) {
                if (isInk(x, y)) {
                    count++
                    if (count >= minInkPerRow) return count
                }
                x++
            }
            return count
        }

        var top = 0
        while (top < h && rowInkCount(top) < minInkPerRow) top++

        var bottom = h - 1
        while (bottom >= top && rowInkCount(bottom) < minInkPerRow) bottom--

        if (top > bottom) return source

        fun colInkCount(x: Int): Int {
            var count = 0
            var y = top
            while (y <= bottom) {
                if (isInk(x, y)) {
                    count++
                    if (count >= minInkPerRow) return count
                }
                y++
            }
            return count
        }

        var left = 0
        while (left < w && colInkCount(left) < minInkPerRow) left++

        var right = w - 1
        while (right >= left && colInkCount(right) < minInkPerRow) right--

        val newW = right - left + 1
        val newH = bottom - top + 1
        if (newW == w && newH == h) return source
        return Bitmap.createBitmap(source, left, top, newW, newH)
    }
}
