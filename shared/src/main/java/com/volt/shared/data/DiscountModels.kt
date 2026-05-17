package com.volt.shared.data

import java.util.Calendar
import java.util.Locale

data class DiscountSchedule(
    val days: List<String> = emptyList(),
    val startTime: String = "",
    val endTime: String = "",
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
    val autoApply: Boolean = true,
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
                now.get(Calendar.MINUTE),
            )
            if (currentTime < sched.startTime || currentTime > sched.endTime) return false
        }

        return true
    }
}
