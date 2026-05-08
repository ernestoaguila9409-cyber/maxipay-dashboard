package com.ernesto.myapplication

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date

/**
 * Redeems a dashboard-generated code in [ACTIVATIONS_COLLECTION]. On success, merges device info
 * into [DEVICES_COLLECTION] and marks the code consumed (single-use).
 */
object PosDeviceActivation {

    private const val TAG = "PosDeviceActivation"
    private const val ACTIVATIONS_COLLECTION = "DeviceActivations"
    private const val DEVICES_COLLECTION = "PosDevices"

    private const val CODE_LEN = 6

    private const val ERR_NO_DOC = "ACTIVATION_NO_DOC"
    private const val ERR_USED = "ACTIVATION_USED"
    private const val ERR_EXPIRED = "ACTIVATION_EXPIRED"
    private const val ERR_BAD = "ACTIVATION_BAD_DOC"

    fun normalizeCode(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == CODE_LEN) digits else ""
    }

    fun redeemCode(
        context: Context,
        rawCode: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val code = normalizeCode(rawCode)
        if (code.length != CODE_LEN) {
            onError(context.getString(R.string.device_activation_code_invalid))
            return
        }

        PosDeviceIdentity.resolveInstallationDocId(context) { installationDocId ->
            val db = FirebaseFirestore.getInstance()
            val actRef = db.collection(ACTIVATIONS_COLLECTION).document(code)
            val devRef = db.collection(DEVICES_COLLECTION).document(installationDocId)

            val pm = context.packageManager
            val pkg = context.packageName
            val appVer = try {
                pm.getPackageInfo(pkg, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
            val verCode = try {
                val pi = pm.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
            } catch (_: Exception) {
                0L
            }
            val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

            db.runTransaction { tx ->
                val snap = tx.get(actRef)
                if (!snap.exists()) {
                    throw Exception(ERR_NO_DOC)
                }
                if (snap.getBoolean("consumed") == true) {
                    throw Exception(ERR_USED)
                }
                val exp = snap.getTimestamp("expiresAt")
                if (exp == null) {
                    throw Exception(ERR_BAD)
                }
                val now = Date()
                if (now.time > exp.toDate().time) {
                    throw Exception(ERR_EXPIRED)
                }

                val devicePayload = hashMapOf<String, Any>(
                    "platform" to "android",
                    "deviceModel" to deviceLabel,
                    "manufacturer" to Build.MANUFACTURER.orEmpty(),
                    "model" to Build.MODEL.orEmpty(),
                    "osVersion" to "Android ${Build.VERSION.RELEASE}",
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "appVersion" to appVer,
                    "appVersionCode" to verCode,
                    "activatedAt" to FieldValue.serverTimestamp(),
                    "enrolledFromDashboard" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                tx.set(devRef, devicePayload, SetOptions.merge())

                tx.update(
                    actRef,
                    mapOf(
                        "consumed" to true,
                        "consumedAt" to FieldValue.serverTimestamp(),
                        "installationId" to installationDocId,
                        "deviceModel" to deviceLabel,
                        "osVersion" to "Android ${Build.VERSION.RELEASE}",
                        "appVersion" to appVer,
                    ),
                )
                null
            }.addOnSuccessListener {
                onSuccess()
            }.addOnFailureListener { e ->
                Log.w(TAG, "Redeem failed: ${e.message}", e)
                val chain = buildString {
                    var t: Throwable? = e
                    var n = 0
                    while (t != null && n++ < 8) {
                        t.message?.let { append(it).append(' ') }
                        t = t.cause
                    }
                }
                val msg = when {
                    chain.contains(ERR_NO_DOC) ->
                        context.getString(R.string.device_activation_invalid_code)
                    chain.contains(ERR_USED) ->
                        context.getString(R.string.device_activation_already_used)
                    chain.contains(ERR_EXPIRED) ->
                        context.getString(R.string.device_activation_expired)
                    chain.contains(ERR_BAD) ->
                        context.getString(R.string.device_activation_invalid_code)
                    else ->
                        context.getString(R.string.device_activation_failed, e.message ?: "")
                }
                onError(msg)
            }
        }
    }
}
