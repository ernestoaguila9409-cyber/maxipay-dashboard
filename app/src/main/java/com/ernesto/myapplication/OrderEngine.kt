package com.ernesto.myapplication.engine

import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class OrderEngine(private val db: FirebaseFirestore) {

    private var pendingWrites = 0
    private val flushCallbacks = mutableListOf<() -> Unit>()

    data class LineItemInput(
        val itemId: String,
        val name: String,
        val quantity: Int,
        val basePrice: Double,
        val modifiers: List<Pair<String, Double>>,
        val guestNumber: Int = 0
    )

    /** Create an order if orderId is null; otherwise keep existing orderId */
    fun ensureOrder(
        currentOrderId: String?,
        employeeName: String,
        orderType: String = "",
        tableId: String? = null,
        tableName: String? = null,
        guestCount: Int? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        customerEmail: String? = null,
        onSuccess: (orderId: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (!currentOrderId.isNullOrBlank()) {
            onSuccess(currentOrderId)
            return
        }

        val orderMap = hashMapOf<String, Any>(
            "employeeName" to employeeName,
            "status" to "OPEN",
            "createdAt" to Date(),
            "updatedAt" to Date(),
            "totalInCents" to 0L,
            "totalPaidInCents" to 0L,
            "remainingInCents" to 0L,
            "orderType" to orderType
        )

        if (!tableId.isNullOrBlank()) orderMap["tableId"] = tableId
        if (!tableName.isNullOrBlank()) orderMap["tableName"] = tableName
        if (guestCount != null && guestCount > 0) orderMap["guestCount"] = guestCount
        if (!customerName.isNullOrBlank()) orderMap["customerName"] = customerName
        if (!customerPhone.isNullOrBlank()) orderMap["customerPhone"] = customerPhone
        if (!customerEmail.isNullOrBlank()) orderMap["customerEmail"] = customerEmail

        db.collection("Orders")
            .add(orderMap)
            .addOnSuccessListener { doc -> onSuccess(doc.id) }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /** Update customer fields on an existing order */
    fun updateOrderCustomer(
        orderId: String,
        customerName: String,
        customerPhone: String = "",
        customerEmail: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val updates = hashMapOf<String, Any>(
            "customerName" to customerName,
            "customerPhone" to customerPhone,
            "customerEmail" to customerEmail,
            "updatedAt" to Date()
        )
        db.collection("Orders").document(orderId)
            .update(updates)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /** Save/replace an item line doc (Orders/{orderId}/items/{lineKey}). Does NOT recompute totals. */
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

        val itemMap = hashMapOf<String, Any>(
            "itemId" to input.itemId,
            "name" to input.name,
            "quantity" to input.quantity,
            "basePriceInCents" to basePriceInCents,
            "modifiersTotalInCents" to modifiersTotalInCents,
            "unitPriceInCents" to unitPriceInCents,
            "lineTotalInCents" to lineTotalInCents,
            "modifiers" to input.modifiers,
            "updatedAt" to Date()
        )
        if (input.guestNumber > 0) itemMap["guestNumber"] = input.guestNumber

        val orderRef = db.collection("Orders").document(orderId)

        pendingWrites++

        orderRef.collection("items")
            .document(lineKey)
            .set(itemMap)
            .addOnSuccessListener {
                pendingWrites--
                drainFlushCallbacks()
                onSuccess()
            }
            .addOnFailureListener { e ->
                pendingWrites--
                drainFlushCallbacks()
                onFailure(e)
            }
    }

    /** Delete a line doc. Does NOT recompute totals. */
    fun deleteLineItem(
        orderId: String,
        lineKey: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val orderRef = db.collection("Orders").document(orderId)

        pendingWrites++

        orderRef.collection("items")
            .document(lineKey)
            .delete()
            .addOnSuccessListener {
                pendingWrites--
                drainFlushCallbacks()
                onSuccess()
            }
            .addOnFailureListener { e ->
                pendingWrites--
                drainFlushCallbacks()
                onFailure(e)
            }
    }

    /** Wait until all pending item writes have completed, then invoke callback. Fires immediately if nothing is pending. */
    fun waitForPendingWrites(callback: () -> Unit) {
        if (pendingWrites <= 0) {
            pendingWrites = 0
            callback()
        } else {
            flushCallbacks.add(callback)
        }
    }

    private fun drainFlushCallbacks() {
        if (pendingWrites <= 0) {
            pendingWrites = 0
            val callbacks = flushCallbacks.toList()
            flushCallbacks.clear()
            callbacks.forEach { it() }
        }
    }

    /** Recompute total from items subcollection + taxes; update total + remainingBalance + status. */
    fun recomputeOrderTotals(
        orderId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val orderRef = db.collection("Orders").document(orderId)

        orderRef.collection("items")
            .get()
            .addOnSuccessListener { itemsSnap ->

                var subtotalInCents = 0L

                for (doc in itemsSnap.documents) {
                    val lineTotal =
                        doc.getLong("lineTotalInCents") ?: 0L

                    subtotalInCents += lineTotal
                }

                db.collection("Taxes")
                    .whereEqualTo("enabled", true)
                    .get()
                    .addOnSuccessListener { taxesSnap ->
                        var newTotalInCents = subtotalInCents
                        val subtotalDollars = subtotalInCents / 100.0
                        val taxBreakdown = mutableListOf<Map<String, Any>>()

                        for (doc in taxesSnap.documents) {
                            val name = doc.getString("name") ?: continue
                            val type = doc.getString("type") ?: continue
                            val amount = doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble() ?: continue
                            val taxAmount = if (type == "PERCENTAGE") {
                                subtotalDollars * amount / 100.0
                            } else {
                                amount
                            }
                            val taxCents = (taxAmount * 100).toLong()
                            newTotalInCents += taxCents
                            taxBreakdown.add(mapOf(
                                "name" to name,
                                "amountInCents" to taxCents
                            ))
                        }

                        db.runTransaction { trx ->

                            val orderSnap = trx.get(orderRef)

                            val totalPaidInCents =
                                orderSnap.getLong("totalPaidInCents") ?: 0L

                            val remainingInCents =
                                (newTotalInCents - totalPaidInCents)
                                    .coerceAtLeast(0L)

                            val updates = mutableMapOf<String, Any>(
                                "totalInCents" to newTotalInCents,
                                "remainingInCents" to remainingInCents,
                                "status" to if (remainingInCents > 0L) "OPEN" else "CLOSED",
                                "updatedAt" to Date()
                            )
                            if (taxBreakdown.isNotEmpty()) {
                                updates["taxBreakdown"] = taxBreakdown
                            }
                            trx.update(orderRef, updates)
                        }
                            .addOnSuccessListener { onSuccess() }
                            .addOnFailureListener { e -> onFailure(e) }
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }
}
