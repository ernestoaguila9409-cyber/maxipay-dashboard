package com.ernesto.myapplication

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.functions.FirebaseFunctions

object ReceiptPromptHelper {

    enum class ReceiptType(
        val title: String,
        val message: String,
        val cloudFunction: String
    ) {
        VOID(
            "Transaction Voided",
            "The transaction was voided successfully. Do you want to email a VOID receipt?",
            "sendVoidReceiptEmail"
        ),
        REFUND(
            "Refund Completed",
            "The refund was processed successfully. Do you want to email a REFUND receipt?",
            "sendRefundReceiptEmail"
        )
    }

    fun promptForReceipt(
        activity: Activity,
        type: ReceiptType,
        orderId: String,
        transactionId: String = "",
        onDismiss: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(activity)
            .setTitle(type.title)
            .setMessage(type.message)
            .setPositiveButton("Email Receipt") { _, _ ->
                showEmailInput(activity, type, orderId, transactionId, onDismiss)
            }
            .setNegativeButton("No") { _, _ -> onDismiss?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun showEmailInput(
        activity: Activity,
        type: ReceiptType,
        orderId: String,
        transactionId: String,
        onDismiss: (() -> Unit)?
    ) {
        val input = EditText(activity).apply {
            hint = "Enter email address"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(activity)
            .setTitle("Email Receipt")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(activity, "Please enter an email", Toast.LENGTH_SHORT).show()
                    onDismiss?.invoke()
                    return@setPositiveButton
                }
                sendReceipt(activity, type, email, orderId, transactionId, onDismiss)
            }
            .setNegativeButton("Cancel") { _, _ -> onDismiss?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun sendReceipt(
        activity: Activity,
        type: ReceiptType,
        email: String,
        orderId: String,
        transactionId: String,
        onDismiss: (() -> Unit)?
    ) {
        val data = hashMapOf(
            "email" to email,
            "orderId" to orderId,
            "transactionId" to transactionId
        )

        FirebaseFunctions.getInstance()
            .getHttpsCallable(type.cloudFunction)
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Toast.makeText(activity, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = (response?.get("error") as? String)?.takeIf { it.isNotBlank() }
                    val msg = if (errorMsg != null) "Failed to send receipt: $errorMsg" else "Failed to send receipt"
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
                onDismiss?.invoke()
            }
            .addOnFailureListener { e ->
                android.util.Log.e("ReceiptPromptHelper", "Receipt send failed", e)
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Please try again."
                Toast.makeText(activity, "Failed to send receipt. $msg", Toast.LENGTH_LONG).show()
                onDismiss?.invoke()
            }
    }
}
