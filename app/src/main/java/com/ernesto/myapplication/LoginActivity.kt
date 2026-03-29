package com.ernesto.myapplication

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

class LoginActivity : AppCompatActivity() {

    private val functions = FirebaseFunctions.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * Anonymous Firebase Auth is required for Firestore after login. Starting it while the user
     * enters their PIN overlaps that latency with [verifyPin], so the post-PIN path is often a
     * single network round trip instead of two sequential calls.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#12002F")
        window.navigationBarColor = Color.parseColor("#12002F")

        setContentView(R.layout.activity_login)

        prewarmAnonymousAuth()
        ReceiptSettings.startBusinessInfoSync(this)

        val bizName = ReceiptSettings.load(this).businessName
        CustomerDisplayManager.setIdle(this, bizName)

        dotsContainer = findViewById(R.id.dotsContainer)
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

        val data = hashMapOf("pin" to pin)

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
}
