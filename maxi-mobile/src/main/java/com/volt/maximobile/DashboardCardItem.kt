package com.volt.maximobile

import androidx.annotation.DrawableRes

data class DashboardCardItem(
    val moduleKey: String,
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val color: Int,
    val badge: Int = 0,
)
