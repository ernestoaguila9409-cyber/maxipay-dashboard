package com.ernesto.myapplication

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import java.util.Date

/**
 * Read/write the per-customer recent-order cache used by the "Same as usual?" suggestion and any
 * future feature that needs a quick look at a customer's recent orders (Favorites, predictive
 * recommendations).
 *
 * **Read path** — [loadRecentHistory]:
 *  1. If the Customer doc already has `visitCount`, `lastOrders` (cache populated after a prior
 *     close on this device), return those.
 *  2. Otherwise **fallback** to querying `Orders` where customerId == X AND status == "CLOSED",
 *     ordered by createdAt desc, limit 5. Items for each order are fetched from the `items`
 *     subcollection. This ensures existing customers show the suggestion immediately, without a
 *     one-time migration.
 *
 * **Write path** — [updateAfterOrderClosed]:
 *  - `visitCount += 1`
 *  - `lastVisitAt = order.createdAt` (or now)
 *  - Prepend order summary into `lastOrders`, cap at [MAX_LAST_ORDERS].
 *  - VOIDED orders are skipped.
 */
class CustomerOrderHistoryRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    data class RecentHistory(
        val visitCount: Int,
        val lastVisitAt: Timestamp?,
        val lastOrders: List<OrderSummary>,
    ) {
        val isEligibleForSuggestion: Boolean
            get() = visitCount > 2 && lastOrders.size >= 2
    }

    fun loadRecentHistory(
        customerId: String,
        onSuccess: (RecentHistory) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val id = customerId.trim()
        if (id.isEmpty()) {
            onSuccess(RecentHistory(0, null, emptyList()))
            return
        }
        db.collection(COLLECTION_CUSTOMERS).document(id).get()
            .addOnSuccessListener { doc ->
                val cached = cachedHistoryOrNull(doc)
                if (cached != null) {
                    onSuccess(cached)
                } else {
                    loadHistoryFromOrders(id, onSuccess, onFailure)
                }
            }
            .addOnFailureListener { onFailure(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cachedHistoryOrNull(doc: DocumentSnapshot): RecentHistory? {
        if (!doc.exists()) return null
        val visitCount = (doc.getLong(FIELD_VISIT_COUNT) ?: return null).toInt()
        val lastVisitAt = doc.getTimestamp(FIELD_LAST_VISIT_AT)
        val rawOrders = doc.get(FIELD_LAST_ORDERS) as? List<*> ?: return null
        val summaries = rawOrders.mapNotNull { OrderSummary.fromMap(it) }
        if (summaries.isEmpty()) return null
        return RecentHistory(visitCount, lastVisitAt, summaries)
    }

    private fun loadHistoryFromOrders(
        customerId: String,
        onSuccess: (RecentHistory) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        db.collection(COLLECTION_ORDERS)
            .whereEqualTo(FIELD_CUSTOMER_ID, customerId)
            .whereEqualTo(FIELD_STATUS, STATUS_CLOSED)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(MAX_LAST_ORDERS.toLong())
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    onSuccess(RecentHistory(0, null, emptyList()))
                    return@addOnSuccessListener
                }
                val docs = snap.documents
                val visitCount = docs.size
                val lastVisit = docs.firstNotNullOfOrNull { it.getTimestamp("createdAt") }
                val itemsTasks: List<Task<QuerySnapshot>> = docs.map { d ->
                    d.reference.collection(COLLECTION_ITEMS).get()
                }
                Tasks.whenAllSuccess<QuerySnapshot>(itemsTasks)
                    .addOnSuccessListener { itemsSnaps ->
                        val summaries = docs.mapIndexedNotNull { idx, orderDoc ->
                            val itemDocs = itemsSnaps.getOrNull(idx)?.documents.orEmpty()
                            buildSummaryFromOrderDoc(orderDoc, itemDocs)
                        }
                        onSuccess(
                            RecentHistory(
                                visitCount = visitCount,
                                lastVisitAt = lastVisit,
                                lastOrders = summaries,
                            )
                        )
                    }
                    .addOnFailureListener { onFailure(it) }
            }
            .addOnFailureListener { onFailure(it) }
    }

    fun updateAfterOrderClosed(orderId: String) {
        val id = orderId.trim()
        if (id.isEmpty()) return
        val orderRef = db.collection(COLLECTION_ORDERS).document(id)
        orderRef.get().addOnSuccessListener { orderDoc ->
            if (!orderDoc.exists()) return@addOnSuccessListener
            val status = orderDoc.getString(FIELD_STATUS)?.trim()?.uppercase().orEmpty()
            if (status == "VOIDED") return@addOnSuccessListener
            val customerId = orderDoc.getString(FIELD_CUSTOMER_ID)?.trim().orEmpty()
            if (customerId.isEmpty()) return@addOnSuccessListener

            orderRef.collection(COLLECTION_ITEMS).get()
                .addOnSuccessListener { itemSnap ->
                    val summary = buildSummaryFromOrderDoc(orderDoc, itemSnap.documents) ?: return@addOnSuccessListener
                    writeSummaryIntoCustomer(customerId, summary)
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeSummaryIntoCustomer(customerId: String, incoming: OrderSummary) {
        val ref = db.collection(COLLECTION_CUSTOMERS).document(customerId)
        ref.get().addOnSuccessListener { snap ->
            val existing: List<OrderSummary> = (snap.get(FIELD_LAST_ORDERS) as? List<*>)
                ?.mapNotNull { OrderSummary.fromMap(it) }
                .orEmpty()
            val dedupedExisting = existing.filter { it.orderId != incoming.orderId }
            val merged = (listOf(incoming) + dedupedExisting).take(MAX_LAST_ORDERS)
            val updates = hashMapOf<String, Any>(
                FIELD_VISIT_COUNT to FieldValue.increment(1),
                FIELD_LAST_VISIT_AT to (incoming.createdAt ?: Timestamp(Date())),
                FIELD_LAST_ORDERS to merged.map { it.toFirestoreMap() },
            )
            ref.set(updates, com.google.firebase.firestore.SetOptions.merge())
        }
    }

    private fun buildSummaryFromOrderDoc(
        orderDoc: DocumentSnapshot,
        itemDocs: List<DocumentSnapshot>,
    ): OrderSummary? {
        val items = itemDocs.mapNotNull { itemDoc ->
            val itemId = itemDoc.getString("itemId")?.trim().orEmpty()
            if (itemId.isEmpty()) return@mapNotNull null
            val name = itemDoc.getString("name")?.trim().orEmpty()
            val quantity = (itemDoc.getLong("quantity") ?: 1L).toInt().coerceAtLeast(1)
            val basePriceInCents = itemDoc.getLong("basePriceInCents") ?: 0L
            val notes = itemDoc.getString("notes")?.trim().orEmpty()
            @Suppress("UNCHECKED_CAST")
            val modsRaw = itemDoc.get("modifiers") as? List<*>
            val mods = modsRaw?.map { SummaryModifier.fromMap(it) }.orEmpty()
            SummaryItem(
                itemId = itemId,
                name = name,
                quantity = quantity,
                basePriceInCents = basePriceInCents,
                notes = notes,
                modifiers = mods,
            )
        }
        if (items.isEmpty()) return null
        return OrderSummary(
            orderId = orderDoc.id,
            items = items,
            totalInCents = orderDoc.getLong("totalInCents") ?: 0L,
            createdAt = orderDoc.getTimestamp("createdAt"),
        )
    }

    companion object {
        const val MAX_LAST_ORDERS = 5

        private const val COLLECTION_CUSTOMERS = "Customers"
        private const val COLLECTION_ORDERS = "Orders"
        private const val COLLECTION_ITEMS = "items"
        private const val FIELD_VISIT_COUNT = "visitCount"
        private const val FIELD_LAST_VISIT_AT = "lastVisitAt"
        private const val FIELD_LAST_ORDERS = "lastOrders"
        private const val FIELD_CUSTOMER_ID = "customerId"
        private const val FIELD_STATUS = "status"
        private const val STATUS_CLOSED = "CLOSED"
    }
}
