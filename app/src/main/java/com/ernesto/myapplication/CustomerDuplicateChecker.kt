package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore

/**
 * Checks for duplicate customers in the Customers collection.
 * A duplicate is detected if either:
 *  - The email matches an existing customer (case-insensitive), OR
 *  - The full name matches an existing customer (case-insensitive)
 * Used by both CustomersActivity and MenuActivity (Cart Add Customer).
 */
object CustomerDuplicateChecker {

    fun checkExists(
        db: FirebaseFirestore,
        name: String,
        email: String,
        onResult: (Boolean) -> Unit
    ) {
        val normalizedName = name.trim().lowercase()
        val normalizedEmail = email.trim().lowercase()

        if (normalizedName.isEmpty() && normalizedEmail.isEmpty()) {
            onResult(false)
            return
        }

        db.collection("Customers")
            .get()
            .addOnSuccessListener { snap ->
                val exists = snap.documents.any { doc ->
                    val firstName = (doc.getString("firstName") ?: "").trim()
                    val lastName = (doc.getString("lastName") ?: "").trim()
                    val nameField = (doc.getString("name") ?: "").trim()
                    val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                        "$firstName $lastName".trim()
                    } else {
                        nameField
                    }
                    val docNameNorm = fullName.lowercase()
                    val docEmailNorm = (doc.getString("email") ?: "").trim().lowercase()

                    val emailMatch = normalizedEmail.isNotEmpty()
                            && docEmailNorm.isNotEmpty()
                            && docEmailNorm == normalizedEmail
                    val nameMatch = normalizedName.isNotEmpty()
                            && docNameNorm.isNotEmpty()
                            && docNameNorm == normalizedName

                    emailMatch || nameMatch
                }
                onResult(exists)
            }
            .addOnFailureListener { onResult(false) }
    }
}
