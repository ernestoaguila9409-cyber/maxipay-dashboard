package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class DashboardAdapter(
    private val modules: MutableList<DashboardModule>,
    private val onClick: (DashboardModule) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val PAGE_SIZE = 9
        private const val VIEW_TYPE_HOME = 1
        private const val VIEW_TYPE_OTHER = 2
    }

    private val badges = mutableMapOf<String, Int>()

    class HomeVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val heroSlots: List<ViewGroup> = listOf(
            itemView.findViewById(R.id.heroSlot0),
            itemView.findViewById(R.id.heroSlot1),
            itemView.findViewById(R.id.heroSlot2),
        )
        val managementGrid: GridLayout = itemView.findViewById(R.id.managementGrid)
    }

    class OtherVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val grid: GridLayout = itemView.findViewById(R.id.secondaryGrid)
    }

    override fun getItemViewType(position: Int): Int =
        if (position == 0) VIEW_TYPE_HOME else VIEW_TYPE_OTHER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HOME -> HomeVH(
                inflater.inflate(R.layout.page_dashboard_home, parent, false),
            )
            else -> OtherVH(
                inflater.inflate(R.layout.page_dashboard_secondary, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HomeVH -> bindHomePage(holder)
            is OtherVH -> bindOtherPage(holder, position)
        }
    }

    private fun bindHomePage(holder: HomeVH) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val cellMargin = (4 * density).toInt()

        holder.heroSlots.forEach { it.removeAllViews() }
        holder.managementGrid.removeAllViews()

        for (i in 0 until 3) {
            val slot = holder.heroSlots[i]
            if (i < modules.size) {
                val view = LayoutInflater.from(ctx).inflate(R.layout.item_dashboard_hero, slot, false)
                bindHero(view, modules[i])
                slot.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }

        for (row in 0 until 2) {
            for (col in 0 until 3) {
                val idx = 3 + row * 3 + col
                val view = LayoutInflater.from(ctx).inflate(R.layout.item_dashboard_management, holder.managementGrid, false)
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(col, 1f),
                ).apply {
                    width = 0
                    height = 0
                    setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                }
                if (idx < modules.size) {
                    bindManagement(view, modules[idx])
                } else {
                    view.visibility = View.INVISIBLE
                }
                holder.managementGrid.addView(view, params)
            }
        }
    }

    private fun bindOtherPage(holder: OtherVH, position: Int) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val cellMargin = (6 * density).toInt()
        val startIdx = position * PAGE_SIZE

        holder.grid.removeAllViews()
        for (i in 0 until PAGE_SIZE) {
            val moduleIdx = startIdx + i
            val view = LayoutInflater.from(ctx).inflate(R.layout.item_dashboard_secondary_tile, holder.grid, false)
            val row = i / 3
            val col = i % 3
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(col, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
            }
            if (moduleIdx < modules.size) {
                bindSecondaryTile(view, modules[moduleIdx])
            } else {
                view.visibility = View.INVISIBLE
            }
            holder.grid.addView(view, params)
        }
    }

    private fun bindHero(view: View, module: DashboardModule) {
        val container = view.findViewById<LinearLayout>(R.id.btnContainer)
        val icon = view.findViewById<ImageView>(R.id.imgIcon)
        val label = view.findViewById<TextView>(R.id.txtLabel)
        val badgeView = view.findViewById<TextView>(R.id.badge)

        label.text = module.label
        icon.setImageResource(IconRegistry.getResId(module.iconName))

        val colorKey = tileColorKey(module)
        container.background = ColorRegistry.getBackgroundDrawable(container, colorKey, 16f)
        container.setOnClickListener { onClick(module) }

        applyBadge(badgeView, module.key)
    }

    private fun bindManagement(view: View, module: DashboardModule) {
        val card = view.findViewById<MaterialCardView>(R.id.managementCard)
        val accentBar = view.findViewById<View>(R.id.accentBar)
        val iconCircle = view.findViewById<FrameLayout>(R.id.iconCircle)
        val icon = view.findViewById<ImageView>(R.id.imgIcon)
        val title = view.findViewById<TextView>(R.id.txtTitle)
        val subtitle = view.findViewById<TextView>(R.id.txtSubtitle)
        val badgeView = view.findViewById<TextView>(R.id.badge)

        val accentKey = managementAccentKey(module)
        val accentColor = ColorRegistry.getColor(accentKey)
        accentBar.setBackgroundColor(accentColor)

        iconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
        }
        icon.setImageResource(IconRegistry.getResId(module.iconName))
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)

        title.text = toTitleCase(module.label)
        val desc = subtitleFor(view.context, module.key)
        if (desc.isNotBlank()) {
            subtitle.visibility = View.VISIBLE
            subtitle.text = desc
        } else {
            subtitle.visibility = View.GONE
        }

        card.setOnClickListener { onClick(module) }
        applyBadge(badgeView, module.key)
    }

    private fun bindSecondaryTile(view: View, module: DashboardModule) {
        val card = view.findViewById<MaterialCardView>(R.id.tileCard)
        val iconCircle = view.findViewById<FrameLayout>(R.id.iconCircle)
        val icon = view.findViewById<ImageView>(R.id.imgIcon)
        val label = view.findViewById<TextView>(R.id.txtLabel)
        val badgeView = view.findViewById<TextView>(R.id.badge)

        label.text = module.label
        icon.setImageResource(IconRegistry.getResId(module.iconName))

        val accentKey = secondaryAccentKey(module)
        val accentColor = ColorRegistry.getColor(accentKey)
        val soft = Color.argb(0x4D, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        iconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(soft)
        }
        icon.imageTintList = ColorStateList.valueOf(accentColor)

        card.setOnClickListener { onClick(module) }
        applyBadge(badgeView, module.key)
    }

    private fun applyBadge(badgeView: TextView, moduleKey: String) {
        val count = badges[moduleKey] ?: 0
        if (count > 0) {
            badgeView.text = count.toString()
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView.visibility = View.GONE
        }
    }

    private fun tileColorKey(module: DashboardModule): String {
        if (module.colorKey.isNotBlank()) return module.colorKey
        return when (module.key) {
            "dine_in" -> "green"
            "to_go" -> "orange"
            "bar" -> "teal"
            else -> "purple"
        }
    }

    private fun managementAccentKey(module: DashboardModule): String {
        return when (module.key) {
            "transactions" -> "indigo"
            "settle_batch" -> "blue"
            "employees" -> "purple"
            "customers" -> "pink"
            "orders" -> "blue"
            "setup" -> "purple"
            "online_orders" -> "uber_green"
            "modifiers" -> "indigo"
            "inventory" -> "teal"
            "reports" -> "blue"
            "printers" -> "cyan"
            "cash_flow" -> "green"
            "tips" -> "amber"
            "reservation" -> "pink"
            else -> "purple"
        }
    }

    private fun secondaryAccentKey(module: DashboardModule): String {
        return when (module.key) {
            "modifiers" -> "indigo"
            "inventory" -> "teal"
            "reports" -> "blue"
            "printers" -> "cyan"
            "cash_flow" -> "green"
            "reservation" -> "pink"
            "tips" -> "amber"
            "online_orders" -> "uber_green"
            "setup" -> "purple"
            else -> managementAccentKey(module)
        }
    }

    private fun subtitleFor(context: android.content.Context, key: String): String {
        val resId = when (key) {
            "online_orders" -> R.string.dashboard_desc_online_orders
            "transactions" -> R.string.dashboard_desc_transactions
            "settle_batch" -> R.string.dashboard_desc_settle_batch
            "employees" -> R.string.dashboard_desc_employees
            "customers" -> R.string.dashboard_desc_customers
            "orders" -> R.string.dashboard_desc_orders
            "setup" -> R.string.dashboard_desc_setup
            "modifiers" -> R.string.dashboard_desc_modifiers
            "inventory" -> R.string.dashboard_desc_inventory
            "reports" -> R.string.dashboard_desc_reports
            "printers" -> R.string.dashboard_desc_printers
            "cash_flow" -> R.string.dashboard_desc_cash_flow
            "tips" -> R.string.dashboard_desc_tips
            "reservation" -> R.string.dashboard_desc_reservation
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else ""
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

    private fun toTitleCase(text: String): String =
        text.lowercase().split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
