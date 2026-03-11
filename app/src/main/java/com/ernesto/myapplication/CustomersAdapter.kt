package com.ernesto.myapplication

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

data class CustomerItem(
    val id: String,
    val name: String,
    val phone: String,
    val email: String
)

class CustomersAdapter(
    private var items: List<CustomerItem> = emptyList(),
    private var selectionMode: Boolean = false,
    private var selectedIds: MutableSet<String> = mutableSetOf(),
    private val onSelectionChanged: (Set<String>) -> Unit = {}
) : RecyclerView.Adapter<CustomersAdapter.VH>() {

    fun submitList(list: List<CustomerItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode != enabled) {
            selectionMode = enabled
            if (!enabled) selectedIds.clear()
            notifyDataSetChanged()
            onSelectionChanged(selectedIds.toSet())
        }
    }

    fun isSelectionMode() = selectionMode

    fun getSelectedIds(): Set<String> = selectedIds.toSet()

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    private fun toggleSelection(item: CustomerItem) {
        if (selectedIds.contains(item.id)) selectedIds.remove(item.id) else selectedIds.add(item.id)
        val pos = items.indexOfFirst { it.id == item.id }
        if (pos >= 0) notifyItemChanged(pos)
        onSelectionChanged(selectedIds.toSet())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(
            items[position],
            selectionMode,
            selectedIds.contains(items[position].id),
            { toggleSelection(it) }
        )
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.cardCustomer)
        private val txtName: TextView = itemView.findViewById(R.id.txtCustomerName)
        private val txtPhone: TextView = itemView.findViewById(R.id.txtCustomerPhone)
        private val txtEmail: TextView = itemView.findViewById(R.id.txtCustomerEmail)

        fun bind(
            item: CustomerItem,
            selectionMode: Boolean,
            isSelected: Boolean,
            onToggleSelection: (CustomerItem) -> Unit
        ) {
            txtName.text = item.name.ifBlank { "No name" }
            txtPhone.text = if (item.phone.isNotBlank()) "Phone: ${formatPhone(item.phone)}" else ""
            txtPhone.visibility = if (item.phone.isNotBlank()) View.VISIBLE else View.GONE
            txtEmail.text = if (item.email.isNotBlank()) "Email: ${item.email}" else ""
            txtEmail.visibility = if (item.email.isNotBlank()) View.VISIBLE else View.GONE

            val bgColor = when {
                selectionMode && isSelected -> Color.parseColor("#EDE7F6")
                else -> Color.WHITE
            }
            card.setCardBackgroundColor(bgColor)
            card.strokeWidth = if (isSelected) 4 else 1
            card.strokeColor = if (isSelected) Color.parseColor("#6A4FB3") else Color.parseColor("#E0E0E0")

            itemView.setOnClickListener {
                if (selectionMode) {
                    onToggleSelection(item)
                }
            }
        }

        private fun formatPhone(phone: String): String {
            val digits = phone.filter { it.isDigit() }
            return when {
                digits.length == 10 -> "${digits.take(3)}-${digits.drop(3).take(3)}-${digits.takeLast(4)}"
                digits.length == 11 && digits.startsWith("1") -> "${digits.drop(1).take(3)}-${digits.drop(4).take(3)}-${digits.takeLast(4)}"
                else -> phone
            }
        }
    }
}
