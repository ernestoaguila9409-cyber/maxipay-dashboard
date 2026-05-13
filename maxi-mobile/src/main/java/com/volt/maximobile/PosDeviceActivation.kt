package com.volt.maximobile

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date

/**
 * Same redemption flow as main POS [com.ernesto.myapplication.PosDeviceActivation]:
 * `Merchants/{mid}/deviceActivations/{code}`.
 */
object PosDeviceActivation {

    private const val TAG = "PosDeviceActivation"
    private const val ACTIVATIONS_COLLECTION = "deviceActivations"
    private const val DEVICES_COLLECTION = "PosDevices"

    const val FIELD_ENROLLED_FROM_DASHBOARD = "enrolledFromDashboard"

    private const val CODE_LEN = 6

    private const val ERR_NO_DOC = "ACTIVATION_NO_DOC"
    private const val ERR_USED = "ACTIVATION_USED"
    private const val ERR_EXPIRED = "ACTIVATION_EXPIRED"

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

        val db = FirebaseFirestore.getInstance()
        val appCtx = context.applicationContext
        val auth = FirebaseAuth.getInstance()

        fun afterAuthReady() {
            db.collection("Merchants").get()
                .addOnFailureListener { e ->
                    Log.w(TAG, "Merchants list failed: ${e.message}", e)
                    onError(context.getString(R.string.device_activation_failed, e.message ?: ""))
                }
                .addOnSuccessListener { merchantSnap ->
                    if (merchantSnap.isEmpty) {
                        onError(context.getString(R.string.device_activation_invalid_code))
                        return@addOnSuccessListener
                    }
                    findCodeAcrossMerchants(
                        db,
                        merchantSnap.documents.map { it.id },
                        code,
                        0,
                        context,
                        appCtx,
                        onSuccess,
                        onError,
                    )
                }
        }

        if (auth.currentUser != null) {
            afterAuthReady()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { afterAuthReady() }
                .addOnFailureListener { e ->
                    Log.w(TAG, "anonymous auth failed: ${e.message}", e)
                    onError(context.getString(R.string.device_activation_failed, e.message ?: ""))
                }
        }
    }

    private fun findCodeAcrossMerchants(
        db: FirebaseFirestore,
        merchantIds: List<String>,
        code: String,
        index: Int,
        context: Context,
        appCtx: Context,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (index >= merchantIds.size) {
            onError(context.getString(R.string.device_activation_invalid_code))
            return
        }
        val merchantId = merchantIds[index]
        db.collection("Merchants").document(merchantId)
            .collection(ACTIVATIONS_COLLECTION).document(code)
            .get()
            .addOnFailureListener {
                findCodeAcrossMerchants(db, merchantIds, code, index + 1, context, appCtx, onSuccess, onError)
            }
            .addOnSuccessListener { actDoc ->
                if (!actDoc.exists()) {
                    findCodeAcrossMerchants(db, merchantIds, code, index + 1, context, appCtx, onSuccess, onError)
                    return@addOnSuccessListener
                }

                if (!MerchantFirestore.isInitialized) {
                    MerchantFirestore.init(merchantId)
                }

                PosDeviceIdentity.resolveInstallationDocId(context) { installationDocId ->
                    val actRef = MerchantFirestore.doc(ACTIVATIONS_COLLECTION, actDoc.id)
                    val devRef = MerchantFirestore.doc(DEVICES_COLLECTION, installationDocId)

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
                    val deviceSerial = DeviceSerial.getBestEffort(context)
                    val deviceStableId = DeviceSerial.getStableAndroidId(context)

                    if (actDoc.getBoolean("consumed") == true) {
                        onError(context.getString(R.string.device_activation_already_used))
                        return@resolveInstallationDocId
                    }
                    val exp = actDoc.getTimestamp("expiresAt")
                    if (exp == null) {
                        onError(context.getString(R.string.device_activation_invalid_code))
                        return@resolveInstallationDocId
                    }
                    if (Date().time > exp.toDate().time) {
                        onError(context.getString(R.string.device_activation_expired))
                        return@resolveInstallationDocId
                    }

                    db.runTransaction { tx ->
                        val freshSnap = tx.get(actRef)
                        if (!freshSnap.exists()) throw Exception(ERR_NO_DOC)
                        if (freshSnap.getBoolean("consumed") == true) throw Exception(ERR_USED)

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
                            FIELD_ENROLLED_FROM_DASHBOARD to true,
                            PosDeviceDeactivationWatch.FIELD_DEACTIVATED to false,
                            "lastSeen" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                        if (deviceStableId.isNotEmpty()) {
                            devicePayload["deviceStableId"] = deviceStableId
                        }
                        if (deviceSerial.isNotEmpty()) {
                            devicePayload["deviceSerial"] = deviceSerial
                        }
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
                        PosDeviceIdentity.setMerchantId(appCtx, merchantId)
                        Log.d(TAG, "Activation success, merchantId=$merchantId")
                        // Navigate immediately; do not block UI on optional business-name sync.
                        Handler(Looper.getMainLooper()).post { onSuccess() }
                        PosDeviceIdentity.syncMerchantBusinessNameFromFirestore(appCtx)
                    }.addOnFailureListener { e ->
                        Log.w(TAG, "Redeem tx failed: ${e.message}", e)
                        val chain = buildString {
                            var t: Throwable? = e
                            var n = 0
                            while (t != null && n++ < 8) {
                                t.message?.let { append(it).append(' ') }
                                t = t.cause
                            }
                        }
                        val msg = when {
                            chain.contains(ERR_USED) -> context.getString(R.string.device_activation_already_used)
                            chain.contains(ERR_EXPIRED) -> context.getString(R.string.device_activation_expired)
                            chain.contains(ERR_NO_DOC) -> context.getString(R.string.device_activation_invalid_code)
                            else -> context.getString(R.string.device_activation_failed, e.message ?: "")
                        }
                        onError(msg)
                    }
                }
            }
    }
}
