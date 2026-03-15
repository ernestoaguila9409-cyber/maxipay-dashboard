package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

class LoginActivity : AppCompatActivity() {

    private val functions = FirebaseFunctions.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var isLoggingIn = false
    private val pinBuilder = StringBuilder()
    private val maxPinLength = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        fun onPinChanged() {
            updateDots()
            if (pinBuilder.length == maxPinLength && !isLoggingIn) {
                loginWithPin(pinBuilder.toString())
            }
        }

        listOf(
            R.id.key0 to "0",
            R.id.key1 to "1",
            R.id.key2 to "2",
            R.id.key3 to "3",
            R.id.key4 to "4",
            R.id.key5 to "5",
            R.id.key6 to "6",
            R.id.key7 to "7",
            R.id.key8 to "8",
            R.id.key9 to "9"
        ).forEach { (id, digit) ->
            findViewById<Button>(id).setOnClickListener {
                if (pinBuilder.length < maxPinLength) {
                    pinBuilder.append(digit)
                    onPinChanged()
                }
            }
        }

        findViewById<Button>(R.id.keyBack).setOnClickListener {
            if (pinBuilder.isNotEmpty()) {
                pinBuilder.deleteCharAt(pinBuilder.length - 1)
                updateDots()
            }
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
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
                    isLoggingIn = false
                    pinBuilder.clear()
                    updateDots()
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val name = response["name"] as? String ?: ""
                val role = response["role"] as? String ?: ""

                auth.signInAnonymously()
                    .addOnSuccessListener {
                        isLoggingIn = false
                        TerminalPrefs.initFromFirestore()
                        SessionEmployee.setEmployee(this, name, role)

                        val intent = Intent(this, MainActivity::class.java)
                        intent.putExtra("employeeName", name)
                        intent.putExtra("employeeRole", role)
                        startActivity(intent)
                        finish()
                    }
                    .addOnFailureListener {
                        isLoggingIn = false
                        pinBuilder.clear()
                        updateDots()
                        Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                isLoggingIn = false
                pinBuilder.clear()
                updateDots()
                Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateDots() {
        val pinDots = listOf(
            findViewById<ImageView>(R.id.pinDot0),
            findViewById<ImageView>(R.id.pinDot1),
            findViewById<ImageView>(R.id.pinDot2),
            findViewById<ImageView>(R.id.pinDot3)
        )
        for (i in 0 until maxPinLength) {
            pinDots[i].setBackgroundResource(
                if (i < pinBuilder.length) R.drawable.pin_dot_filled
                else R.drawable.pin_dot_empty
            )
        }
    }
}
