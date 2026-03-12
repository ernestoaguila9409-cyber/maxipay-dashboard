package com.ernesto.myapplication

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Reusable helper for capturing customer information (name, phone, email).
 * Used in Cart for To-Go orders and for email receipts.
 */
object CustomerDialogHelper {

    data class CustomerInfo(
        val id: String? = null,
        val name: String,
        val phone: String = "",
        val email: String = ""
    )

    private data class SavedCustomer(
        val id: String,
        val name: String,
        val phone: String,
        val email: String
    ) {
        override fun toString(): String = name
    }

    /**
     * Show a dialog to add or edit customer information.
     * @param activity The activity context
     * @param initialName Pre-filled name (optional)
     * @param initialPhone Pre-filled phone (optional)
     * @param initialEmail Pre-filled email (optional)
     * @param onSave Called with CustomerInfo when user taps Save. Name is required.
     */
    fun showCustomerDialog(
        activity: Activity,
        initialName: String = "",
        initialPhone: String = "",
        initialEmail: String = "",
        onSave: (CustomerInfo) -> Unit
    ) {
        val allCustomers = mutableListOf<SavedCustomer>()

        val searchInput = AutoCompleteTextView(activity).apply {
            hint = "Search saved customers..."
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(48, 32, 48, 24)
            threshold = 1
            setTextColor(Color.BLACK)
        }

        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(48, 8, 48, 8) }
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        }

        val sectionLabel = TextView(activity).apply {
            text = "Customer Details"
            setTypeface(null, Typeface.BOLD)
            setPadding(48, 16, 48, 8)
            setTextColor(Color.parseColor("#555555"))
            textSize = 13f
        }

        val nameInput = EditText(activity).apply {
            hint = "Customer Name (required)"
            setText(initialName)
            inputType = InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setPadding(48, 32, 48, 24)
        }

        val phoneInput = EditText(activity).apply {
            hint = "Phone (optional)"
            setText(initialPhone)
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(48, 16, 48, 24)
        }

        val emailInput = EditText(activity).apply {
            hint = "Email (optional)"
            setText(initialEmail)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 16, 48, 32)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchInput)
            addView(divider)
            addView(sectionLabel)
            addView(nameInput)
            addView(phoneInput)
            addView(emailInput)
        }

        val adapter = object : ArrayAdapter<SavedCustomer>(
            activity,
            android.R.layout.simple_list_item_1,
            allCustomers
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val customer = getItem(position) ?: return view
                val display = buildString {
                    append(customer.name)
                    if (customer.phone.isNotBlank()) append("  •  ${customer.phone}")
                }
                view.text = display
                view.setTextColor(Color.BLACK)
                view.textSize = 14f
                view.setPadding(48, 24, 48, 24)
                return view
            }
        }
        searchInput.setAdapter(adapter)

        var selectedCustomerId: String? = null

        searchInput.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            selectedCustomerId = selected.id
            nameInput.setText(selected.name)
            phoneInput.setText(selected.phone)
            emailInput.setText(selected.email)
            searchInput.setText("")
            searchInput.clearFocus()
            nameInput.requestFocus()
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Customer Information")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            loadSavedCustomers(allCustomers, adapter)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Customer name is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val info = CustomerInfo(
                    id = selectedCustomerId,
                    name = name,
                    phone = phoneInput.text.toString().trim(),
                    email = emailInput.text.toString().trim()
                )
                dialog.dismiss()
                onSave(info)
            }
        }
        dialog.show()
    }

    private fun loadSavedCustomers(
        allCustomers: MutableList<SavedCustomer>,
        adapter: ArrayAdapter<SavedCustomer>
    ) {
        FirebaseFirestore.getInstance().collection("Customers")
            .get()
            .addOnSuccessListener { snap ->
                allCustomers.clear()
                for (doc in snap.documents) {
                    val firstName = (doc.getString("firstName") ?: "").trim()
                    val lastName = (doc.getString("lastName") ?: "").trim()
                    val nameField = (doc.getString("name") ?: "").trim()
                    val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                        "$firstName $lastName".trim()
                    } else {
                        nameField
                    }
                    if (fullName.isBlank()) continue

                    allCustomers.add(
                        SavedCustomer(
                            id = doc.id,
                            name = fullName,
                            phone = doc.getString("phone") ?: "",
                            email = doc.getString("email") ?: ""
                        )
                    )
                }
                allCustomers.sortBy { it.name.lowercase() }
                adapter.notifyDataSetChanged()
            }
    }
}
