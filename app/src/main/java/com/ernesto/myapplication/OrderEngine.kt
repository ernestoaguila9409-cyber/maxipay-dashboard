package com.ernesto.myapplication.engine

import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class OrderEngine(private val db: FirebaseFirestore) {

    data class LineItemInput(
        val itemId: String,
        val name: String,
        val quantity: Int,
        val basePrice: Double,
        val modifiers: List<Pair<String, Double>>
    )

    /** Create an order if orderId is null; otherwise keep existing orderId */
    fun ensureOrder(
        currentOrderId: String?,
        employeeName: String,
        onSuccess: (orderId: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (!currentOrderId.isNullOrBlank()) {
            onSuccess(currentOrderId)
            return
        }

        val orderMap = hashMapOf(
            "employeeName" to employeeName,
            "status" to "OPEN",
            "createdAt" to Date(),
            "updatedAt" to Date(),
            "totalInCents" to 0L,
            "totalPaidInCents" to 0L,
            "remainingInCents" to 0L
        )

        db.collection("Orders")
            .add(orderMap)
            .addOnSuccessListener { doc -> onSuccess(doc.id) }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /** Save/replace an item line doc (Orders/{orderId}/items/{lineKey}) and then recompute totals */
    fun upsertLineItem(
        orderId: String,
        lineKey: String,
        input: LineItemInput,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val modifiersTotalInCents =
            input.modifiers.sumOf { (it.second * 100).toLong() }

        val basePriceInCents = (input.basePrice * 100).toLong()

        val unitPriceInCents = basePriceInCents + modifiersTotalInCents

        val lineTotalInCents = unitPriceInCents * input.quantity

        val itemMap = hashMapOf(
            "itemId" to input.itemId,
            "name" to input.name,
            "quantity" to input.quantity,
            "basePriceInCents" to basePriceInCents,
            "modifiersTotalInCents" to modifiersTotalInCents,
            "unitPriceInCents" to unitPriceInCents,
            "lineTotalInCents" to lineTotalInCents,
            "modifiers" to input.modifiers,   // ⭐ ADD THIS LINE
            "updatedAt" to Date()
        )

        val orderRef = db.collection("Orders").document(orderId)

        orderRef.collection("items")
            .document(lineKey)
            .set(itemMap)
            .addOnSuccessListener {
                recomputeOrderTotals(orderId, onSuccess, onFailure)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /** Delete a line doc and then recompute totals */
    fun deleteLineItem(
        orderId: String,
        lineKey: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val orderRef = db.collection("Orders").document(orderId)

        orderRef.collection("items")
            .document(lineKey)
            .delete()
            .addOnSuccessListener {
                recomputeOrderTotals(orderId, onSuccess, onFailure)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /** Recompute total from items subcollection; update total + remainingBalance + status deterministically */
    fun recomputeOrderTotals(
        orderId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val orderRef = db.collection("Orders").document(orderId)

        orderRef.collection("items")
            .get()
            .addOnSuccessListener { itemsSnap ->

                var newTotalInCents = 0L

                for (doc in itemsSnap.documents) {
                    val lineTotal =
                        doc.getLong("lineTotalInCents") ?: 0L

                    newTotalInCents += lineTotal
                }

                db.runTransaction { trx ->

                    val orderSnap = trx.get(orderRef)

                    val totalPaidInCents =
                        orderSnap.getLong("totalPaidInCents") ?: 0L

                    val remainingInCents =
                        (newTotalInCents - totalPaidInCents)
                            .coerceAtLeast(0L)

                    trx.update(orderRef, mapOf(
                        "totalInCents" to newTotalInCents,
                        "remainingInCents" to remainingInCents,
                        "status" to if (remainingInCents > 0L) "OPEN" else "CLOSED",
                        "updatedAt" to Date()
                    ))
                }
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }
}