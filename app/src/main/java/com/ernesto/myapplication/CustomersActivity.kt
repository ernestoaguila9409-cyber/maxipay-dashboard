package com.ernesto.myapplication

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore

class CustomersActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: CustomersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customers)

        adapter = CustomersAdapter()
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerCustomers).apply {
            layoutManager = LinearLayoutManager(this@CustomersActivity)
            this.adapter = this@CustomersActivity.adapter
        }

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnAddCustomer)
            .setOnClickListener { showAddCustomerDialog() }
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
                    CustomerItem(
                        id = doc.id,
                        name = fullName,
                        phone = phone,
                        email = email
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

        AlertDialog.Builder(this)
            .setTitle("Add Customer")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val firstName = etFirstName.text.toString().trim()
                val lastName = etLastName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                val email = etEmail.text.toString().trim()

                if (firstName.isEmpty() && lastName.isEmpty()) {
                    Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val customer = hashMapOf(
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "phone" to phone,
                    "email" to email
                )

                db.collection("Customers")
                    .add(customer)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Customer Added", Toast.LENGTH_SHORT).show()
                        loadCustomers()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
