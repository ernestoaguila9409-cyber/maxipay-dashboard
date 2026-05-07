package com.ernesto.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.net.URL

object SignatureSettings {

    private const val PREFS = "signature_settings"
    private const val KEY_MODE = "signature_mode"

    const val MODE_NONE = "NONE"
    const val MODE_RECEIPT = "RECEIPT"
    const val MODE_CUSTOMER_DISPLAY = "CUSTOMER_DISPLAY"

    fun getMode(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
    }

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode)
            .apply()
        saveToFirestore(mode)
    }

    private fun saveToFirestore(mode: String) {
        val db = FirebaseFirestore.getInstance()
        val data = hashMapOf<String, Any>("signatureMode" to mode)
        db.collection("Settings").document("signatureSettings")
            .set(data)
            .addOnSuccessListener { Log.d("SignatureSettings", "Saved to Firestore: $mode") }
            .addOnFailureListener { Log.w("SignatureSettings", "Firestore save failed", it) }
    }

    /**
     * Downloads a signature bitmap from a URL. Must be called off the main thread.
     */
    fun downloadSignatureBitmap(url: String): Bitmap? {
        return try {
            val stream = URL(url).openStream()
            BitmapFactory.decodeStream(stream)
        } catch (e: Exception) {
            Log.w("SignatureSettings", "Failed to download signature: ${e.message}")
            null
        }
    }
}
