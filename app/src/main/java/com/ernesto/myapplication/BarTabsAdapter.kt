package com.ernesto.myapplication

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.MoneyUtils

data class BarSeat(
    val tableId: String,
    val seatName: String,
    val maxSeats: Int,
    val isOccupied: Boolean,
    val orderId: String? = null,
    val customerName: String? = null,
    val totalInCents: Long = 0L,
    val cardLast4: String = "",
    val cardBrand: String = ""
)

class BarTabsAdapter(
    private val onSeatClick: (BarSeat) -> Unit
) : RecyclerView.Adapter<BarTabsAdapter.VH>() {

    private val items = mutableListOf<BarSeat>()

    fun submit(list: List<BarSeat>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bar_tab, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val seat = items[position]
        holder.bind(seat)
        holder.itemView.setOnClickListener { onSeatClick(seat) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtSeatLabel: TextView = itemView.findViewById(R.id.txtSeatLabel)
        private val txtTabStatus: TextView = itemView.findViewById(R.id.txtTabStatus)
        private val txtTabTotal: TextView = itemView.findViewById(R.id.txtTabTotal)
        private val txtCardLast4: TextView = itemView.findViewById(R.id.txtCardLast4)

        fun bind(seat: BarSeat) {
            txtSeatLabel.text = seat.seatName

            val statusText = if (seat.isOccupied) "OCCUPIED" else "OPEN"
            txtTabStatus.text = statusText

            if (seat.isOccupied) {
                txtTabTotal.text = MoneyUtils.centsToDisplay(seat.totalInCents)
                txtTabTotal.visibility = View.VISIBLE
            } else {
                txtTabTotal.visibility = View.GONE
            }

            if (seat.isOccupied && !seat.customerName.isNullOrBlank()) {
                txtCardLast4.text = seat.customerName
                txtCardLast4.visibility = View.VISIBLE
            } else if (seat.cardLast4.isNotBlank()) {
                val brandLabel = if (seat.cardBrand.isNotBlank()) "${seat.cardBrand} " else ""
                txtCardLast4.text = "${brandLabel}•••• ${seat.cardLast4}"
                txtCardLast4.visibility = View.VISIBLE
            } else {
                txtCardLast4.visibility = View.GONE
            }

            val statusColor: Int
            val bgColor: Int
            if (seat.isOccupied) {
                statusColor = 0xFFE65100.toInt()
                bgColor = 0xFFFFF3E0.toInt()
            } else {
                statusColor = 0xFF1B5E20.toInt()
                bgColor = 0xFFDFF5E3.toInt()
            }
            val badge = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 8f
            }
            txtTabStatus.background = badge
            txtTabStatus.setTextColor(statusColor)
            txtTabStatus.setPadding(12, 4, 12, 4)
        }
    }
}
