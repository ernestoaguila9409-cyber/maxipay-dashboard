package com.ernesto.myapplication

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale
import com.ernesto.myapplication.engine.MoneyUtils

class OrderItemsAdapter(
    private val items: List<DocumentSnapshot>,
    private val onItemClick: ((DocumentSnapshot) -> Unit)? = null
) : RecyclerView.Adapter<OrderItemsAdapter.ItemVH>() {

    private var refundedLineKeys: Set<String> = emptySet()
    private var refundedNameAmountKeys: Set<String> = emptySet()
    private var refundedLineKeyToEmployee: Map<String, String> = emptyMap()
    private var refundedNameAmountToEmployee: Map<String, String> = emptyMap()
    private var refundedLineKeyToDate: Map<String, String> = emptyMap()
    private var refundedNameAmountToDate: Map<String, String> = emptyMap()

    fun setRefundedKeys(
        lineKeys: Set<String>,
        nameAmountKeys: Set<String>,
        lineKeyToEmployee: Map<String, String> = emptyMap(),
        nameAmountToEmployee: Map<String, String> = emptyMap(),
        lineKeyToDate: Map<String, String> = emptyMap(),
        nameAmountToDate: Map<String, String> = emptyMap()
    ) {
        refundedLineKeys = lineKeys
        refundedNameAmountKeys = nameAmountKeys
        refundedLineKeyToEmployee = lineKeyToEmployee
        refundedNameAmountToEmployee = nameAmountToEmployee
        refundedLineKeyToDate = lineKeyToDate
        refundedNameAmountToDate = nameAmountToDate
        notifyDataSetChanged()
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val nameQty: TextView = view.findViewById(R.id.txtItemNameQty)
        val base: TextView = view.findViewById(R.id.txtItemBase)
        val modifiers: TextView = view.findViewById(R.id.txtItemModifiers)
        val lineTotal: TextView = view.findViewById(R.id.txtItemLineTotal)
        val payments: TextView = view.findViewById(R.id.txtItemPayments)
        val card: MaterialCardView = view as MaterialCardView
        val refundedByRow: View = view.findViewById(R.id.refundedByRow)
        val refundedBy: TextView = view.findViewById(R.id.txtRefundedBy)
        val refundedDate: TextView = view.findViewById(R.id.txtRefundedDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_item, parent, false)
        return ItemVH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ItemVH, position: Int) {
        val doc = items[position]

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(doc)
        }

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

        val lineKey = doc.id
        val nameAmountKey = "$name|$lineInCents"
        val isRefunded = lineKey in refundedLineKeys || nameAmountKey in refundedNameAmountKeys
        val refundedByEmployee = refundedLineKeyToEmployee[lineKey] ?: refundedNameAmountToEmployee[nameAmountKey]

        holder.card.setCardBackgroundColor(
            if (isRefunded) Color.parseColor("#BBDEFB") else Color.WHITE
        )
        val refundedDateStr = refundedLineKeyToDate[lineKey] ?: refundedNameAmountToDate[nameAmountKey]
        if (isRefunded && (!refundedByEmployee.isNullOrBlank() || !refundedDateStr.isNullOrBlank())) {
            holder.refundedByRow.visibility = View.VISIBLE
            holder.refundedBy.text = if (!refundedByEmployee.isNullOrBlank()) "Refunded by: $refundedByEmployee" else ""
            holder.refundedBy.visibility = if (refundedByEmployee.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.refundedDate.text = refundedDateStr ?: ""
            holder.refundedDate.visibility = if (refundedDateStr.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            holder.refundedByRow.visibility = View.GONE
        }

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