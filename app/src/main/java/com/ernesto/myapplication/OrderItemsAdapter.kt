package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale
import com.ernesto.myapplication.engine.MoneyUtils
class OrderItemsAdapter(
    private val items: List<DocumentSnapshot>
) : RecyclerView.Adapter<OrderItemsAdapter.ItemVH>() {

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val nameQty: TextView = view.findViewById(R.id.txtItemNameQty)
        val base: TextView = view.findViewById(R.id.txtItemBase)
        val modifiers: TextView = view.findViewById(R.id.txtItemModifiers)
        val lineTotal: TextView = view.findViewById(R.id.txtItemLineTotal)
        val payments: TextView = view.findViewById(R.id.txtItemPayments) // 🔥 NEW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_item, parent, false)
        return ItemVH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ItemVH, position: Int) {
        val doc = items[position]

        val name = doc.getString("name")
            ?: doc.getString("itemName")
            ?: "Item"

        val qty = (doc.getLong("qty")
            ?: doc.getLong("quantity")
            ?: 1L).toInt()

        val basePriceInCents =
            doc.getLong("basePriceInCents") ?: 0L

        val lineInCents =
            doc.getLong("lineTotalInCents") ?: 0L

        holder.nameQty.text = "$name (Qty: $qty)"

        // Base price
        // Base price
        if (basePriceInCents > 0L) {
            holder.base.visibility = View.VISIBLE
            holder.base.text = "Base: ${MoneyUtils.centsToDisplay(basePriceInCents)}"
        } else {
            holder.base.visibility = View.GONE
        }

        // Modifiers
        val modsText = buildModifiersText(doc)
        if (modsText.isNullOrBlank()) {
            holder.modifiers.visibility = View.GONE
        } else {
            holder.modifiers.visibility = View.VISIBLE
            holder.modifiers.text = modsText
        }

        holder.lineTotal.text =
            "Line Total: ${MoneyUtils.centsToDisplay(lineInCents)}"

        // ===============================
        // 🔥 PAYMENT BREAKDOWN
        // ===============================

// ===============================
// 🔥 PAYMENT BREAKDOWN
// ===============================

        val paymentsArray = doc.get("payments") as? List<Map<String, Any>>

        if (!paymentsArray.isNullOrEmpty()) {

            val totalsByType = mutableMapOf<String, Double>()
            var grandTotal = 0.0

            paymentsArray.forEach { payment ->

                val type = payment["type"]?.toString() ?: return@forEach
                val amount = (payment["amount"] as? Number)?.toDouble() ?: 0.0

                totalsByType[type] = (totalsByType[type] ?: 0.0) + amount
                grandTotal += amount
            }

            val lines = totalsByType.map { (type, amount) ->
                "$type: $${String.format(Locale.US, "%.2f", amount)}"
            }.toMutableList()

            lines.add("")
            lines.add("Total Collected: $${String.format(Locale.US, "%.2f", grandTotal)}")

            holder.payments.visibility = View.VISIBLE
            holder.payments.text = lines.joinToString("\n")

        } else {
            holder.payments.visibility = View.GONE
        }
    }

    private fun buildModifiersText(doc: DocumentSnapshot): String? {

        val raw = doc.get("modifiers") as? List<*> ?: return null
        if (raw.isEmpty()) return null

        val lines = raw.mapNotNull { item ->

            when (item) {

                // Case 1: stored as Pair map {first, second}
                is Map<*, *> -> {
                    val name = item["first"]?.toString() ?: return@mapNotNull null
                    val price = (item["second"] as? Number)?.toDouble() ?: 0.0

                    if (price > 0)
                        "   • $name (+$${String.format(Locale.US, "%.2f", price)})"
                    else
                        "   • $name"
                }

                // Case 2: stored as list ["Large", 0.01]
                is List<*> -> {
                    if (item.size < 2) return@mapNotNull null

                    val name = item[0]?.toString() ?: return@mapNotNull null
                    val price = (item[1] as? Number)?.toDouble() ?: 0.0

                    if (price > 0)
                        "   • $name (+$${String.format(Locale.US, "%.2f", price)})"
                    else
                        "   • $name"
                }

                else -> null
            }
        }

        if (lines.isEmpty()) return null

        return lines.joinToString("\n")
    }
}