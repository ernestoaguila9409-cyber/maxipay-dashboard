package com.ernesto.myapplication.engine

import com.ernesto.myapplication.OrderModifier
import com.ernesto.myapplication.OrderNumberGenerator
import com.google.firebase.firestore.FieldValue
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
        val modifiers: List<OrderModifier>,
        val guestNumber: Int = 0,
        val taxMode: String = "INHERIT",
        val taxIds: List<String> = emptyList()
    )

    /** Create an order if orderId is null; otherwise keep existing orderId */
    fun ensureOrder(
        currentOrderId: String?,
        employeeName: String,
        orderType: String = "",
        tableId: String? = null,
        tableName: String? = null,
        sectionId: String? = null,
        sectionName: String? = null,
        guestCount: Int? = null,
        guestNames: List<String>? = null,
        seatName: String? = null,
        area: String? = null,
        customerId: String? = null,
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

        OrderNumberGenerator.nextOrderNumber(
            onSuccess = { orderNumber ->
                val orderMap = hashMapOf<String, Any>(
                    "orderNumber" to orderNumber,
                    "employeeName" to employeeName,
                    "status" to "OPEN",
                    "createdAt" to Date(),
                    "updatedAt" to Date(),
                    "totalInCents" to 0L,
                    "totalPaidInCents" to 0L,
                    "remainingInCents" to 0L,
                    "orderType" to orderType,
                    "itemsCount" to 0L
                )

                if (!tableId.isNullOrBlank()) orderMap["tableId"] = tableId
                if (!tableName.isNullOrBlank()) orderMap["tableName"] = tableName
                if (!sectionId.isNullOrBlank()) orderMap["sectionId"] = sectionId
                if (!sectionName.isNullOrBlank()) orderMap["sectionName"] = sectionName
                if (guestCount != null && guestCount > 0) orderMap["guestCount"] = guestCount
                if (!guestNames.isNullOrEmpty()) orderMap["guestNames"] = guestNames!!
                if (!seatName.isNullOrBlank()) orderMap["seatName"] = seatName
                if (!area.isNullOrBlank()) orderMap["area"] = area
                if (!customerId.isNullOrBlank()) orderMap["customerId"] = customerId
                if (!customerName.isNullOrBlank()) orderMap["customerName"] = customerName
                if (!customerPhone.isNullOrBlank()) orderMap["customerPhone"] = customerPhone
                if (!customerEmail.isNullOrBlank()) orderMap["customerEmail"] = customerEmail

                db.collection("Orders")
                    .add(orderMap)
                    .addOnSuccessListener { doc -> onSuccess(doc.id) }
                    .addOnFailureListener { e -> onFailure(e) }
            },
            onFailure = { e -> onFailure(e) }
        )
    }

    /** Update customer fields on an existing order */
    fun updateOrderCustomer(
        orderId: String,
        customerId: String? = null,
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
        if (!customerId.isNullOrBlank()) updates["customerId"] = customerId
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
        isNewLine: Boolean = false,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val modifiersTotalInCents =
            input.modifiers.filter { it.action == "ADD" }.sumOf { (it.price * 100).toLong() }

        val basePriceInCents = (input.basePrice * 100).toLong()

        val unitPriceInCents = basePriceInCents + modifiersTotalInCents

        val lineTotalInCents = unitPriceInCents * input.quantity

        val modifierMaps = input.modifiers.map { mod ->
            hashMapOf(
                "name" to mod.name,
                "action" to mod.action,
                "price" to mod.price
            )
        }

        val itemMap = hashMapOf<String, Any>(
            "itemId" to input.itemId,
            "name" to input.name,
            "quantity" to input.quantity,
            "basePriceInCents" to basePriceInCents,
            "modifiersTotalInCents" to modifiersTotalInCents,
            "unitPriceInCents" to unitPriceInCents,
            "lineTotalInCents" to lineTotalInCents,
            "modifiers" to modifierMaps,
            "taxMode" to input.taxMode,
            "updatedAt" to Date()
        )
        if (input.taxIds.isNotEmpty()) itemMap["taxIds"] = input.taxIds
        if (input.guestNumber > 0) itemMap["guestNumber"] = input.guestNumber

        val orderRef = db.collection("Orders").document(orderId)

        if (isNewLine) {
            orderRef.update("itemsCount", FieldValue.increment(1))
        }

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

        orderRef.update("itemsCount", FieldValue.increment(-1))

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

    /** Recompute total from items subcollection + taxes – discounts; update total + remainingBalance + status. */
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

                data class LineInfo(val lineKey: String, val lineTotalInCents: Long, val taxMode: String, val taxIds: List<String>)
                val lineInfos = mutableListOf<LineInfo>()

                for (doc in itemsSnap.documents) {
                    val lineTotal = doc.getLong("lineTotalInCents") ?: 0L
                    subtotalInCents += lineTotal
                    val mode = doc.getString("taxMode") ?: "INHERIT"
                    @Suppress("UNCHECKED_CAST")
                    val ids = (doc.get("taxIds") as? List<String>) ?: emptyList()
                    lineInfos.add(LineInfo(doc.id, lineTotal, mode, ids))
                }

                db.collection("Taxes")
                    .get()
                    .addOnSuccessListener { taxesSnap ->

                        orderRef.get().addOnSuccessListener { orderDoc ->
                            val discountInCents = orderDoc.getLong("discountInCents") ?: 0L
                            val discountedSubtotalInCents = (subtotalInCents - discountInCents).coerceAtLeast(0L)

                            var newTotalInCents = discountedSubtotalInCents
                            val taxBreakdown = mutableListOf<Map<String, Any>>()
                            val perItemTaxCents = mutableMapOf<String, Long>()
                            val perItemTaxBreakdown = mutableMapOf<String, MutableList<Map<String, Any>>>()
                            lineInfos.forEach {
                                perItemTaxCents[it.lineKey] = 0L
                                perItemTaxBreakdown[it.lineKey] = mutableListOf()
                            }

                            for (taxDoc in taxesSnap.documents) {
                                val taxId = taxDoc.id
                                val name = taxDoc.getString("name") ?: continue
                                val type = taxDoc.getString("type") ?: continue
                                val amount = taxDoc.getDouble("amount") ?: taxDoc.getLong("amount")?.toDouble() ?: continue
                                val taxEnabled = taxDoc.getBoolean("enabled") ?: true

                                var taxableBaseCents = 0L
                                val taxableItems = mutableListOf<Pair<String, Long>>()
                                for (li in lineInfos) {
                                    val effectiveLineCents = if (subtotalInCents > 0) {
                                        (li.lineTotalInCents.toDouble() / subtotalInCents * discountedSubtotalInCents).toLong()
                                    } else 0L

                                    val isTaxable = if (li.taxMode == "FORCE_APPLY") {
                                        li.taxIds.contains(taxId)
                                    } else {
                                        taxEnabled
                                    }
                                    if (isTaxable) {
                                        taxableBaseCents += effectiveLineCents
                                        taxableItems.add(li.lineKey to effectiveLineCents)
                                    }
                                }
                                if (taxableBaseCents <= 0L) continue

                                val taxCents = if (type == "PERCENTAGE") {
                                    ((taxableBaseCents / 100.0) * amount / 100.0 * 100).toLong()
                                } else {
                                    (amount * 100).toLong()
                                }
                                newTotalInCents += taxCents
                                taxBreakdown.add(mapOf(
                                    "name" to name,
                                    "rate" to amount,
                                    "taxType" to type,
                                    "amountInCents" to taxCents
                                ))

                                if (taxCents > 0 && taxableBaseCents > 0 && taxableItems.isNotEmpty()) {
                                    var distributed = 0L
                                    for ((idx, pair) in taxableItems.withIndex()) {
                                        val (itemKey, effectiveCents) = pair
                                        val share = if (idx == taxableItems.size - 1) {
                                            taxCents - distributed
                                        } else {
                                            Math.round(taxCents.toDouble() * effectiveCents / taxableBaseCents)
                                        }
                                        perItemTaxCents[itemKey] = (perItemTaxCents[itemKey] ?: 0L) + share
                                        perItemTaxBreakdown.getOrPut(itemKey) { mutableListOf() }.add(mapOf(
                                            "name" to name,
                                            "rate" to amount,
                                            "amountInCents" to share
                                        ))
                                        distributed += share
                                    }
                                }
                            }

                            db.runTransaction { trx ->

                                val orderSnap = trx.get(orderRef)

                                val totalPaidInCents =
                                    orderSnap.getLong("totalPaidInCents") ?: 0L

                                val remainingInCents =
                                    (newTotalInCents - totalPaidInCents)
                                        .coerceAtLeast(0L)

                                val newStatus = when {
                                    remainingInCents > 0L -> "OPEN"
                                    totalPaidInCents > 0L -> "CLOSED"
                                    else -> orderSnap.getString("status") ?: "OPEN"
                                }

                                val updates = mutableMapOf<String, Any>(
                                    "totalInCents" to newTotalInCents,
                                    "remainingInCents" to remainingInCents,
                                    "status" to newStatus,
                                    "updatedAt" to Date()
                                )
                                if (taxBreakdown.isNotEmpty()) {
                                    updates["taxBreakdown"] = taxBreakdown
                                }
                                trx.update(orderRef, updates)
                            }
                                .addOnSuccessListener {
                                    val itemBatch = db.batch()
                                    for (li in lineInfos) {
                                        val taxAmount = perItemTaxCents[li.lineKey] ?: 0L
                                        val effectiveLineCents = if (subtotalInCents > 0) {
                                            Math.round(li.lineTotalInCents.toDouble() / subtotalInCents * discountedSubtotalInCents)
                                        } else 0L
                                        val ref = orderRef.collection("items").document(li.lineKey)
                                        val itemTaxBreakdown = perItemTaxBreakdown[li.lineKey] ?: emptyList<Map<String, Any>>()
                                        itemBatch.update(ref, mapOf(
                                            "lineTaxInCents" to taxAmount,
                                            "lineTotalWithTaxInCents" to effectiveLineCents + taxAmount,
                                            "taxBreakdown" to itemTaxBreakdown
                                        ))
                                    }
                                    if (lineInfos.isNotEmpty()) {
                                        itemBatch.commit()
                                            .addOnSuccessListener { onSuccess() }
                                            .addOnFailureListener { onSuccess() }
                                    } else {
                                        onSuccess()
                                    }
                                }
                                .addOnFailureListener { e -> onFailure(e) }
                        }.addOnFailureListener { e -> onFailure(e) }
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }
}
