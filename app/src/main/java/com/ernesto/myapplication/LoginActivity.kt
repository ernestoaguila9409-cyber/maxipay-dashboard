package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etPin = findViewById<EditText>(R.id.etPin)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        btnLogin.setOnClickListener {

            val enteredPin = etPin.text.toString().trim()

            if (enteredPin.isEmpty()) {
                Toast.makeText(this, "Enter PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginWithPin(enteredPin)
        }
    }

    private fun loginWithPin(pin: String) {

        db.collection("Employees")
            .whereEqualTo("pin", pin)
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { documents ->

                if (!documents.isEmpty) {

                    val employee = documents.documents[0]
                    val name = employee.getString("name") ?: ""
                    val role = employee.getString("role") ?: ""

                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("employeeName", name)
                    intent.putExtra("employeeRole", role)
                    startActivity(intent)

                    finish()

                } else {
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
            }
    }
}