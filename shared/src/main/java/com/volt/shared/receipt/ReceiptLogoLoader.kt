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

        val scaled = if (flattened.width > maxWidthPx) {
            val ratio = maxWidthPx.toFloat() / flattened.width
            val newH = (flattened.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(flattened, maxWidthPx, newH, true).also {
                if (it !== flattened) flattened.recycle()
            }
        } else {
            flattened
        }

            cachedBitmap?.recycle()
            cachedUrl = trimmed
            cachedMaxWidthPx = maxWidthPx
            cachedBitmap = scaled
            cachedBase64 = null
        Log.d(TAG, "Logo ready: ${scaled.width}x${scaled.height}")
        return scaled
    }

    fun downloadBase64Png(url: String, maxWidthPx: Int = MAX_LOGO_WIDTH_PX): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == cachedUrl && maxWidthPx == cachedMaxWidthPx && cachedBase64 != null) {
            return cachedBase64
        }
        val bitmap = downloadBitmap(trimmed, maxWidthPx) ?: return null
        val out = ByteArrayOutputStream()
        // P8 / Kozen SDK accepts base64 in IMG; JPEG is smaller and avoids PNG alpha issues.
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        cachedBase64 = encoded
        Log.d(TAG, "Logo JPEG base64 encoded (${encoded.length} chars)")
        return encoded
    }

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
}
