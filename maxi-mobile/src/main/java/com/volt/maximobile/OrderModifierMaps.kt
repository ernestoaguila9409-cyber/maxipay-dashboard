package com.volt.maximobile

import com.volt.shared.data.OrderModifier

object OrderModifierMaps {

    @Suppress("UNCHECKED_CAST")
    fun toMap(mod: OrderModifier): HashMap<String, Any> {
        val map = hashMapOf<String, Any>(
            "name" to mod.name,
            "action" to mod.action,
            "price" to mod.price,
        )
        if (mod.groupId.isNotEmpty()) map["groupId"] = mod.groupId
        if (mod.groupName.isNotEmpty()) map["groupName"] = mod.groupName
        if (mod.children.isNotEmpty()) {
            map["children"] = mod.children.map { toMap(it) }
        }
        return map
    }

    @Suppress("UNCHECKED_CAST")
    fun fromMap(raw: Any?): OrderModifier {
        val m = raw as? Map<String, Any?> ?: return OrderModifier()
        val childrenRaw = m["children"] as? List<*>
        val children = childrenRaw?.mapNotNull { fromMap(it) }.orEmpty()
        return OrderModifier(
            name = m["name"]?.toString().orEmpty(),
            action = m["action"]?.toString()?.ifBlank { "ADD" } ?: "ADD",
            price = (m["price"] as? Number)?.toDouble() ?: 0.0,
            groupId = m["groupId"]?.toString().orEmpty(),
            groupName = m["groupName"]?.toString().orEmpty(),
            children = children,
        )
    }

    fun toIntentList(mods: List<OrderModifier>): ArrayList<HashMap<String, Any>> =
        ArrayList(mods.map { toMap(it) })

    fun fromIntentList(raw: ArrayList<HashMap<String, Any>>?): List<OrderModifier> =
        raw?.map { fromMap(it) }.orEmpty()
}
