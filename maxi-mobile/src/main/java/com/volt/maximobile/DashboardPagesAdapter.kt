package com.volt.maximobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

class DashboardPagesAdapter(
    private val onClick: (DashboardCardItem) -> Unit,
) : RecyclerView.Adapter<DashboardPagesAdapter.PageViewHolder>() {

    private val pages = mutableListOf<List<DashboardCardItem>>()

    class PageViewHolder(val recyclerView: RecyclerView) : RecyclerView.ViewHolder(recyclerView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dashboard_page, parent, false)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerDashboardPage)
        recycler.layoutManager = GridLayoutManager(parent.context, 2)
        recycler.isNestedScrollingEnabled = false
        recycler.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        recycler.adapter = DashboardCardAdapter(onClick)
        return PageViewHolder(recycler)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val adapter = holder.recyclerView.adapter as DashboardCardAdapter
        adapter.submitList(pages[position])
        holder.recyclerView.post {
            resizeTiles(holder.recyclerView, adapter)
        }
    }

    private fun resizeTiles(recyclerView: RecyclerView, adapter: DashboardCardAdapter) {
        val itemCount = adapter.itemCount
        if (itemCount == 0 || recyclerView.height <= 0) return
        val rowCount = ceil(itemCount / 2.0).toInt().coerceAtLeast(1)
        val density = recyclerView.resources.displayMetrics.density
        val verticalMarginPx = (6f * density).toInt() * 2
        val padding = recyclerView.paddingTop + recyclerView.paddingBottom
        val available = recyclerView.height - padding
        val tileHeight = ((available - verticalMarginPx * rowCount) / rowCount)
            .coerceAtLeast((120f * density).toInt())
        adapter.setTileHeightPx(tileHeight)
    }

    override fun getItemCount(): Int = pages.size

    fun submitPages(newPages: List<List<DashboardCardItem>>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }
}
