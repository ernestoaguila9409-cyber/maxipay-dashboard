package com.ernesto.myapplication

import android.app.AlertDialog
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class EmployeesActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var btnAdd: FloatingActionButton
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

    private fun isBlankOrValidEmail(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return true
        return Patterns.EMAIL_ADDRESS.matcher(t).matches()
    }

    private fun loadEmployees() {

        container.removeAllViews()

        db.collection("Employees")
            .get()
            .addOnSuccessListener { snap ->

                for (doc in snap.documents.sortedBy { it.getString("name") ?: "" }) {

                    val employeeId = doc.id
                    val name = doc.getString("name") ?: ""
                    val role = doc.getString("role") ?: ""
                    val pin = doc.getString("pin") ?: ""
                    val email = doc.getString("email")
                    val phone = doc.getString("phone")

                    val row = LayoutInflater.from(this).inflate(R.layout.item_employee, container, false)

                    val txtName = row.findViewById<TextView>(R.id.txtEmployeeName)
                    val txtRole = row.findViewById<TextView>(R.id.txtEmployeeRole)
                    val btnEdit = row.findViewById<ImageButton>(R.id.btnEdit)

                    txtName.text = name
                    txtRole.text = role

                    row.setOnClickListener {
                        showEditEmployeeDialog(employeeId, name, pin, role, email, phone)
                    }
                    btnEdit.setOnClickListener {
                        showEditEmployeeDialog(employeeId, name, pin, role, email, phone)
                    }

                    container.addView(row)
                }
            }
    }

    private fun showAddEmployeeDialog() {

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_employee, null)

        val etName = dialogView.findViewById<EditText>(R.id.etEmployeeName)
        val etEmail = dialogView.findViewById<EditText>(R.id.etEmployeeEmail)
        val etPhone = dialogView.findViewById<EditText>(R.id.etEmployeePhone)
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
                val email = etEmail.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                val pin = etPin.text.toString().trim()
                val role = spinnerRole.selectedItem.toString()

                if (name.isEmpty() || pin.isEmpty()) {
                    Toast.makeText(this, "Name and PIN are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!isBlankOrValidEmail(email)) {
                    Toast.makeText(this, "Enter a valid email or leave it blank", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (pinLayout.error != null) {
                    Toast.makeText(this, "Fix PIN error first", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val employee = hashMapOf<String, Any>(
                    "name" to name,
                    "pin" to pin,
                    "role" to role,
                    "active" to true
                )
                if (email.isNotEmpty()) employee["email"] = email
                if (phone.isNotEmpty()) employee["phone"] = phone

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
        currentRole: String,
        currentEmail: String?,
        currentPhone: String?,
    ) {

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_employee, null)

        val etName = dialogView.findViewById<EditText>(R.id.etEmployeeName)
        val etEmail = dialogView.findViewById<EditText>(R.id.etEmployeeEmail)
        val etPhone = dialogView.findViewById<EditText>(R.id.etEmployeePhone)
        val etPin = dialogView.findViewById<EditText>(R.id.etEmployeePin)
        val pinLayout = dialogView.findViewById<TextInputLayout>(R.id.pinLayout)
        val spinnerRole = dialogView.findViewById<Spinner>(R.id.spinnerRole)

        val roles = arrayOf("EMPLOYEE", "ADMINISTRATOR")

        spinnerRole.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            roles
        )

        etName.setText(currentName)
        etEmail.setText(currentEmail?.trim().orEmpty())
        etPhone.setText(currentPhone?.trim().orEmpty())
        etPin.setText(currentPin)

        val roleIndex = roles.indexOf(currentRole)
        if (roleIndex >= 0) {
            spinnerRole.setSelection(roleIndex)
        }

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
                            val conflict = documents.any { it.id != employeeId }
                            pinLayout.error = if (conflict) "PIN already in use" else null
                        }
                } else {
                    pinLayout.error = null
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Edit Employee")
            .setView(dialogView)
            .setPositiveButton("Update") { _, _ ->

                val newName = etName.text.toString().trim()
                val newEmail = etEmail.text.toString().trim()
                val newPhone = etPhone.text.toString().trim()
                val newPin = etPin.text.toString().trim()
                val newRole = spinnerRole.selectedItem.toString()

                if (newName.isEmpty() || newPin.isEmpty()) {
                    Toast.makeText(this, "Name and PIN are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!isBlankOrValidEmail(newEmail)) {
                    Toast.makeText(this, "Enter a valid email or leave it blank", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (pinLayout.error != null) {
                    Toast.makeText(this, "Fix PIN error first", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updates = hashMapOf<String, Any>(
                    "name" to newName,
                    "pin" to newPin,
                    "role" to newRole,
                    "email" to if (newEmail.isNotEmpty()) newEmail else FieldValue.delete(),
                    "phone" to if (newPhone.isNotEmpty()) newPhone else FieldValue.delete(),
                )

                db.collection("Employees")
                    .document(employeeId)
                    .update(updates)
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
