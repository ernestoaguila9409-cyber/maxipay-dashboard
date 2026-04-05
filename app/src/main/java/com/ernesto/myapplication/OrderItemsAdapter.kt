package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Locale
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils

sealed class OrderListItem {
    data class GuestHeader(val guestNumber: Int, val guestName: String? = null) : OrderListItem()
    data class Item(val doc: DocumentSnapshot) : OrderListItem()
}

class OrderItemsAdapter(
    private val listItems: MutableList<OrderListItem>,
    private val onItemClick: ((DocumentSnapshot) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private var refundedLineKeys: Set<String> = emptySet()
    private var refundedNameAmountKeys: Set<String> = emptySet()
    private var refundedLineKeyToEmployee: Map<String, String> = emptyMap()
    private var refundedNameAmountToEmployee: Map<String, String> = emptyMap()
    private var refundedLineKeyToDate: Map<String, String> = emptyMap()
    private var refundedNameAmountToDate: Map<String, String> = emptyMap()
    private var wholeOrderRefundEmployee: String? = null
    private var wholeOrderRefundDate: String? = null

    /** Order-level applied discounts from Firestore (each map may contain lineKey). */
    private var appliedDiscounts: List<Map<String, Any>> = emptyList()

    fun setAppliedDiscounts(discounts: List<Map<String, Any>>) {
        appliedDiscounts = discounts
        notifyDataSetChanged()
    }

    fun setRefundedKeys(
        lineKeys: Set<String>,
        nameAmountKeys: Set<String>,
        lineKeyToEmployee: Map<String, String> = emptyMap(),
        nameAmountToEmployee: Map<String, String> = emptyMap(),
        lineKeyToDate: Map<String, String> = emptyMap(),
        nameAmountToDate: Map<String, String> = emptyMap(),
        wholeOrderEmployee: String? = null,
        wholeOrderDate: String? = null
    ) {
        refundedLineKeys = lineKeys
        refundedNameAmountKeys = nameAmountKeys
        refundedLineKeyToEmployee = lineKeyToEmployee
        refundedNameAmountToEmployee = nameAmountToEmployee
        refundedLineKeyToDate = lineKeyToDate
        refundedNameAmountToDate = nameAmountToDate
        wholeOrderRefundEmployee = wholeOrderEmployee
        wholeOrderRefundDate = wholeOrderDate
        notifyDataSetChanged()
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val txtHeader: TextView = view.findViewById(R.id.txtGuestHeader)
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val nameQty: TextView = view.findViewById(R.id.txtItemNameQty)
        val base: TextView = view.findViewById(R.id.txtItemBase)
        val modifiers: TextView = view.findViewById(R.id.txtItemModifiers)
        val discounts: TextView = view.findViewById(R.id.txtItemDiscounts)
        val lineTotal: TextView = view.findViewById(R.id.txtItemLineTotal)
        val payments: TextView = view.findViewById(R.id.txtItemPayments)
        val refundedBadge: TextView = view.findViewById(R.id.txtRefundedBadge)
        val refundedByRow: View = view.findViewById(R.id.refundedByRow)
        val refundedBy: TextView = view.findViewById(R.id.txtRefundedBy)
        val refundedDate: TextView = view.findViewById(R.id.txtRefundedDate)
    }

    override fun getItemViewType(position: Int): Int = when (listItems[position]) {
        is OrderListItem.GuestHeader -> TYPE_HEADER
        is OrderListItem.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_guest_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_order_item, parent, false))
        }
    }

    override fun getItemCount() = listItems.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = listItems[position]) {
            is OrderListItem.GuestHeader -> {
                val label = if (!entry.guestName.isNullOrBlank()) entry.guestName else "Guest ${entry.guestNumber}"
                (holder as HeaderVH).txtHeader.text = "— $label —"
            }
            is OrderListItem.Item -> bindItem(holder as ItemVH, entry.doc)
        }
    }

    private fun bindItem(holder: ItemVH, doc: DocumentSnapshot) {
        holder.itemView.setOnClickListener {
            onItemClick?.invoke(doc)
        }

        val name = doc.getString("name")
            ?: doc.getString("itemName")
            ?: "Item"

        val qty = (doc.getLong("qty")
            ?: doc.getLong("quantity")
            ?: 1L).toInt()

        val lineInCents =
            doc.getLong("lineTotalInCents") ?: 0L

        val lineKey = doc.id
        val nameAmountKey = "$name|$lineInCents"
        val matchedByKey = lineKey in refundedLineKeys || nameAmountKey in refundedNameAmountKeys
        val isRefunded = matchedByKey || wholeOrderRefundEmployee != null
        val refundedByEmployee = refundedLineKeyToEmployee[lineKey]
            ?: refundedNameAmountToEmployee[nameAmountKey]
            ?: wholeOrderRefundEmployee
        val refundedDateStr = refundedLineKeyToDate[lineKey]
            ?: refundedNameAmountToDate[nameAmountKey]
            ?: wholeOrderRefundDate

        val lineTotalNormalColor = Color.parseColor("#1B5E20")
        val lineTotalRefundedColor = Color.parseColor("#999999")

        holder.nameQty.text = "$name (Qty: $qty)"
        holder.lineTotal.text = "Line Total: ${MoneyUtils.centsToDisplay(lineInCents)}"

        if (isRefunded) {
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF5F5"))
            holder.nameQty.paintFlags = holder.nameQty.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.nameQty.setTextColor(Color.parseColor("#999999"))
            holder.lineTotal.paintFlags = holder.lineTotal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.lineTotal.setTextColor(lineTotalRefundedColor)
            holder.refundedBadge.visibility = View.VISIBLE

            if (!refundedByEmployee.isNullOrBlank() || !refundedDateStr.isNullOrBlank()) {
                holder.refundedByRow.visibility = View.VISIBLE
                holder.refundedBy.text = if (!refundedByEmployee.isNullOrBlank()) "Refunded by $refundedByEmployee" else ""
                holder.refundedBy.visibility = if (refundedByEmployee.isNullOrBlank()) View.GONE else View.VISIBLE
                holder.refundedDate.text = refundedDateStr ?: ""
                holder.refundedDate.visibility = if (refundedDateStr.isNullOrBlank()) View.GONE else View.VISIBLE
            } else {
                holder.refundedByRow.visibility = View.GONE
            }
        } else {
            holder.itemView.setBackgroundColor(Color.WHITE)
            holder.nameQty.paintFlags = holder.nameQty.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.nameQty.setTextColor(Color.parseColor("#222222"))
            holder.lineTotal.paintFlags = holder.lineTotal.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.lineTotal.setTextColor(lineTotalNormalColor)
            holder.refundedBadge.visibility = View.GONE
            holder.refundedByRow.visibility = View.GONE
        }

        holder.base.visibility = View.GONE

        val modsText = buildModifiersText(doc)
        if (modsText.isNullOrBlank()) {
            holder.modifiers.visibility = View.GONE
        } else {
            holder.modifiers.visibility = View.VISIBLE
            holder.modifiers.text = modsText
        }

        val lineDiscountText = buildLineDiscountsText(lineKey)
        if (lineDiscountText.isNullOrBlank()) {
            holder.discounts.visibility = View.GONE
        } else {
            holder.discounts.visibility = View.VISIBLE
            holder.discounts.text = lineDiscountText
        }

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

    private fun buildLineDiscountsText(lineKey: String): String? {
        val itemLevel = appliedDiscounts.filter { ad ->
            val lk = ad["lineKey"]?.toString()?.trim().orEmpty()
            lk == lineKey
        }
        // Order- / manual-scope discounts (empty lineKey) belong in the summary under Subtotal only.
        if (itemLevel.isEmpty()) return null
        return itemLevel.joinToString("\n") { DiscountDisplay.formatBulletFromFirestoreMap(it) }
    }

    private fun buildModifiersText(doc: DocumentSnapshot): String? {
        val raw = doc.get("modifiers") as? List<*> ?: return null
        if (raw.isEmpty()) return null

        val lines = mutableListOf<String>()
        fun appendModifier(item: Any?, indent: String = "") {
            when (item) {
                is Map<*, *> -> {
                    val action = item["action"]?.toString() ?: "ADD"
                    val modName = item["name"]?.toString()
                        ?: item["first"]?.toString()
                        ?: return

                    lines.add(
                        if (action == "REMOVE") "${indent}\u2022 No $modName"
                        else "${indent}\u2022 $modName"
                    )
                    val children = item["children"] as? List<*>
                    children?.forEach { child -> appendModifier(child, "$indent    \u21B3 ") }
                }
                is List<*> -> {
                    if (item.isEmpty()) return
                    val modName = item[0]?.toString() ?: return
                    lines.add("${indent}\u2022 $modName")
                }
            }
        }
        raw.forEach { appendModifier(it) }

        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }
}
