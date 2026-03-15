package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CashActivityItem(
    val timestamp: Date,
    val orderNumber: Long,
    val type: String,
    val amountDueCents: Long,
    val tenderedCents: Long,
    val changeCents: Long
)

class CashActivityAdapter : RecyclerView.Adapter<CashActivityAdapter.ViewHolder>() {

    private val items = mutableListOf<CashActivityItem>()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun submitList(newItems: List<CashActivityItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cash_activity, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val txtTime: TextView = itemView.findViewById(R.id.txtTime)
        private val txtOrderInfo: TextView = itemView.findViewById(R.id.txtOrderInfo)
        private val txtType: TextView = itemView.findViewById(R.id.txtType)
        private val txtAmount: TextView = itemView.findViewById(R.id.txtAmount)
        private val txtTenderDetails: TextView = itemView.findViewById(R.id.txtTenderDetails)

        fun bind(item: CashActivityItem) {
            txtTime.text = timeFmt.format(item.timestamp)

            if (item.orderNumber > 0L) {
                txtOrderInfo.text = "Order #${item.orderNumber}"
                txtOrderInfo.visibility = View.VISIBLE
            } else {
                txtOrderInfo.visibility = View.GONE
            }

            val label = when (item.type) {
                "SALE", "CAPTURE" -> "Cash Sale"
                "REFUND" -> "Cash Refund"
                "CASH_ADD" -> "Cash Added"
                "PAID_OUT" -> "Paid Out"
                else -> item.type
            }
            txtType.text = label
            txtAmount.text = centsToDisplay(item.amountDueCents)

            val isRefundOrOut = item.type == "REFUND" || item.type == "PAID_OUT"
            txtAmount.setTextColor(
                android.graphics.Color.parseColor(if (isRefundOrOut) "#C62828" else "#2E7D32")
            )

            if (item.tenderedCents > 0L && (item.type == "SALE" || item.type == "CAPTURE")) {
                txtTenderDetails.text =
                    "Tendered: ${centsToDisplay(item.tenderedCents)} | Change: ${centsToDisplay(item.changeCents)}"
                txtTenderDetails.visibility = View.VISIBLE
            } else {
                txtTenderDetails.visibility = View.GONE
            }
        }

        private fun centsToDisplay(cents: Long): String {
            return String.format(Locale.US, "$%.2f", cents / 100.0)
        }
    }
}
