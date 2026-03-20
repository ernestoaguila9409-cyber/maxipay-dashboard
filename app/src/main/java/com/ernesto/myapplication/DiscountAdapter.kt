package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SwitchCompat
import java.util.Calendar
import java.util.Locale

data class DiscountSchedule(
    val days: List<String> = emptyList(),
    val startTime: String = "",
    val endTime: String = ""
)

data class DiscountItem(
    val id: String,
    val name: String,
    val type: String,
    val value: Double,
    val applyTo: String,
    val active: Boolean = true,
    val applyScope: String = "order",
    val itemIds: List<String> = emptyList(),
    val schedule: DiscountSchedule? = null,
    val autoApply: Boolean = true
) {
    fun isScheduleValid(): Boolean {
        val sched = schedule ?: return true
        if (sched.days.isEmpty() && sched.startTime.isBlank() && sched.endTime.isBlank()) return true

        val now = Calendar.getInstance()
        val dayOfWeek = when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            Calendar.SUNDAY -> "SUN"
            else -> ""
        }

        if (sched.days.isNotEmpty() && !sched.days.contains(dayOfWeek)) return false

        if (sched.startTime.isNotBlank() && sched.endTime.isNotBlank()) {
            val currentTime = String.format(
                Locale.US, "%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE)
            )
            if (currentTime < sched.startTime || currentTime > sched.endTime) return false
        }

        return true
    }
}

class DiscountAdapter(
    private var items: List<DiscountItem> = emptyList(),
    private val onItemClick: (DiscountItem) -> Unit = {},
    private val onToggleActive: (DiscountItem, Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<DiscountAdapter.VH>() {

    fun submitList(list: List<DiscountItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_discount, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bind(item, onItemClick, onToggleActive)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtName: TextView = itemView.findViewById(R.id.txtDiscountName)
        private val txtTypeAndValue: TextView = itemView.findViewById(R.id.txtDiscountTypeAndValue)
        private val txtApplyTo: TextView = itemView.findViewById(R.id.txtDiscountApplyTo)
        private val switchActive: SwitchCompat = itemView.findViewById(R.id.switchDiscountActive)

        fun bind(
            item: DiscountItem,
            onItemClick: (DiscountItem) -> Unit,
            onToggleActive: (DiscountItem, Boolean) -> Unit
        ) {
            txtName.text = item.name

            val typeLabel = if (item.type == "FIXED") "Fixed" else "Percentage"
            val valueStr = if (item.type == "FIXED") {
                String.format(Locale.US, "$%.2f", item.value)
            } else {
                String.format(Locale.US, "%.1f%%", item.value)
            }
            txtTypeAndValue.text = "$typeLabel · $valueStr"

            val scopeLabel = when (item.applyScope) {
                "item" -> "Applies to: Specific Items"
                "manual" -> "Applies to: Manual (Checkout)"
                else -> "Applies to: Entire Order"
            }
            val autoLabel = if (item.autoApply) "" else " · Manual only"
            val schedLabel = item.schedule?.let { s ->
                if (s.days.isNotEmpty()) " · ${s.days.joinToString(",")}" else ""
            } ?: ""
            txtApplyTo.text = "$scopeLabel$autoLabel$schedLabel"

            switchActive.setOnCheckedChangeListener(null)
            switchActive.isChecked = item.active
            setSwitchColors(switchActive, item.active)
            switchActive.setOnCheckedChangeListener { _, isChecked ->
                onToggleActive(item, isChecked)
                setSwitchColors(switchActive, isChecked)
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
