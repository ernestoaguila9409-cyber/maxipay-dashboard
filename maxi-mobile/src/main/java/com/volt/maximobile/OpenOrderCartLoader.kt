package com.volt.maximobile

import com.google.firebase.firestore.FirebaseFirestore
import com.volt.shared.MerchantFirestore
import com.volt.shared.engine.OrderEngine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Metadata + cart lines when resuming an open Firestore order in [MainActivity]. */
data class OpenOrderCartContext(
    val lines: List<CartLine>,
    /** Firestore `orderType` (e.g. DINE_IN, BAR_TAB, TO_GO). */
    val orderType: String,
    val tableId: String?,
    val tableName: String?,
    val tableLayoutId: String?,
    val batchId: String?,
    val guestCount: Int,
    val guestNames: List<String>,
    val employeeName: String?,
)

object OpenOrderCartLoader {

    suspend fun load(orderId: String): OpenOrderCartContext = withContext(Dispatchers.IO) {
        val orderDoc = MerchantFirestore.col("Orders").document(orderId).get().await()
        if (!orderDoc.exists()) {
            throw IllegalStateException("Order not found")
        }

        val orderType = orderDoc.getString("orderType")?.trim().orEmpty().ifBlank { "TO_GO" }

        val itemsSnap = MerchantFirestore.col("Orders").document(orderId).collection("items").get().await()
        val lines = itemsSnap.documents.mapNotNull { doc ->
            val itemId = doc.getString("itemId")?.trim().orEmpty()
            if (itemId.isEmpty()) return@mapNotNull null
            val name = doc.getString("name")?.trim().orEmpty().ifBlank { "Item" }
            val qty = (doc.getLong("quantity") ?: 1L).toInt().coerceAtLeast(1)
            val basePriceInCents = doc.getLong("basePriceInCents")
                ?: doc.getLong("unitPriceInCents")
                ?: 0L
            val basePrice = basePriceInCents / 100.0
            @Suppress("UNCHECKED_CAST")
            val modsRaw = doc.get("modifiers") as? List<*>
            val modifiers = modsRaw?.mapNotNull { raw ->
                try {
                    OrderModifierMaps.fromMap(raw)
                } catch (_: Exception) {
                    null
                }
            }.orEmpty()
            val guest = (doc.getLong("guestNumber") ?: 0L).toInt()
            val taxMode = doc.getString("taxMode") ?: "INHERIT"
            @Suppress("UNCHECKED_CAST")
            val taxIds = (doc.get("taxIds") as? List<String>) ?: emptyList()
            CartLine(
                menuItemId = itemId,
                name = name,
                basePriceDollars = basePrice,
                modifiers = modifiers,
                quantity = qty,
                guestNumber = guest,
                taxMode = taxMode,
                taxIds = taxIds,
                firestoreLineKey = doc.id,
            )
        }

        if (lines.isEmpty()) {
            throw IllegalStateException("Order has no items")
        }

        suspendCoroutine { cont ->
            OrderEngine(FirebaseFirestore.getInstance()).recomputeOrderTotals(
                orderId = orderId,
                onSuccess = { cont.resume(Unit) },
                onFailure = { cont.resumeWithException(it) },
            )
        }

        val guestCount = (orderDoc.getLong("guestCount") ?: 0L).toInt()
        @Suppress("UNCHECKED_CAST")
        val guestNames = (orderDoc.get("guestNames") as? List<String>)?.filter { it.isNotBlank() }.orEmpty()

        OpenOrderCartContext(
            lines = lines,
            orderType = orderType,
            tableId = orderDoc.getString("tableId")?.trim()?.takeIf { it.isNotEmpty() },
            tableName = orderDoc.getString("tableName")?.trim()?.takeIf { it.isNotEmpty() },
            tableLayoutId = orderDoc.getString("tableLayoutId")?.trim()?.takeIf { it.isNotEmpty() },
            batchId = orderDoc.getString("batchId")?.trim()?.takeIf { it.isNotEmpty() },
            guestCount = guestCount,
            guestNames = guestNames,
            employeeName = orderDoc.getString("employeeName")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
