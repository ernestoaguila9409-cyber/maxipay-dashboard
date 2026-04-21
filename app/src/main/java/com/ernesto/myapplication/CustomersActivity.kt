package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore

class CustomersActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: CustomersAdapter
    private lateinit var txtTitle: TextView
    private lateinit var btnCancelSelect: TextView
    private lateinit var btnDeleteMode: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customers)

        txtTitle = findViewById(R.id.txtTitle)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        btnDeleteMode = findViewById(R.id.btnDeleteMode)

        adapter = CustomersAdapter(
            onSelectionChanged = { updateSelectionUI(it) },
            onItemClick = { customer -> openCustomerProfile(customer) }
        )
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerCustomers).apply {
            layoutManager = LinearLayoutManager(this@CustomersActivity)
            this.adapter = this@CustomersActivity.adapter
        }

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnAddCustomer)
            .setOnClickListener { showAddCustomerDialog() }

        btnDeleteMode.setOnClickListener {
            if (adapter.isSelectionMode()) {
                val selected = adapter.getSelectedIds()
                if (selected.isNotEmpty()) {
                    confirmDeleteCustomers(selected)
                } else {
                    exitSelectionMode()
                }
            } else {
                enterSelectionMode()
            }
        }

        btnCancelSelect.setOnClickListener { exitSelectionMode() }
    }

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
        btnCancelSelect.visibility = View.VISIBLE
        txtTitle.text = "Select customers"
    }

    private fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        btnCancelSelect.visibility = View.GONE
        txtTitle.text = "Customers"
    }

    private fun updateSelectionUI(selectedIds: Set<String>) {
        txtTitle.text = if (selectedIds.isEmpty()) "Select customers" else "${selectedIds.size} selected"
    }

    override fun onResume() {
        super.onResume()
        loadCustomers()
    }

    private fun loadCustomers() {
        db.collection("Customers")
            .get()
            .addOnSuccessListener { documents ->
                val items = documents.map { doc ->
                    val firstName = doc.getString("firstName") ?: ""
                    val lastName = doc.getString("lastName") ?: ""
                    val name = doc.getString("name") ?: ""
                    val phone = doc.getString("phone") ?: ""
                    val email = doc.getString("email") ?: ""

                    val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                        "$firstName $lastName".trim()
                    } else {
                        name
                    }
                    val visitCount = (doc.getLong("visitCount") ?: 0L).toInt().coerceAtLeast(0)
                    CustomerItem(
                        id = doc.id,
                        name = fullName,
                        phone = phone,
                        email = email,
                        visitCount = visitCount,
                    )
                }
                adapter.submitList(items)
            }
    }

    private fun showAddCustomerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_customer, null)

        val etFirstName = dialogView.findViewById<EditText>(R.id.etFirstName)
        val etLastName = dialogView.findViewById<EditText>(R.id.etLastName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etPhone)
        val etEmail = dialogView.findViewById<EditText>(R.id.etEmail)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Customer")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val firstName = etFirstName.text.toString().trim()
                val lastName = etLastName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                val email = etEmail.text.toString().trim()

                if (firstName.isEmpty() && lastName.isEmpty()) {
                    Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val fullName = "$firstName $lastName".trim()

                CustomerDuplicateChecker.checkExists(db, fullName, email) { exists ->
                    if (exists) {
                        Toast.makeText(
                            this,
                            "A customer with this name and email already exists",
                            Toast.LENGTH_LONG
                        ).show()
                        return@checkExists
                    }

                    val customer = hashMapOf(
                        "firstName" to firstName,
                        "lastName" to lastName,
                        "name" to fullName,
                        "nameSearch" to CustomerFirestoreHelper.nameSearchKey(fullName),
                        "phone" to phone,
                        "email" to email,
                        "createdAt" to Timestamp.now(),
                    )

                    db.collection("Customers")
                        .add(customer)
                        .addOnSuccessListener {
                            dialog.dismiss()
                            Toast.makeText(this, "Customer Added", Toast.LENGTH_SHORT).show()
                            loadCustomers()
                        }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteCustomers(selectedIds: Set<String>) {
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle("Delete Customers")
            .setMessage("Delete $count customer${if (count == 1) "" else "s"}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteCustomers(selectedIds) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCustomerProfile(customer: CustomerItem) {
        val intent = Intent(this, CustomerProfileActivity::class.java)
        intent.putExtra("customerId", customer.id)
        intent.putExtra("customerName", customer.name)
        startActivity(intent)
    }

    private fun deleteCustomers(selectedIds: Set<String>) {
        if (selectedIds.isEmpty()) return
        exitSelectionMode()
        val batch = db.batch()
        for (id in selectedIds) {
            batch.delete(db.collection("Customers").document(id))
        }
        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(this, "Customer${if (selectedIds.size > 1) "s" else ""} deleted", Toast.LENGTH_SHORT).show()
                loadCustomers()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
                loadCustomers()
            }
    }
}
