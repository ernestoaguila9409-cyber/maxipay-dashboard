package com.ernesto.myapplication

import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import java.util.Locale
import com.ernesto.myapplication.engine.MoneyUtils
class OrdersAdapter(
    private val onOrderClick: (OrderRow) -> Unit,
    private val onOrderLongPress: (OrderRow) -> Boolean
) : RecyclerView.Adapter<OrdersAdapter.VH>() {

    private val items = mutableListOf<OrderRow>()
    private val selected = linkedSetOf<String>()

    fun submit(list: List<OrderRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun toggleSelected(orderId: String) {
        if (selected.contains(orderId)) selected.remove(orderId) else selected.add(orderId)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<String> = selected.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val order = items[position]
        holder.bind(order, selected.contains(order.id))

        holder.card.setOnClickListener { onOrderClick(order) }
        holder.card.setOnLongClickListener { onOrderLongPress(order) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardOrder)
        private val txtStatus: TextView = itemView.findViewById(R.id.txtStatus)
        private val txtTotal: TextView = itemView.findViewById(R.id.txtTotal)
        private val txtEmployee: TextView = itemView.findViewById(R.id.txtEmployee)
        private val txtOrderType: TextView = itemView.findViewById(R.id.txtOrderType)
        private val txtRefund: TextView = itemView.findViewById(R.id.txtRefund)
        private val txtTime: TextView = itemView.findViewById(R.id.txtTime)
        private val imgSelected: ImageView = itemView.findViewById(R.id.imgSelected)

        fun bind(order: OrderRow, isSelected: Boolean) {

            txtStatus.text = order.status

            // ✅ Status color styling (linked to Transaction screen: voided sales show as VOIDED here)
            when (order.status.uppercase()) {
                "OPEN" -> {
                    txtStatus.setBackgroundColor(0xFFDFF5E3.toInt()) // light green
                    txtStatus.setTextColor(0xFF1B5E20.toInt()) // dark green
                }
                "CLOSED" -> {
                    txtStatus.setBackgroundColor(0xFFFFE0E0.toInt()) // light red
                    txtStatus.setTextColor(0xFFB71C1C.toInt()) // dark red
                }
                "VOIDED" -> {
                    txtStatus.setBackgroundColor(0xFFFFF3E0.toInt()) // light orange
                    txtStatus.setTextColor(0xFFE65100.toInt()) // dark orange
                }
                "REFUNDED" -> {
                    txtStatus.setBackgroundColor(0xFFE3F2FD.toInt()) // light blue
                    txtStatus.setTextColor(0xFF1565C0.toInt()) // dark blue
                }
                else -> {
                    txtStatus.setBackgroundColor(0xFFE0E0E0.toInt())
                    txtStatus.setTextColor(0xFF000000.toInt())
                }
            }

            // Show net total (original minus refunds); show refund line when there's a partial/full refund
            val hasRefund = order.totalRefundedInCents > 0L
            txtTotal.text = MoneyUtils.centsToDisplay(order.netCents)
            if (hasRefund) {
                txtRefund.visibility = View.VISIBLE
                txtRefund.text = "Refund -${MoneyUtils.centsToDisplay(order.totalRefundedInCents)}"
            } else {
                txtRefund.visibility = View.GONE
            }
            txtEmployee.text = order.employeeName

            if (order.orderType.isNotBlank()) {
                txtOrderType.visibility = View.VISIBLE
                txtOrderType.text = when (order.orderType) {
                    "DINE_IN" -> "DINE IN"
                    "BAR" -> "BAR"
                    "BAR_TAB" -> "BAR TAB"
                    else -> "TO-GO"
                }
                val bgColor = when (order.orderType) {
                    "DINE_IN" -> 0xFF1B5E20.toInt()
                    "BAR" -> 0xFF4A148C.toInt()
                    "BAR_TAB" -> 0xFF6A4FB3.toInt()
                    else -> 0xFFE65100.toInt()
                }
                val badge = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 8f
                }
                txtOrderType.background = badge
                txtOrderType.setTextColor(0xFFFFFFFF.toInt())
            } else {
                txtOrderType.visibility = View.GONE
            }

            txtTime.text = formatTime(order.createdAt.toDate().time)

            imgSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.alpha = if (isSelected) 0.85f else 1f
        }

        private fun formatTime(ms: Long): String {
            return DateFormat.format("MMM dd, h:mm a", ms).toString()
        }
    }
}