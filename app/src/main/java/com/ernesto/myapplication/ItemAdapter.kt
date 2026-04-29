package com.ernesto.myapplication

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
class ItemAdapter(
    private val context: Context,
    private val itemList: List<ItemModel>,
    private val categoryAvailabilityMap: Map<String, List<String>> = emptyMap(),
    private val stockCountingEnabled: Boolean = true,
    private val subcategories: List<SubcategoryModel> = emptyList(),
    private val refresh: () -> Unit
) : RecyclerView.Adapter<ItemAdapter.ViewHolder>() {

    private var filteredList: List<ItemModel> = itemList

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgThumb: ImageView = view.findViewById(R.id.imgItemThumb)
        val txtItemName: TextView = view.findViewById(R.id.txtItemName)
        val txtItemPrice: TextView = view.findViewById(R.id.txtItemPrice)
        val txtItemStock: TextView = view.findViewById(R.id.txtItemStock)
        val txtStockStatus: TextView = view.findViewById(R.id.txtStockStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = filteredList.size

    fun filter(query: String) {
        filteredList = if (query.isBlank()) {
            itemList
        } else {
            itemList.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredList[position]

        holder.txtItemName.text = item.name
        holder.txtItemPrice.text = "$${String.format("%.2f", item.getPrice("pos"))}"

        val img = item.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (img != null) {
            holder.imgThumb.visibility = View.VISIBLE
            holder.imgThumb.load(img) { crossfade(true) }
        } else {
            holder.imgThumb.visibility = View.GONE
            holder.imgThumb.setImageDrawable(null)
        }

        if (stockCountingEnabled) {
            holder.txtItemStock.visibility = View.VISIBLE
            holder.txtStockStatus.visibility = View.VISIBLE

            holder.txtItemStock.text = item.stock.toString()

            when {
                item.stock <= 0 -> {
                    holder.txtStockStatus.text = "Out of stock"
                    holder.txtStockStatus.setTextColor(0xFFDC2626.toInt())
                    holder.txtStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_red)
                }
                item.stock <= 10 -> {
                    holder.txtStockStatus.text = "Low stock"
                    holder.txtStockStatus.setTextColor(0xFFA16207.toInt())
                    holder.txtStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_yellow)
                }
                else -> {
                    holder.txtStockStatus.text = "In stock"
                    holder.txtStockStatus.setTextColor(0xFF16A34A.toInt())
                    holder.txtStockStatus.setBackgroundResource(R.drawable.bg_stock_badge_green)
                }
            }
        } else {
            holder.txtItemStock.visibility = View.GONE
            holder.txtStockStatus.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            context.startActivity(ItemDetailActivity.createIntent(context, item.id))
        }
    }
}
