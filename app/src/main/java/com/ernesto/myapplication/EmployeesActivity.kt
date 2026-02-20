package com.ernesto.myapplication

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore

class EmployeesActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var btnAdd: ImageButton
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employees)

        btnAdd = findViewById(R.id.btnAddEmployee)
        container = findViewById(R.id.employeeContainer)

        btnAdd.setOnClickListener {
            showAddEmployeeDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        loadEmployees()
    }

    private fun loadEmployees() {

        container.removeAllViews()

        db.collection("Employees")
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val employeeId = doc.id
                    val name = doc.getString("name") ?: ""
                    val role = doc.getString("role") ?: ""
                    val pin = doc.getString("pin") ?: ""

                    val textView = TextView(this)
                    textView.text = "$name - $role"
                    textView.textSize = 18f
                    textView.setPadding(0, 30, 0, 30)

                    textView.setOnClickListener {
                        showEditEmployeeDialog(employeeId, name, pin, role)
                    }

                    container.addView(textView)
                }
            }
    }

    private fun showAddEmployeeDialog() {

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_employee, null)

        val etName = dialogView.findViewById<EditText>(R.id.etEmployeeName)
        val etPin = dialogView.findViewById<EditText>(R.id.etEmployeePin)
        val pinLayout = dialogView.findViewById<TextInputLayout>(R.id.pinLayout)
        val spinnerRole = dialogView.findViewById<Spinner>(R.id.spinnerRole)

        val roles = arrayOf("EMPLOYEE", "ADMINISTRATOR")

        spinnerRole.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            roles
        )

        // 🔥 LIVE PIN CHECK
        etPin.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {

                val pin = s.toString()

                if (pin.length == 4) {

                    db.collection("Employees")
                        .whereEqualTo("pin", pin)
                        .get()
                        .addOnSuccessListener { documents ->

                            if (!documents.isEmpty) {
                                pinLayout.error = "PIN already in use"
                            } else {
                                pinLayout.error = null
                            }
                        }
                } else {
                    pinLayout.error = null
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Add Employee")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->

                val name = etName.text.toString().trim()
                val pin = etPin.text.toString().trim()
                val role = spinnerRole.selectedItem.toString()

                if (name.isEmpty() || pin.isEmpty()) {
                    Toast.makeText(this, "All fields required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (pinLayout.error != null) {
                    Toast.makeText(this, "Fix PIN error first", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val employee = hashMapOf(
                    "name" to name,
                    "pin" to pin,
                    "role" to role,
                    "active" to true
                )

                db.collection("Employees")
                    .add(employee)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Employee Added", Toast.LENGTH_SHORT).show()
                        loadEmployees()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditEmployeeDialog(
        employeeId: String,
        currentName: String,
        currentPin: String,
        currentRole: String
    ) {

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_employee, null)

        val etName = dialogView.findViewById<EditText>(R.id.etEmployeeName)
        val etPin = dialogView.findViewById<EditText>(R.id.etEmployeePin)
        val spinnerRole = dialogView.findViewById<Spinner>(R.id.spinnerRole)

        val roles = arrayOf("EMPLOYEE", "ADMINISTRATOR")

        spinnerRole.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            roles
        )

        etName.setText(currentName)
        etPin.setText(currentPin)

        val roleIndex = roles.indexOf(currentRole)
        if (roleIndex >= 0) {
            spinnerRole.setSelection(roleIndex)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Employee")
            .setView(dialogView)
            .setPositiveButton("Update") { _, _ ->

                val newName = etName.text.toString().trim()
                val newPin = etPin.text.toString().trim()
                val newRole = spinnerRole.selectedItem.toString()

                if (newName.isEmpty() || newPin.isEmpty()) {
                    Toast.makeText(this, "All fields required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                db.collection("Employees")
                    .document(employeeId)
                    .update(
                        mapOf(
                            "name" to newName,
                            "pin" to newPin,
                            "role" to newRole
                        )
                    )
                    .addOnSuccessListener {
                        Toast.makeText(this, "Employee Updated", Toast.LENGTH_SHORT).show()
                        loadEmployees()
                    }
            }
            .setNeutralButton("Delete") { _, _ ->

                db.collection("Employees")
                    .document(employeeId)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Employee Deleted", Toast.LENGTH_SHORT).show()
                        loadEmployees()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}