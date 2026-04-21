package com.ernesto.myapplication

import com.google.firebase.Timestamp
import java.util.Locale

/**
 * Repeat-order detection for the cart "Same as usual?" suggestion, plus reusable helpers for
 * upcoming features (Favorites, smart recommendations) that also analyze [Customer.lastOrders].
 *
 * Source of truth for detection is a customer's recent closed orders (max 5). We prefer a fully
 * repeating combination (all the same items across visits) over single-item frequency — a single
 * repeated latte is weaker signal than "latte + croissant ordered twice".
 */

data class SummaryModifier(
    val name: String = "",
    val action: String = "ADD",
    val price: Double = 0.0,
    val groupId: String = "",
    val groupName: String = "",
    val children: List<SummaryModifier> = emptyList(),
) {
    fun toOrderModifier(): OrderModifier = OrderModifier(
        name = name,
        action = action,
        price = price,
        groupId = groupId,
        groupName = groupName,
        children = children.map { it.toOrderModifier() },
    )

    fun toFirestoreMap(): Map<String, Any> = buildMap {
        put("name", name)
        put("action", action)
        put("price", price)
        put("groupId", groupId)
        put("groupName", groupName)
        if (children.isNotEmpty()) {
            put("children", children.map { it.toFirestoreMap() })
        }
    }

    companion object {
        fun fromOrderModifier(m: OrderModifier): SummaryModifier = SummaryModifier(
            name = m.name,
            action = m.action,
            price = m.price,
            groupId = m.groupId,
            groupName = m.groupName,
            children = m.children.map { fromOrderModifier(it) },
        )

        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Any?): SummaryModifier {
            val m = raw as? Map<String, Any?> ?: return SummaryModifier()
            val childrenRaw = m["children"] as? List<*>
            return SummaryModifier(
                name = m["name"]?.toString().orEmpty(),
                action = m["action"]?.toString()?.takeIf { it.isNotBlank() } ?: "ADD",
                price = (m["price"] as? Number)?.toDouble() ?: 0.0,
                groupId = m["groupId"]?.toString().orEmpty(),
                groupName = m["groupName"]?.toString().orEmpty(),
                children = childrenRaw?.map { fromMap(it) }.orEmpty(),
            )
        }
    }
}

/**
 * One line within an [OrderSummary]. Keeps enough info to rebuild the line on a Repeat action
 * without re-reading the original order document (price and modifiers are the user's choice at
 * that visit; only stock and taxes are re-fetched from MenuItems).
 */
data class SummaryItem(
    val itemId: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val basePriceInCents: Long = 0L,
    val notes: String = "",
    val modifiers: List<SummaryModifier> = emptyList(),
) {
    fun toFirestoreMap(): Map<String, Any> = buildMap {
        put("itemId", itemId)
        put("name", name)
        put("quantity", quantity)
        put("basePriceInCents", basePriceInCents)
        if (notes.isNotBlank()) put("notes", notes)
        if (modifiers.isNotEmpty()) {
            put("modifiers", modifiers.map { it.toFirestoreMap() })
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Any?): SummaryItem? {
            val m = raw as? Map<String, Any?> ?: return null
            val id = m["itemId"]?.toString()?.trim().orEmpty()
            if (id.isEmpty()) return null
            val modsRaw = m["modifiers"] as? List<*>
            return SummaryItem(
                itemId = id,
                name = m["name"]?.toString().orEmpty(),
                quantity = ((m["quantity"] as? Number)?.toInt() ?: 1).coerceAtLeast(1),
                basePriceInCents = (m["basePriceInCents"] as? Number)?.toLong() ?: 0L,
                notes = m["notes"]?.toString().orEmpty(),
                modifiers = modsRaw?.map { SummaryModifier.fromMap(it) }.orEmpty(),
            )
        }
    }
}

data class OrderSummary(
    val orderId: String = "",
    val items: List<SummaryItem> = emptyList(),
    val totalInCents: Long = 0L,
    val createdAt: Timestamp? = null,
) {
    fun toFirestoreMap(): Map<String, Any?> = buildMap {
        put("orderId", orderId)
        put("items", items.map { it.toFirestoreMap() })
        put("totalInCents", totalInCents)
        put("createdAt", createdAt)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Any?): OrderSummary? {
            val m = raw as? Map<String, Any?> ?: return null
            val itemsRaw = m["items"] as? List<*> ?: return null
            val items = itemsRaw.mapNotNull { SummaryItem.fromMap(it) }
            if (items.isEmpty()) return null
            return OrderSummary(
                orderId = m["orderId"]?.toString().orEmpty(),
                items = items,
                totalInCents = (m["totalInCents"] as? Number)?.toLong() ?: 0L,
                createdAt = m["createdAt"] as? Timestamp,
            )
        }
    }
}

enum class RepeatPatternType { FULL_ORDER, ITEM_PATTERN, NONE }

data class RepeatPattern(
    val type: RepeatPatternType,
    val items: List<SummaryItem> = emptyList(),
    /** Only set for [RepeatPatternType.FULL_ORDER] — sum of the matched order. */
    val totalInCents: Long = 0L,
    /** Number of orders in the window that matched this pattern (≥ 2 when non-NONE). */
    val occurrences: Int = 0,
) {
    val isEmpty: Boolean get() = type == RepeatPatternType.NONE || items.isEmpty()

    companion object {
        val NONE: RepeatPattern = RepeatPattern(RepeatPatternType.NONE)
    }
}

/**
 * Rules (matches product spec):
 *  1. A combination (set of itemIds in an order) that repeats in **≥ 2 orders** wins as [FULL_ORDER].
 *  2. Otherwise, if any single itemId appears in **≥ 2 different orders**, return up to 3 of them
 *     sorted by frequency as [ITEM_PATTERN].
 *  3. Else [NONE].
 *
 * Note this function does **not** enforce `visitCount > 2` — the caller does, so detection can be
 * reused for reporting / other signals (e.g. Favorites) that don't gate on visit count.
 *
 * **Modifier rule:** grouping uses [SummaryItem.itemId] only (modifiers ignored). After a pattern
 * is chosen, modifiers are picked per item from **leaf** modifiers only: each distinct key counts
 * at most once per **order** (recent-window index), then keys that tie for **max** count and
 * **max ≥ 2** are kept—otherwise the suggestion uses no modifiers for that item (no consensus).
 */
fun detectFrequentOrderPattern(lastOrders: List<OrderSummary>): RepeatPattern {
    if (lastOrders.size < 2) return RepeatPattern.NONE
    val window = lastOrders.take(5)

    val comboKeys: List<List<String>> = window.map { o ->
        o.items.map { it.itemId.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()
    }.filter { it.isNotEmpty() }
    if (comboKeys.isEmpty()) return RepeatPattern.NONE

    val comboCounts = comboKeys.groupingBy { it }.eachCount()
    val bestCombo = comboCounts.maxByOrNull { it.value }
    if (bestCombo != null && bestCombo.value >= 2) {
        val winningKey = bestCombo.key
        val matched = window.first { o ->
            o.items.map { it.itemId.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList() == winningKey
        }
        val matchingOrders = window.filter { o ->
            o.items.map { it.itemId.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList() == winningKey
        }
        val rebuilt = matched.items.map { template ->
            val sourced = matchingOrders.flatMapIndexed { orderIdx, o ->
                o.items.filter { it.itemId.trim() == template.itemId.trim() }
                    .map { line -> orderIdx to line }
            }
            val mods = pickMostFrequentModifierLeaves(sourced)
            template.copy(modifiers = mods)
        }
        return RepeatPattern(
            type = RepeatPatternType.FULL_ORDER,
            items = rebuilt,
            totalInCents = matched.totalInCents,
            occurrences = bestCombo.value,
        )
    }

    val perOrderItemIds: List<Set<String>> = window.map { o ->
        o.items.map { it.itemId.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    val idCounts = mutableMapOf<String, Int>()
    for (set in perOrderItemIds) {
        for (id in set) idCounts.merge(id, 1) { a, _ -> a + 1 }
    }
    val frequent = idCounts.filter { it.value >= 2 }.keys
    if (frequent.isEmpty()) return RepeatPattern.NONE

    val topIds = frequent.sortedByDescending { idCounts[it] ?: 0 }.take(3)
    val latestForId: Map<String, SummaryItem> = buildMap {
        for (order in window) {
            for (line in order.items) {
                if (line.itemId in topIds && line.itemId !in this) {
                    put(line.itemId, line)
                }
            }
        }
    }
    val ordered = topIds.mapNotNull { id ->
        val template = latestForId[id] ?: return@mapNotNull null
        val sourced = window.flatMapIndexed { orderIdx, o ->
            o.items.filter { it.itemId.trim() == id.trim() }
                .map { line -> orderIdx to line }
        }
        val mods = pickMostFrequentModifierLeaves(sourced)
        template.copy(modifiers = mods)
    }
    if (ordered.isEmpty()) return RepeatPattern.NONE
    return RepeatPattern(
        type = RepeatPatternType.ITEM_PATTERN,
        items = ordered,
        totalInCents = 0L,
        occurrences = ordered.maxOf { idCounts[it.itemId] ?: 0 },
    )
}

/** Leaf modifiers only (skip non-leaf parents so group shells are not double-counted). */
private fun flattenModifierLeaves(mods: List<SummaryModifier>): List<SummaryModifier> {
    val out = mutableListOf<SummaryModifier>()
    for (m in mods) {
        if (m.children.isEmpty()) {
            out.add(m)
        } else {
            out.addAll(flattenModifierLeaves(m.children))
        }
    }
    return out
}

private fun modifierConsensusKey(m: SummaryModifier): String {
    val a = m.action.trim().uppercase(Locale.US)
    val n = m.name.trim().lowercase(Locale.US)
    val g = m.groupId.trim().lowercase(Locale.US)
    return "$a|$n|$g"
}

/**
 * [sourcedLines] is `(orderIndex, line)` for the same [SummaryItem.itemId] across the window;
 * [orderIndex] identifies a distinct order row (max one hit per key per order). Counts leaf
 * modifier keys, returns every leaf at the maximum count **only if** that maximum is at least 2.
 * Ties at the max all win.
 */
private fun pickMostFrequentModifierLeaves(
    sourcedLines: List<Pair<Int, SummaryItem>>,
): List<SummaryModifier> {
    if (sourcedLines.isEmpty()) return emptyList()
    val keyToOrderIndices = mutableMapOf<String, MutableSet<Int>>()
    val keyToExample = mutableMapOf<String, SummaryModifier>()
    for ((orderIdx, line) in sourcedLines) {
        for (m in flattenModifierLeaves(line.modifiers)) {
            val key = modifierConsensusKey(m)
            keyToOrderIndices.getOrPut(key) { mutableSetOf() }.add(orderIdx)
            keyToExample.putIfAbsent(key, m.copy(children = emptyList()))
        }
    }
    if (keyToOrderIndices.isEmpty()) return emptyList()
    val maxCount = keyToOrderIndices.values.maxOf { it.size }
    if (maxCount < 2) return emptyList()
    return keyToOrderIndices.entries
        .filter { it.value.size == maxCount }
        .sortedBy { it.key }
        .map { entry -> keyToExample[entry.key]!! }
}

/**
 * Short line for the suggestion card subtitle. Limits to 2–3 items and collapses overflow into
 * "… + N more".
 */
fun summarizePatternForCard(pattern: RepeatPattern): String {
    if (pattern.isEmpty) return ""
    val names = pattern.items.map { it.name.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return ""
    return when {
        names.size == 1 && pattern.type == RepeatPatternType.ITEM_PATTERN -> "${names[0]} (frequent)"
        names.size <= 3 -> names.joinToString(" + ")
        else -> "${names.take(2).joinToString(" + ")} + ${names.size - 2} more"
    }
}
