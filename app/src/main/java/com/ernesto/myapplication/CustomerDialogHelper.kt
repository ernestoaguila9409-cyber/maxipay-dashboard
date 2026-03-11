package com.ernesto.myapplication

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Reusable helper for capturing customer information (name, phone, email).
 * Used in Cart for To-Go orders and for email receipts.
 */
object CustomerDialogHelper {

    data class CustomerInfo(
        val name: String,
        val phone: String = "",
        val email: String = ""
    )

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

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(nameInput)
            addView(phoneInput)
            addView(emailInput)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Customer Information")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Customer name is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val info = CustomerInfo(
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
}
