package com.volt.maximobile

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date
import java.util.UUID

data class CartLine(
    val menuItemId: String,
    val name: String,
    val unitPriceDollars: Double,
    val quantity: Int,
)

object ToGoCheckout {

    private val db get() = FirebaseFirestore.getInstance()

    fun createToGoOrderWithLines(
        employeeName: String,
        lines: List<CartLine>,
        onSuccess: (orderId: String, totalCents: Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (lines.isEmpty()) {
            onFailure(IllegalArgumentException("Cart is empty"))
            return
        }
        OrderNumberGenerator.nextOrderNumber(
            onSuccess = { orderNumber ->
                val totalCents = lines.sumOf { line ->
                    val unit = (line.unitPriceDollars * 100.0).toLong()
                    unit * line.quantity
                }
                val orderMap = hashMapOf<String, Any>(
                    "orderNumber" to orderNumber,
                    "employeeName" to employeeName,
                    "status" to "OPEN",
                    "createdAt" to Date(),
                    "updatedAt" to Date(),
                    "totalInCents" to totalCents,
                    "totalPaidInCents" to 0L,
                    "remainingInCents" to totalCents,
                    "orderType" to "TO_GO",
                    "itemsCount" to lines.size.toLong(),
                )
                MerchantFirestore.col("Orders")
                    .add(orderMap)
                    .addOnSuccessListener { doc ->
                        val oid = doc.id
                        val orderRef = MerchantFirestore.col("Orders").document(oid)
                        val batch = db.batch()
                        for (line in lines) {
                            val lineKey = UUID.randomUUID().toString()
                            val lineRef = orderRef.collection("items").document(lineKey)
                            val basePriceInCents = (line.unitPriceDollars * 100).toLong()
                            val unitPriceInCents = basePriceInCents
                            val lineTotalInCents = unitPriceInCents * line.quantity
                            val itemMap = hashMapOf<String, Any>(
                                "itemId" to line.menuItemId,
                                "name" to line.name,
                                "quantity" to line.quantity,
                                "basePriceInCents" to basePriceInCents,
                                "modifiersTotalInCents" to 0L,
                                "unitPriceInCents" to unitPriceInCents,
                                "lineTotalInCents" to lineTotalInCents,
                                "modifiers" to emptyList<Any>(),
                                "taxMode" to "INHERIT",
                                "createdAt" to Date(),
                                "updatedAt" to Date(),
                            )
                            batch.set(lineRef, itemMap, SetOptions.merge())
                        }
                        batch.update(orderRef, "updatedAt", Date())
                        batch.commit()
                            .addOnSuccessListener { onSuccess(oid, totalCents) }
                            .addOnFailureListener { e -> onFailure(e) }
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            },
            onFailure = { e -> onFailure(e) },
        )
    }

    fun fetchOpenBatchId(onResult: (String) -> Unit, onFailure: (Exception) -> Unit) {
        MerchantFirestore.col("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val id = snap.documents.firstOrNull()?.id.orEmpty()
                onResult(id)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }
}
