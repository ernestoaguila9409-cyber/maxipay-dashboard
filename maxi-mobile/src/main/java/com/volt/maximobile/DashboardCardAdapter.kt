package com.volt.maximobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class DashboardCardAdapter(
    private val onClick: (DashboardCardItem) -> Unit,
) : RecyclerView.Adapter<DashboardCardAdapter.ViewHolder>() {

    private val items = mutableListOf<DashboardCardItem>()
    private var tileHeightPx: Int? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardRoot)
        val title: TextView = view.findViewById(R.id.txtTitle)
        val subtitle: TextView = view.findViewById(R.id.txtSubtitle)
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val badge: TextView = view.findViewById(R.id.txtBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dashboard, parent, false)
        tileHeightPx?.let { height ->
            view.layoutParams = view.layoutParams.apply { this.height = height }
        }
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        tileHeightPx?.let { height ->
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                this.height = height
            }
        }
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.icon.setImageResource(item.iconRes)
        holder.card.setCardBackgroundColor(item.color)

        if (item.badge > 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = item.badge.toString()
        } else {
            holder.badge.visibility = View.GONE
        }

        holder.card.setOnClickListener { onClick(item) }
    }

    fun submitList(newItems: List<DashboardCardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setTileHeightPx(heightPx: Int) {
        if (tileHeightPx == heightPx) return
        tileHeightPx = heightPx
        notifyDataSetChanged()
    }
}
