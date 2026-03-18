package com.ernesto.myapplication.engine

import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Date

data class ItemSalesRow(
    val itemName: String,
    val quantitySold: Int,
    val totalRevenueCents: Long
)

data class CategorySalesRow(
    val categoryName: String,
    val totalRevenueCents: Long
)

data class ModifierSalesRow(
    val modifierName: String,
    val action: String,
    val itemName: String,
    val totalExtraCents: Long,
    val usageCount: Int
)

class MenuPerformanceEngine(private val db: FirebaseFirestore) {

    fun dateRange(startDate: Date, endDate: Date): Pair<Date, Date> {
        val startCal = Calendar.getInstance().apply {
            time = startDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            time = endDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return startCal.time to endCal.time
    }

    fun getItemSalesReport(
        startDate: Date,
        endDate: Date,
        employeeName: String? = null,
        onSuccess: (List<ItemSalesRow>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        db.collection("Orders")
            .whereEqualTo("status", "CLOSED")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener { orderSnap ->
                val filteredDocs = if (employeeName != null)
                    orderSnap.documents.filter { it.getString("employeeName") == employeeName }
                else orderSnap.documents
                val byItem = mutableMapOf<String, Pair<Int, Long>>()
                var pending = filteredDocs.size

                if (pending == 0) {
                    onSuccess(emptyList())
                    return@addOnSuccessListener
                }

                val lock = Any()
                for (orderDoc in filteredDocs) {
                    db.collection("Orders").document(orderDoc.id).collection("items").get()
                        .addOnSuccessListener { itemsSnap ->
                            for (itemDoc in itemsSnap.documents) {
                                val name = itemDoc.getString("name") ?: itemDoc.getString("itemName") ?: "Unknown"
                                val qty = (itemDoc.getLong("quantity") ?: 1L).toInt()
                                val lineCents = itemDoc.getLong("lineTotalInCents")
                                    ?: (itemDoc.getLong("unitPriceInCents") ?: 0L) * qty

                                synchronized(lock) {
                                    val cur = byItem[name] ?: (0 to 0L)
                                    byItem[name] = (cur.first + qty) to (cur.second + lineCents)
                                }
                            }
                            synchronized(lock) {
                                if (--pending == 0) {
                                    val result = byItem.map { (name, p) ->
                                        ItemSalesRow(name, p.first, p.second)
                                    }.sortedByDescending { it.totalRevenueCents }
                                    onSuccess(result)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            synchronized(lock) {
                                if (--pending == 0) onFailure(e)
                            }
                        }
                }
            }
            .addOnFailureListener(onFailure)
    }

    fun getCategorySalesReport(
        startDate: Date,
        endDate: Date,
        employeeName: String? = null,
        onSuccess: (List<CategorySalesRow>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        db.collection("Orders")
            .whereEqualTo("status", "CLOSED")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener { orderSnap ->
                val filteredDocs = if (employeeName != null)
                    orderSnap.documents.filter { it.getString("employeeName") == employeeName }
                else orderSnap.documents
                val byCategory = mutableMapOf<String, Long>()
                val categoryNames = mutableMapOf<String, String>()
                val itemToCategory = mutableMapOf<String, String>()

                fun loadCategoriesAndItems(callback: () -> Unit) {
                    db.collection("Categories").get()
                        .addOnSuccessListener { catSnap ->
                            for (doc in catSnap.documents) {
                                categoryNames[doc.id] = doc.getString("name") ?: doc.id
                            }
                            db.collection("MenuItems").get()
                                .addOnSuccessListener { itemSnap ->
                                    for (doc in itemSnap.documents) {
                                        val catId = doc.getString("categoryId") ?: continue
                                        val catName = categoryNames[catId] ?: catId
                                        itemToCategory[doc.id] = catName
                                    }
                                    callback()
                                }
                                .addOnFailureListener { callback() }
                        }
                        .addOnFailureListener { callback() }
                }

                loadCategoriesAndItems {
                    var pending = filteredDocs.size
                    if (pending == 0) {
                        onSuccess(emptyList())
                        return@loadCategoriesAndItems
                    }

                val catLock = Any()
                for (orderDoc in filteredDocs) {
                    db.collection("Orders").document(orderDoc.id).collection("items").get()
                        .addOnSuccessListener { itemsSnap ->
                            for (itemDoc in itemsSnap.documents) {
                                val itemId = itemDoc.getString("itemId") ?: continue
                                val catName = itemToCategory[itemId] ?: "Uncategorized"
                                val lineCents = itemDoc.getLong("lineTotalInCents")
                                    ?: (itemDoc.getLong("unitPriceInCents") ?: 0L) * (itemDoc.getLong("quantity") ?: 1L).toInt()

                                synchronized(catLock) {
                                    byCategory[catName] = (byCategory[catName] ?: 0L) + lineCents
                                }
                            }
                            synchronized(catLock) {
                                if (--pending == 0) {
                                    val result = byCategory.map { (name, cents) ->
                                        CategorySalesRow(name, cents)
                                    }.sortedByDescending { it.totalRevenueCents }
                                    onSuccess(result)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            synchronized(catLock) {
                                if (--pending == 0) onFailure(e)
                            }
                        }
                }
                }
            }
            .addOnFailureListener(onFailure)
    }

    fun getModifierSalesReport(
        startDate: Date,
        endDate: Date,
        employeeName: String? = null,
        onSuccess: (List<ModifierSalesRow>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val (start, end) = dateRange(startDate, endDate)

        db.collection("Orders")
            .whereEqualTo("status", "CLOSED")
            .whereGreaterThanOrEqualTo("createdAt", start)
            .whereLessThan("createdAt", end)
            .get()
            .addOnSuccessListener { orderSnap ->
                val filteredDocs = if (employeeName != null)
                    orderSnap.documents.filter { it.getString("employeeName") == employeeName }
                else orderSnap.documents
                val byModifier = mutableMapOf<Triple<String, String, String>, Pair<Long, Int>>()

                var pending = filteredDocs.size
                if (pending == 0) {
                    onSuccess(emptyList())
                    return@addOnSuccessListener
                }

                for (orderDoc in filteredDocs) {
                    db.collection("Orders").document(orderDoc.id).collection("items").get()
                        .addOnSuccessListener { itemsSnap ->
                            for (itemDoc in itemsSnap.documents) {
                                @Suppress("UNCHECKED_CAST")
                                val mods = itemDoc.get("modifiers") as? List<Map<String, Any>> ?: emptyList()
                                val qty = (itemDoc.getLong("quantity") ?: 1L).toInt()
                                val itemName = itemDoc.getString("name")
                                    ?: itemDoc.getString("itemName") ?: "Unknown"

                                for (mod in mods) {
                                    val modName = (mod["name"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                                    val action = (mod["action"] as? String) ?: "ADD"
                                    val pricePer = (mod["price"] as? Number)?.toDouble() ?: 0.0
                                    val extraCents = Math.round(pricePer * qty * 100)

                                    val key = Triple(itemName, modName, action)
                                    val cur = byModifier[key] ?: (0L to 0)
                                    byModifier[key] = (cur.first + extraCents) to (cur.second + qty)
                                }
                            }
                            if (--pending == 0) {
                                val result = byModifier.map { (key, value) ->
                                    ModifierSalesRow(
                                        modifierName = key.second,
                                        action = key.third,
                                        itemName = key.first,
                                        totalExtraCents = value.first,
                                        usageCount = value.second
                                    )
                                }.sortedWith(
                                    compareBy<ModifierSalesRow> { it.itemName }
                                        .thenByDescending { it.usageCount }
                                )
                                onSuccess(result)
                            }
                        }
                        .addOnFailureListener { e ->
                            if (--pending == 0) onFailure(e)
                        }
                }
            }
            .addOnFailureListener(onFailure)
    }
}
