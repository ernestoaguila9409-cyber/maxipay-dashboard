package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
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
        private val statusBar: View = itemView.findViewById(R.id.statusBar)
        private val txtOrderLabel: TextView = itemView.findViewById(R.id.txtOrderLabel)
        private val txtTotal: TextView = itemView.findViewById(R.id.txtTotal)
        private val txtStatus: TextView = itemView.findViewById(R.id.txtStatus)
        private val txtPreAuth: TextView = itemView.findViewById(R.id.txtPreAuth)
        private val txtRefund: TextView = itemView.findViewById(R.id.txtRefund)
        private val txtTime: TextView = itemView.findViewById(R.id.txtTime)
        private val txtOrderType: TextView = itemView.findViewById(R.id.txtOrderType)
        private val imgSelected: ImageView = itemView.findViewById(R.id.imgSelected)

        fun bind(order: OrderRow, isSelected: Boolean) {
            val label = buildString {
                if (order.orderNumber > 0L) append("#${order.orderNumber}")
                val name = order.employeeName.takeIf { it.isNotBlank() && it != "—" }
                if (name != null) {
                    if (isNotEmpty()) append(" · ")
                    append(name)
                }
            }
            txtOrderLabel.text = label
            txtOrderLabel.visibility = if (label.isNotEmpty()) View.VISIBLE else View.GONE

            txtTotal.text = MoneyUtils.centsToDisplay(order.netCents)

            bindStatusBar(order.status)
            bindStatusBadge(order.status)

            if (order.preAuthAmountCents > 0L) {
                txtPreAuth.visibility = View.VISIBLE
                val label = if (order.status.uppercase() == "CLOSED") "PostAuth" else "PreAuth"
                txtPreAuth.text = "$label ${MoneyUtils.centsToDisplay(order.preAuthAmountCents)}"
            } else {
                txtPreAuth.visibility = View.GONE
            }

            val hasRefund = order.totalRefundedInCents > 0L
            if (hasRefund) {
                txtRefund.visibility = View.VISIBLE
                txtRefund.text = "Refund -${MoneyUtils.centsToDisplay(order.totalRefundedInCents)}"
            } else {
                txtRefund.visibility = View.GONE
            }

            txtTime.text = formatTime(order.createdAt.toDate().time)

            bindOrderTypeBadge(order.orderType)

            imgSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.alpha = if (isSelected) 0.85f else 1f
        }

        private fun bindStatusBar(status: String) {
            val color = when (status.uppercase()) {
                "OPEN" -> Color.parseColor("#2196F3")
                "CLOSED" -> Color.parseColor("#9E9E9E")
                "VOIDED" -> Color.parseColor("#F44336")
                "REFUNDED" -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#BDBDBD")
            }
            val drawable = GradientDrawable().apply {
                setColor(color)
                cornerRadii = floatArrayOf(16f, 0f, 0f, 0f, 0f, 0f, 16f, 0f)
            }
            statusBar.background = drawable
        }

        private fun bindStatusBadge(status: String) {
            txtStatus.text = status.uppercase()

            val (bgColor, textColor) = when (status.uppercase()) {
                "OPEN" -> Color.parseColor("#E3F2FD") to Color.parseColor("#1565C0")
                "CLOSED" -> Color.parseColor("#F5F5F5") to Color.parseColor("#616161")
                "VOIDED" -> Color.parseColor("#FFEBEE") to Color.parseColor("#C62828")
                "REFUNDED" -> Color.parseColor("#FFF3E0") to Color.parseColor("#E65100")
                else -> Color.parseColor("#F5F5F5") to Color.parseColor("#424242")
            }

            val badge = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 50f
            }
            txtStatus.background = badge
            txtStatus.setTextColor(textColor)
        }

        private fun bindOrderTypeBadge(orderType: String) {
            if (orderType.isBlank()) {
                txtOrderType.visibility = View.GONE
                return
            }

            txtOrderType.visibility = View.VISIBLE
            txtOrderType.text = when (orderType) {
                "DINE_IN" -> "DINE IN"
                "BAR" -> "BAR"
                "BAR_TAB" -> "BAR TAB"
                else -> "TO-GO"
            }

            val bgColor = when (orderType) {
                "DINE_IN" -> Color.parseColor("#2E7D32")
                "BAR", "BAR_TAB" -> Color.parseColor("#6A4FB3")
                else -> Color.parseColor("#E65100")
            }

            val badge = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 50f
            }
            txtOrderType.background = badge
            txtOrderType.setTextColor(Color.WHITE)
        }

        private fun formatTime(ms: Long): String {
            return DateFormat.format("MMM dd · h:mm a", ms).toString()
        }
    }
}
