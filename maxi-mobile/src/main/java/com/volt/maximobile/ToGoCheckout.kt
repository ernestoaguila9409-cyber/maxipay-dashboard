package com.volt.maximobile

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.volt.shared.engine.OrderEngine
import com.volt.shared.engine.OrderTaxHelper
import java.util.Date
import java.util.UUID

data class CartLine(
    val menuItemId: String,
    val name: String,
    val unitPriceDollars: Double,
    val quantity: Int,
    val guestNumber: Int = 0,
    val taxMode: String = "INHERIT",
    val taxIds: List<String> = emptyList(),
)

object ToGoCheckout {

    private val db get() = FirebaseFirestore.getInstance()
    private val orderEngine get() = OrderEngine(db)

    private fun lineItemTaxFields(line: CartLine): Pair<String, List<String>> {
        val taxIds = line.taxIds.map { it.trim() }.filter { it.isNotEmpty() }
        val taxMode = OrderTaxHelper.effectiveTaxMode(line.taxMode, taxIds)
        return taxMode to taxIds
    }

    private fun finishWithRecomputedTotal(
        orderId: String,
        fallbackTotalCents: Long,
        onSuccess: (Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        orderEngine.recomputeOrderTotals(
            orderId = orderId,
            onSuccess = {
                MerchantFirestore.col("Orders").document(orderId).get()
                    .addOnSuccessListener { snap ->
                        val total = snap.getLong("totalInCents") ?: fallbackTotalCents
                        onSuccess(total)
                    }
                    .addOnFailureListener { onSuccess(fallbackTotalCents) }
            },
            onFailure = onFailure,
        )
    }

    fun createToGoOrderWithLines(
        employeeName: String,
        lines: List<CartLine>,
        orderType: String,
        tableId: String? = null,
        tableLayoutId: String? = null,
        joinedTableIds: List<String>? = null,
        tableName: String? = null,
        seatIds: List<String>? = null,
        seatName: String? = null,
        area: String? = null,
        onSuccess: (orderId: String, totalCents: Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (lines.isEmpty()) {
            onFailure(IllegalArgumentException("Cart is empty"))
            return
        }
        OrderNumberGenerator.nextOrderNumber(
            onSuccess = { orderNumber ->
                val subtotalCents = lines.sumOf { line ->
                    val unit = (line.unitPriceDollars * 100.0).toLong()
                    unit * line.quantity
                }
                val orderMap = hashMapOf<String, Any>(
                    "orderNumber" to orderNumber,
                    "employeeName" to employeeName,
                    "status" to "OPEN",
                    "createdAt" to Date(),
                    "updatedAt" to Date(),
                    "totalInCents" to subtotalCents,
                    "totalPaidInCents" to 0L,
                    "remainingInCents" to subtotalCents,
                    "orderType" to orderType,
                    "itemsCount" to lines.size.toLong(),
                    "taxBreakdown" to emptyList<Map<String, Any>>(),
                )
                if (!tableId.isNullOrBlank()) orderMap["tableId"] = tableId
                val layoutId = tableLayoutId?.trim().orEmpty()
                if (layoutId.isNotEmpty()) orderMap["tableLayoutId"] = layoutId
                val joined = joinedTableIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                if (!joined.isNullOrEmpty()) orderMap["joinedTableIds"] = joined
                if (!tableName.isNullOrBlank()) orderMap["tableName"] = tableName
                if (!seatName.isNullOrBlank()) orderMap["seatName"] = seatName
                val seats = seatIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
                if (seats.isNotEmpty()) {
                    orderMap["seatIds"] = seats
                    orderMap["guestCount"] = seats.size.toLong()
                }
                if (!area.isNullOrBlank()) orderMap["area"] = area

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
                            val (taxMode, taxIds) = lineItemTaxFields(line)
                            val itemMap = hashMapOf<String, Any>(
                                "itemId" to line.menuItemId,
                                "name" to line.name,
                                "quantity" to line.quantity,
                                "basePriceInCents" to basePriceInCents,
                                "modifiersTotalInCents" to 0L,
                                "unitPriceInCents" to unitPriceInCents,
                                "lineTotalInCents" to lineTotalInCents,
                                "modifiers" to emptyList<Any>(),
                                "taxMode" to taxMode,
                                "createdAt" to Date(),
                                "updatedAt" to Date(),
                            )
                            if (taxIds.isNotEmpty()) itemMap["taxIds"] = taxIds
                            if (line.guestNumber > 0) itemMap["guestNumber"] = line.guestNumber
                            batch.set(lineRef, itemMap, SetOptions.merge())
                        }
                        batch.update(orderRef, "updatedAt", Date())
                        batch.commit()
                            .addOnSuccessListener {
                                fun completeWithTotal(totalCents: Long) {
                                    if (orderType == "DINE_IN" && !tableId.isNullOrBlank()) {
                                        val layoutForMark = tableLayoutId?.trim().orEmpty().ifBlank { null }
                                        if (!joined.isNullOrEmpty() && joined.size > 1) {
                                            TableFirestoreHelper.markDineInJoinedTablesOccupied(
                                                db, joined, layoutForMark, oid,
                                            )
                                        } else {
                                            TableFirestoreHelper.markDineInTableOccupied(
                                                db, tableId, layoutForMark, oid,
                                            )
                                        }
                                    }
                                    if (orderType == "BAR_TAB" && seats.isNotEmpty()) {
                                        val seatBatch = db.batch()
                                        for (sid in seats) {
                                            seatBatch.update(
                                                MerchantFirestore.doc("Tables", sid),
                                                mapOf("currentOrderId" to oid),
                                            )
                                        }
                                        seatBatch.commit()
                                            .addOnSuccessListener { onSuccess(oid, totalCents) }
                                            .addOnFailureListener { e -> onFailure(e) }
                                    } else {
                                        onSuccess(oid, totalCents)
                                    }
                                }
                                finishWithRecomputedTotal(
                                    orderId = oid,
                                    fallbackTotalCents = subtotalCents,
                                    onSuccess = { completeWithTotal(it) },
                                    onFailure = { e -> onFailure(e) },
                                )
                            }
                            .addOnFailureListener { e -> onFailure(e) }
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            },
            onFailure = { e -> onFailure(e) },
        )
    }

    /** Adds cart lines to an existing OPEN order (e.g. created when seating a Dine-In table). */
    fun appendLinesToExistingOrder(
        orderId: String,
        lines: List<CartLine>,
        onSuccess: (totalCents: Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (lines.isEmpty()) {
            onFailure(IllegalArgumentException("Cart is empty"))
            return
        }
        val orderRef = MerchantFirestore.col("Orders").document(orderId)
        orderRef.get()
            .addOnSuccessListener { orderSnap ->
                if (!orderSnap.exists()) {
                    onFailure(IllegalStateException("Order not found"))
                    return@addOnSuccessListener
                }
                val newLinesCents = lines.sumOf { line ->
                    val unit = (line.unitPriceDollars * 100.0).toLong()
                    unit * line.quantity
                }
                val paid = orderSnap.getLong("totalPaidInCents") ?: 0L
                val prevTotal = orderSnap.getLong("totalInCents") ?: 0L
                val prevCount = orderSnap.getLong("itemsCount") ?: 0L
                val interimTotal = prevTotal + newLinesCents
                val batch = db.batch()
                for (line in lines) {
                    val lineKey = UUID.randomUUID().toString()
                    val lineRef = orderRef.collection("items").document(lineKey)
                    val basePriceInCents = (line.unitPriceDollars * 100).toLong()
                    val unitPriceInCents = basePriceInCents
                    val lineTotalInCents = unitPriceInCents * line.quantity
                    val (taxMode, taxIds) = lineItemTaxFields(line)
                    val itemMap = hashMapOf<String, Any>(
                        "itemId" to line.menuItemId,
                        "name" to line.name,
                        "quantity" to line.quantity,
                        "basePriceInCents" to basePriceInCents,
                        "modifiersTotalInCents" to 0L,
                        "unitPriceInCents" to unitPriceInCents,
                        "lineTotalInCents" to lineTotalInCents,
                        "modifiers" to emptyList<Any>(),
                        "taxMode" to taxMode,
                        "createdAt" to Date(),
                        "updatedAt" to Date(),
                    )
                    if (taxIds.isNotEmpty()) itemMap["taxIds"] = taxIds
                    if (line.guestNumber > 0) itemMap["guestNumber"] = line.guestNumber
                    batch.set(lineRef, itemMap, SetOptions.merge())
                }
                batch.update(
                    orderRef,
                    mapOf(
                        "totalInCents" to interimTotal,
                        "remainingInCents" to (interimTotal - paid).coerceAtLeast(0L),
                        "itemsCount" to prevCount + lines.size,
                        "updatedAt" to Date(),
                    ),
                )
                batch.commit()
                    .addOnSuccessListener {
                        finishWithRecomputedTotal(
                            orderId = orderId,
                            fallbackTotalCents = interimTotal,
                            onSuccess = { recomputedTotal ->
                                orderRef.update(
                                    mapOf(
                                        "remainingInCents" to (recomputedTotal - paid).coerceAtLeast(0L),
                                        "updatedAt" to Date(),
                                    ),
                                ).addOnSuccessListener { onSuccess(recomputedTotal) }
                                    .addOnFailureListener { onSuccess(recomputedTotal) }
                            },
                            onFailure = onFailure,
                        )
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
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
