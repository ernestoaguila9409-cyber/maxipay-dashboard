package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale

class OrderItemsAdapter(
    private val items: List<DocumentSnapshot>
) : RecyclerView.Adapter<OrderItemsAdapter.ItemVH>() {

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val nameQty: TextView = view.findViewById(R.id.txtItemNameQty)
        val base: TextView = view.findViewById(R.id.txtItemBase)
        val modifiers: TextView = view.findViewById(R.id.txtItemModifiers)
        val lineTotal: TextView = view.findViewById(R.id.txtItemLineTotal)
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

        // Base price (we will hide if 0)
        val basePrice = doc.getDouble("basePrice")
            ?: doc.getDouble("base")
            ?: doc.getDouble("unitPrice") // fallback if you stored it as unitPrice
            ?: 0.0

        // Line total (final price including modifiers)
        val line = doc.getDouble("lineTotal")
            ?: doc.getDouble("line")
            ?: doc.getDouble("total")
            ?: 0.0

        holder.nameQty.text = "$name (Qty: $qty)"

        // ✅ Hide base if 0
        if (basePrice > 0.0) {
            holder.base.visibility = View.VISIBLE
            holder.base.text = "Base: $${String.format(Locale.US, "%.2f", basePrice)}"
        } else {
            holder.base.visibility = View.GONE
        }

        // Modifiers formatting (supports a few common structures)
        val modsText = buildModifiersText(doc)

        // ✅ Hide modifiers if empty
        if (modsText.isNullOrBlank()) {
            holder.modifiers.visibility = View.GONE
        } else {
            holder.modifiers.visibility = View.VISIBLE
            holder.modifiers.text = modsText
        }

        holder.lineTotal.text = "Line Total: $${String.format(Locale.US, "%.2f", line)}"
    }

    private fun buildModifiersText(doc: DocumentSnapshot): String? {
        val raw = doc.get("modifiers") as? List<*> ?: return null

        if (raw.isEmpty()) return null

        val lines = raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null

            val name = map["first"]?.toString() ?: return@mapNotNull null
            val price = (map["second"] as? Number)?.toDouble() ?: 0.0

            if (price > 0.0) {
                "• $name (+$${String.format(Locale.US, "%.2f", price)})"
            } else {
                "• $name"
            }
        }

        if (lines.isEmpty()) return null

        return "Modifiers:\n" + lines.joinToString("\n")
    }
}