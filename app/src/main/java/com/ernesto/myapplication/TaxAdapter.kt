package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SwitchCompat
import java.util.Locale

data class TaxItem(
    val id: String,
    val type: String,
    val name: String,
    val amount: Double,
    val enabled: Boolean = true
)

class TaxAdapter(
    private var items: List<TaxItem> = emptyList(),
    private val onItemClick: (TaxItem) -> Unit = {},
    private val onToggleEnabled: (TaxItem, Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<TaxAdapter.VH>() {

    fun submitList(list: List<TaxItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tax, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bind(item, onItemClick, onToggleEnabled)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtName: TextView = itemView.findViewById(R.id.txtTaxName)
        private val txtTypeAndAmount: TextView = itemView.findViewById(R.id.txtTaxTypeAndAmount)
        private val switchEnabled: SwitchCompat = itemView.findViewById(R.id.switchTaxEnabled)

        fun bind(
            item: TaxItem,
            onItemClick: (TaxItem) -> Unit,
            onToggleEnabled: (TaxItem, Boolean) -> Unit
        ) {
            txtName.text = item.name
            val typeLabel = if (item.type == "FIXED") "Fixed" else "Percentage"
            val amountStr = if (item.type == "FIXED") {
                String.format(Locale.US, "$%.2f", item.amount)
            } else {
                String.format(Locale.US, "%.1f%%", item.amount)
            }
            txtTypeAndAmount.text = "$typeLabel · $amountStr"

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = item.enabled
            setSwitchColors(switchEnabled, item.enabled)
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(item, isChecked)
                setSwitchColors(switchEnabled, isChecked)
            }

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun setSwitchColors(switch: SwitchCompat, enabled: Boolean) {
            if (enabled) {
                switch.thumbTintList = ColorStateList.valueOf(Color.parseColor("#6A4FB3"))
                switch.trackTintList = ColorStateList.valueOf(Color.parseColor("#E0D0F0"))
            } else {
                switch.thumbTintList = ColorStateList.valueOf(Color.BLACK)
                switch.trackTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
            }
        }
    }
}
