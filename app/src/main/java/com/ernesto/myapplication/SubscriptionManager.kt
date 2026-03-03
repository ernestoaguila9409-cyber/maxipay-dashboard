package com.ernesto.myapplication.core

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object SubscriptionManager {

    private val db = FirebaseFirestore.getInstance()

    var isBlocked: Boolean = false
        private set

    private var listener: ListenerRegistration? = null

    fun startListening(merchantId: String) {

        stopListening()

        listener = db.collection("Merchants")
            .document(merchantId)
            .addSnapshotListener { snapshot, _ ->

                val status = snapshot
                    ?.getString("subscriptionStatus")
                    ?: "ACTIVE"

                isBlocked = (status == "BLOCKED")
            }
    }

    fun stopListening() {
        listener?.remove()
        listener = null
    }
}