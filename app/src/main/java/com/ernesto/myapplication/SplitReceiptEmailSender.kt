package com.ernesto.myapplication

import android.content.Context
import android.widget.Toast
import com.ernesto.myapplication.engine.SplitReceiptPayload
import com.google.firebase.functions.FirebaseFunctions

/**
 * Sends a per-guest split receipt via [sendReceiptEmail] with [splitReceipt] payload (SendGrid),
 * avoiding the system email/share sheet.
 */
object SplitReceiptEmailSender {

    fun send(
        context: Context,
        email: String,
        orderId: String,
        payload: SplitReceiptPayload,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        val data = hashMapOf<String, Any>(
            "email" to email,
            "orderId" to orderId,
            "splitReceipt" to payload.toFirestoreMap()
        )
        FirebaseFunctions.getInstance()
            .getHttpsCallable("sendReceiptEmail")
            .call(data)
            .addOnSuccessListener { result ->
                val response = result.data as? Map<*, *>
                if (response?.get("success") == true) {
                    Toast.makeText(context, "Receipt sent to $email", Toast.LENGTH_SHORT).show()
                    onSuccess?.invoke()
                } else {
                    Toast.makeText(context, "Failed to send receipt", Toast.LENGTH_SHORT).show()
                    onFailure?.invoke()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to send receipt. Please try again.", Toast.LENGTH_SHORT).show()
                onFailure?.invoke()
            }
    }
}
