package com.ernesto.myapplication

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions

class LoginActivity : AppCompatActivity() {

    private val functions = FirebaseFunctions.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * Anonymous Firebase Auth is required for Firestore after login. Starting it while the user
     * enters their PIN overlaps that latency with [verifyPin], so the post-PIN path is often a
     * single network round trip instead of two sequential ones.
     */
    private fun prewarmAnonymousAuth() {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }
    }
    private var isLoggingIn = false
    private val pinBuilder = StringBuilder()
    private val maxPinLength = 4
    private lateinit var pinDots: List<ImageView>
    private lateinit var dotsContainer: LinearLayout
    private lateinit var loginTitleBusiness: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#12002F")
        window.navigationBarColor = Color.parseColor("#12002F")

        setContentView(R.layout.activity_login)

        val gateProgress = findViewById<View>(R.id.loginGateProgress)
        dotsContainer = findViewById(R.id.dotsContainer)
        findViewById<View>(R.id.loginKeypadRoot)
        loginTitleBusiness = findViewById(R.id.loginTitleBusiness)

        ensureMerchantInitialized()
        applyLoginHeaderFromSettings(ReceiptSettings.load(this))
        if (MerchantFirestore.isInitialized) {
            PosDeviceIdentity.syncMerchantBusinessNameFromFirestore(applicationContext) {
                runOnUiThread { applyLoginHeaderFromSettings(ReceiptSettings.load(this)) }
            }
        }
        ReceiptSettings.setOnSettingsChangedListener { rs ->
            runOnUiThread { applyLoginHeaderFromSettings(rs) }
        }

        prewarmAnonymousAuth()

        scheduleDeviceGateAndContinue {
            gateProgress.visibility = View.GONE
            dotsContainer.visibility = View.VISIBLE
            findViewById<View>(R.id.loginKeypadRoot).visibility = View.VISIBLE

            if (MerchantFirestore.isInitialized) {
                PosDeviceIdentity.syncMerchantBusinessNameFromFirestore(applicationContext) {
                    runOnUiThread {
                        ReceiptSettings.startBusinessInfoSync(this)
                        applyLoginHeaderFromSettings(ReceiptSettings.load(this))
                        val bizName = ReceiptSettings.load(this).businessName
                        CustomerDisplayManager.setIdle(this, bizName)
                    }
                }
            }
        }

        pinDots = listOf(
            findViewById(R.id.pinDot0),
            findViewById(R.id.pinDot1),
            findViewById(R.id.pinDot2),
            findViewById(R.id.pinDot3)
        )

        val digitKeys = listOf(
            R.id.key0 to "0", R.id.key1 to "1", R.id.key2 to "2",
            R.id.key3 to "3", R.id.key4 to "4", R.id.key5 to "5",
            R.id.key6 to "6", R.id.key7 to "7", R.id.key8 to "8",
            R.id.key9 to "9"
        )

        digitKeys.forEach { (id, digit) ->
            val btn = findViewById<Button>(id)
            addPressAnimation(btn)
            btn.setOnClickListener {
                if (pinBuilder.length < maxPinLength) {
                    pinBuilder.append(digit)
                    updateDots()
                    if (pinBuilder.length == maxPinLength && !isLoggingIn) {
                        loginWithPin(pinBuilder.toString())
                    }
                }
            }
        }

        val keyBack = findViewById<Button>(R.id.keyBack)
        addPressAnimation(keyBack)
        keyBack.setOnClickListener {
            if (pinBuilder.isNotEmpty()) {
                pinBuilder.deleteCharAt(pinBuilder.length - 1)
                updateDots()
            }
        }

        val keyConfirm = findViewById<Button>(R.id.keyConfirm)
        addPressAnimation(keyConfirm)
        keyConfirm.setOnClickListener {
            if (pinBuilder.length == maxPinLength && !isLoggingIn) {
                loginWithPin(pinBuilder.toString())
            }
        }
    }

    override fun onDestroy() {
        // Do not call [ReceiptSettings.stopBusinessInfoSync] here — [MainActivity] starts its own
        // listeners right after login; tearing them down from this activity would break sync.
        ReceiptSettings.setOnSettingsChangedListener(null)
        super.onDestroy()
    }

    private fun applyLoginHeaderFromSettings(rs: ReceiptSettings) {
        val fromIdentity = PosDeviceIdentity.getMerchantBusinessName(this).trim()
        val fromReceipt = rs.businessName.trim()
        val name = when {
            fromIdentity.isNotEmpty() -> fromIdentity
            fromReceipt.isNotEmpty() -> fromReceipt
            else -> getString(R.string.login_business_title_fallback)
        }
        loginTitleBusiness.text = name
    }

    /**
     * Blocks the PIN screen until we know this install is allowed to sign in:
     * 1) No persisted merchant id → device was never activated → [DeviceActivationActivity].
     * 2) If [PosDeviceDeactivationWatch.FIELD_DEACTIVATED] is true → forced re-activation.
     * 3) If [PosDeviceActivation.FIELD_ENROLLED_FROM_DASHBOARD] is not true → must redeem code.
     * Uses [Source.SERVER] so a stale local cache cannot skip the gate.
     *
     * If the server read fails (offline), we allow the PIN screen so devices are not bricked.
     */
    private fun scheduleDeviceGateAndContinue(onReady: () -> Unit) {
        fun readDeviceFromServer() {
            ensureMerchantInitialized()
            if (!MerchantFirestore.isInitialized) {
                Log.d(TAG, "device gate: no merchantId persisted → activation required")
                DeviceActivationActivity.launchEnrollmentRequired(this)
                finish()
                return
            }
            PosDeviceIdentity.resolveInstallationDocId(this) { docId ->
                MerchantFirestore.col("PosDevices")
                    .document(docId)
                    .get(Source.SERVER)
                    .addOnSuccessListener { snap ->
                        if (snap.exists() && snap.getBoolean(PosDeviceDeactivationWatch.FIELD_DEACTIVATED) == true) {
                            DeviceActivationActivity.launchForceLock(this)
                            finish()
                            return@addOnSuccessListener
                        }
                        val enrolled = snap.exists() &&
                            snap.getBoolean(PosDeviceActivation.FIELD_ENROLLED_FROM_DASHBOARD) == true
                        if (!enrolled) {
                            DeviceActivationActivity.launchEnrollmentRequired(this)
                            finish()
                            return@addOnSuccessListener
                        }
                        runOnUiThread { onReady() }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "device gate: PosDevices server read failed", e)
                        runOnUiThread { onReady() }
                    }
            }
        }
        if (auth.currentUser != null) {
            readDeviceFromServer()
        } else {
            auth.signInAnonymously().addOnCompleteListener { readDeviceFromServer() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false
        }
    }

    private fun updateDots() {
        for (i in 0 until maxPinLength) {
            if (i < pinBuilder.length) {
                if (pinDots[i].tag != "filled") {
                    pinDots[i].tag = "filled"
                    pinDots[i].setBackgroundResource(R.drawable.pin_dot_filled)
                    // Fourth digit: no scale animation so login can feel instant on key up.
                    if (pinBuilder.length == maxPinLength) {
                        pinDots[i].scaleX = 1f
                        pinDots[i].scaleY = 1f
                    } else {
                        pinDots[i].scaleX = 0.5f
                        pinDots[i].scaleY = 0.5f
                        pinDots[i].animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(OvershootInterpolator(3f))
                            .start()
                    }
                }
            } else {
                pinDots[i].tag = "empty"
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_empty)
                pinDots[i].scaleX = 1f
                pinDots[i].scaleY = 1f
            }
        }
    }

    private fun shakeDots() {
        ObjectAnimator.ofFloat(
            dotsContainer, "translationX",
            0f, 20f, -20f, 15f, -15f, 8f, -8f, 0f
        ).apply {
            duration = 400
            start()
        }
    }

    private fun onLoginFailed(message: String) {
        isLoggingIn = false
        shakeDots()
        dotsContainer.postDelayed({
            pinBuilder.clear()
            updateDots()
        }, 500)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun loginWithPin(pin: String) {
        if (isLoggingIn) return
        isLoggingIn = true

        val data = hashMapOf<String, Any>("pin" to pin)
        val mid = PosDeviceIdentity.getMerchantId(this).trim()
        if (mid.isNotEmpty()) {
            data["merchantId"] = mid
        }

        functions.getHttpsCallable("verifyPin")
            .call(data)
            .addOnSuccessListener { result ->
                @Suppress("UNCHECKED_CAST")
                val response = result.data as? Map<String, Any?> ?: emptyMap()
                val success = response["success"] as? Boolean ?: false

                if (!success) {
                    onLoginFailed("Invalid PIN")
                    return@addOnSuccessListener
                }

                val name = response["name"] as? String ?: ""
                val role = response["role"] as? String ?: ""

                fun openMainAfterAuth() {
                    ensureMerchantInitialized()
                    isLoggingIn = false
                    TerminalPrefs.initFromFirestore()
                    SessionEmployee.setEmployee(this, name, role)

                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("employeeName", name)
                    intent.putExtra("employeeRole", role)
                    startActivity(intent)
                    finish()
                }

                if (auth.currentUser != null) {
                    openMainAfterAuth()
                } else {
                    auth.signInAnonymously()
                        .addOnSuccessListener { openMainAfterAuth() }
                        .addOnFailureListener {
                            onLoginFailed("Login failed")
                        }
                }
            }
            .addOnFailureListener {
                onLoginFailed("Login failed")
            }
    }

    /**
     * Reads the persisted merchant id (set by [PosDeviceActivation.redeemCode]) and
     * initialises [MerchantFirestore] if not already done. Call at every entry point
     * that needs Firestore paths (gate, post-login).
     */
    private fun ensureMerchantInitialized() {
        if (MerchantFirestore.isInitialized) return
        val mid = PosDeviceIdentity.getMerchantId(this)
        if (mid.isNotEmpty()) {
            MerchantFirestore.init(mid)
            Log.d(TAG, "MerchantFirestore initialized from prefs: $mid")
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
