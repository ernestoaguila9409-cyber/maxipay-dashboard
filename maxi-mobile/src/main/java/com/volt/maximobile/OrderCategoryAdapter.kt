package com.volt.maximobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

internal class OrderCategoryAdapter(
    private val onCategorySelected: (CatUi) -> Unit,
) : RecyclerView.Adapter<OrderCategoryAdapter.ViewHolder>() {

    private var categories: List<CatUi> = emptyList()
    private var selectedCategoryId: String? = null

    fun submitList(categories: List<CatUi>, selectedCategoryId: String?) {
        this.categories = categories
        this.selectedCategoryId = selectedCategoryId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_order_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.categoryName)

        fun bind(category: CatUi) {
            val selected = category.id == selectedCategoryId
            nameView.text = category.name
            nameView.setBackgroundResource(
                if (selected) R.drawable.bg_order_category_selected else R.drawable.bg_order_category_normal,
            )
            nameView.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (selected) R.color.order_category_selected_text else R.color.order_text_primary,
                ),
            )
            itemView.setOnClickListener { onCategorySelected(category) }
        }
    }
}
