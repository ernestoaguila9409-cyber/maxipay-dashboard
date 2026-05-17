package com.volt.maximobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

internal class OrderMenuItemAdapter(
    private val onItemClick: (ItemUi) -> Unit,
) : RecyclerView.Adapter<OrderMenuItemAdapter.ViewHolder>() {

    private var items: List<ItemUi> = emptyList()

    fun submitList(items: List<ItemUi>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.menuItemName)
        private val priceView: TextView = itemView.findViewById(R.id.menuItemPrice)

        fun bind(item: ItemUi) {
            nameView.text = item.name
            priceView.text = formatMoney(item.priceDollars)
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    private fun formatMoney(amount: Double): String =
        "$${String.format(Locale.US, "%.2f", amount)}"
}
