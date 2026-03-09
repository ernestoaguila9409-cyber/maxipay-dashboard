package com.ernesto.myapplication

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.MoneyUtils

data class BarTab(
    val orderId: String,
    val barSeat: Int,
    val status: String,
    val totalInCents: Long,
    val cardLast4: String = "",
    val cardBrand: String = ""
)

class BarTabsAdapter(
    private val onTabClick: (BarTab) -> Unit
) : RecyclerView.Adapter<BarTabsAdapter.VH>() {

    private val items = mutableListOf<BarTab>()

    fun submit(list: List<BarTab>) {
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
        val tab = items[position]
        holder.bind(tab)
        holder.itemView.setOnClickListener { onTabClick(tab) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtSeatLabel: TextView = itemView.findViewById(R.id.txtSeatLabel)
        private val txtTabStatus: TextView = itemView.findViewById(R.id.txtTabStatus)
        private val txtTabTotal: TextView = itemView.findViewById(R.id.txtTabTotal)
        private val txtCardLast4: TextView = itemView.findViewById(R.id.txtCardLast4)

        fun bind(tab: BarTab) {
            txtSeatLabel.text = "Seat ${tab.barSeat}"
            txtTabStatus.text = tab.status
            txtTabTotal.text = MoneyUtils.centsToDisplay(tab.totalInCents)

            if (tab.cardLast4.isNotBlank()) {
                txtCardLast4.visibility = View.VISIBLE
                val brandLabel = if (tab.cardBrand.isNotBlank()) "${tab.cardBrand} " else ""
                txtCardLast4.text = "${brandLabel}•••• ${tab.cardLast4}"
            } else {
                txtCardLast4.visibility = View.GONE
            }

            val statusColor = when (tab.status) {
                "OPEN" -> 0xFF1B5E20.toInt()
                else -> 0xFFB71C1C.toInt()
            }
            val bgColor = when (tab.status) {
                "OPEN" -> 0xFFDFF5E3.toInt()
                else -> 0xFFFFE0E0.toInt()
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
