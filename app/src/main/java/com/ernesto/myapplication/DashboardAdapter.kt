package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DashboardAdapter(
    private val modules: MutableList<DashboardModule>,
    private val onClick: (DashboardModule) -> Unit
) : RecyclerView.Adapter<DashboardAdapter.PageViewHolder>() {

    companion object {
        const val PAGE_SIZE = 9
    }

    private val badges = mutableMapOf<String, Int>()

    inner class PageViewHolder(val grid: GridLayout) : RecyclerView.ViewHolder(grid)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val density = parent.resources.displayMetrics.density
        val pad = (4 * density).toInt()
        val grid = GridLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            columnCount = 3
            rowCount = 3
            setPadding(pad, pad, pad, pad)
            clipChildren = false
            clipToPadding = false
        }
        return PageViewHolder(grid)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val grid = holder.grid
        grid.removeAllViews()

        val startIdx = position * PAGE_SIZE
        val density = grid.resources.displayMetrics.density
        val cellMargin = (3 * density).toInt()

        for (i in 0 until PAGE_SIZE) {
            val moduleIdx = startIdx + i
            val view = LayoutInflater.from(grid.context)
                .inflate(R.layout.item_dashboard_button, grid, false)

            val params = GridLayout.LayoutParams(
                GridLayout.spec(i / 3, 1f),
                GridLayout.spec(i % 3, 1f)
            ).apply {
                width = 0
                height = 0
                setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
            }

            if (moduleIdx < modules.size) {
                bindButton(view, modules[moduleIdx])
            } else {
                view.visibility = View.INVISIBLE
            }

            grid.addView(view, params)
        }
    }

    private fun bindButton(view: View, module: DashboardModule) {
        val container = view.findViewById<View>(R.id.btnContainer)
        val icon = view.findViewById<ImageView>(R.id.imgIcon)
        val label = view.findViewById<TextView>(R.id.txtLabel)
        val badgeView = view.findViewById<TextView>(R.id.badge)

        label.text = module.label
        icon.setImageResource(IconRegistry.getResId(module.iconName))

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
        container.background = ColorRegistry.getBackgroundDrawable(container, colorKey)
        container.setOnClickListener { onClick(module) }

        val count = badges[module.key] ?: 0
        if (count > 0) {
            badgeView.text = count.toString()
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int =
        if (modules.isEmpty()) 0 else (modules.size + PAGE_SIZE - 1) / PAGE_SIZE

    fun setModules(newModules: List<DashboardModule>) {
        modules.clear()
        modules.addAll(newModules)
        notifyDataSetChanged()
    }

    fun updateBadge(key: String, count: Int) {
        badges[key] = count
        val idx = modules.indexOfFirst { it.key == key }
        if (idx >= 0) notifyItemChanged(idx / PAGE_SIZE)
    }

    fun getModuleKeys(): List<String> = modules.map { it.key }

    fun getPageCount(): Int = itemCount
}
