package com.ernesto.myapplication

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
        private val txtTime: TextView = itemView.findViewById(R.id.txtTime)
        private val imgSelected: ImageView = itemView.findViewById(R.id.imgSelected)

        fun bind(order: OrderRow, isSelected: Boolean) {

            txtStatus.text = order.status

            // ✅ Status color styling
            when (order.status.uppercase()) {
                "OPEN" -> {
                    txtStatus.setBackgroundColor(0xFFDFF5E3.toInt()) // light green
                    txtStatus.setTextColor(0xFF1B5E20.toInt()) // dark green
                }
                "CLOSED" -> {
                    txtStatus.setBackgroundColor(0xFFFFE0E0.toInt()) // light red
                    txtStatus.setTextColor(0xFFB71C1C.toInt()) // dark red
                }
                else -> {
                    txtStatus.setBackgroundColor(0xFFE0E0E0.toInt())
                    txtStatus.setTextColor(0xFF000000.toInt())
                }
            }

            txtTotal.text = centsToMoney(order.totalCents)
            txtEmployee.text = order.employeeName
            txtTime.text = formatTime(order.createdAt.toDate().time)

            imgSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.alpha = if (isSelected) 0.85f else 1f
        }

        private fun centsToMoney(cents: Long): String {
            val dollars = cents / 100.0
            return String.format(Locale.US, "$%.2f", dollars)
        }

        private fun formatTime(ms: Long): String {
            return DateFormat.format("MMM dd, h:mm a", ms).toString()
        }
    }
}