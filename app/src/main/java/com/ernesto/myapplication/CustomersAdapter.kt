package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CustomerItem(
    val id: String,
    val name: String,
    val phone: String,
    val email: String
)

class CustomersAdapter(
    private var items: List<CustomerItem> = emptyList()
) : RecyclerView.Adapter<CustomersAdapter.VH>() {

    fun submitList(list: List<CustomerItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtName: TextView = itemView.findViewById(R.id.txtCustomerName)
        private val txtPhone: TextView = itemView.findViewById(R.id.txtCustomerPhone)
        private val txtEmail: TextView = itemView.findViewById(R.id.txtCustomerEmail)

        fun bind(item: CustomerItem) {
            txtName.text = item.name.ifBlank { "No name" }
            txtPhone.text = if (item.phone.isNotBlank()) "Phone: ${formatPhone(item.phone)}" else ""
            txtPhone.visibility = if (item.phone.isNotBlank()) View.VISIBLE else View.GONE
            txtEmail.text = if (item.email.isNotBlank()) "Email: ${item.email}" else ""
            txtEmail.visibility = if (item.email.isNotBlank()) View.VISIBLE else View.GONE
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
