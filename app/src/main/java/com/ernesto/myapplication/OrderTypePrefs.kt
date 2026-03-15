package com.ernesto.myapplication

import android.content.Context

object OrderTypePrefs {
    private const val PREFS_NAME = "order_type_prefs"
    private const val KEY_BAR_TAB = "bar_tab_enabled"
    private const val KEY_TO_GO = "to_go_enabled"
    private const val KEY_DINE_IN = "dine_in_enabled"
    private const val KEY_WAITING_ALERT_MINUTES = "waiting_alert_minutes"
    private const val DEFAULT_WAITING_ALERT_MINUTES = 5

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBarTabEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BAR_TAB, true)

    fun setBarTabEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_BAR_TAB, enabled).apply()

    fun isToGoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TO_GO, true)

    fun setToGoEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_TO_GO, enabled).apply()

    fun isDineInEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DINE_IN, true)

    fun setDineInEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_DINE_IN, enabled).apply()

    fun getWaitingAlertMinutes(context: Context): Int =
        prefs(context).getInt(KEY_WAITING_ALERT_MINUTES, DEFAULT_WAITING_ALERT_MINUTES)

    fun setWaitingAlertMinutes(context: Context, minutes: Int) =
        prefs(context).edit().putInt(KEY_WAITING_ALERT_MINUTES, minutes).apply()
}
