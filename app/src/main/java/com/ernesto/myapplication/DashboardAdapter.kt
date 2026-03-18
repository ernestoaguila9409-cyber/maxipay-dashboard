package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DashboardAdapter(
    private val modules: MutableList<DashboardModule>,
    private val onClick: (DashboardModule) -> Unit
) : RecyclerView.Adapter<DashboardAdapter.ViewHolder>() {

    private val badges = mutableMapOf<String, Int>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnContainer: View = view.findViewById(R.id.btnContainer)
        val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
        val txtLabel: TextView = view.findViewById(R.id.txtLabel)
        val badge: TextView = view.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dashboard_button, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val module = modules[position]
        holder.txtLabel.text = module.label
        holder.imgIcon.setImageResource(IconRegistry.getResId(module.iconName))

        val colorKey = if (module.colorKey.isNotBlank()) {
            module.colorKey
        } else {
            when (module.key) {
                "dine_in" -> "green"
                "to_go" -> "orange"
                "bar" -> "teal"
                else -> "purple"
            }
        }
        holder.btnContainer.background = ColorRegistry.getBackgroundDrawable(holder.btnContainer, colorKey)
        holder.btnContainer.setOnClickListener { onClick(module) }

        val count = badges[module.key] ?: 0
        if (count > 0) {
            holder.badge.text = count.toString()
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = modules.size

    fun setModules(newModules: List<DashboardModule>) {
        modules.clear()
        modules.addAll(newModules)
        notifyDataSetChanged()
    }

    fun updateBadge(key: String, count: Int) {
        badges[key] = count
        val idx = modules.indexOfFirst { it.key == key }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun getModuleKeys(): List<String> = modules.map { it.key }
}
