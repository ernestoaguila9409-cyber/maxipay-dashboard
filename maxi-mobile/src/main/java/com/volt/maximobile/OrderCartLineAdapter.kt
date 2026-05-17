package com.volt.maximobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

internal class OrderCartLineAdapter(
    private val onIncreaseLine: (Int) -> Unit,
    private val onDecreaseLine: (Int) -> Unit,
) : RecyclerView.Adapter<OrderCartLineAdapter.ViewHolder>() {

    private var lines: List<CartLine> = emptyList()

    fun submitList(lines: List<CartLine>) {
        this.lines = lines
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cart_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position], position)
    }

    override fun getItemCount(): Int = lines.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.cartLineName)
        private val unitPriceView: TextView = itemView.findViewById(R.id.cartLineUnitPrice)
        private val quantityView: TextView = itemView.findViewById(R.id.cartLineQuantity)
        private val totalView: TextView = itemView.findViewById(R.id.cartLineTotal)
        private val decreaseButton: ImageButton = itemView.findViewById(R.id.cartLineDecrease)
        private val increaseButton: ImageButton = itemView.findViewById(R.id.cartLineIncrease)

        fun bind(line: CartLine, position: Int) {
            nameView.text = line.name
            unitPriceView.text = itemView.context.getString(
                R.string.order_cart_unit_price,
                formatMoney(line.unitPriceDollars),
            )
            quantityView.text = line.quantity.toString()
            totalView.text = formatMoney(line.unitPriceDollars * line.quantity)
            decreaseButton.setOnClickListener { onDecreaseLine(position) }
            increaseButton.setOnClickListener { onIncreaseLine(position) }
        }
    }

    private fun formatMoney(amount: Double): String =
        "$${String.format(Locale.US, "%.2f", amount)}"
}
