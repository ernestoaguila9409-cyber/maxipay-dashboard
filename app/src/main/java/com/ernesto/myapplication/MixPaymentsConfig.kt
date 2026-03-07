package com.ernesto.myapplication

import android.content.Context
import android.content.SharedPreferences

object MixPaymentsConfig {
    private const val PREFS_NAME = "app_config"
    private const val KEY_MIX_PAYMENTS_ENABLED = "mix_payments_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MIX_PAYMENTS_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIX_PAYMENTS_ENABLED, enabled).apply()
    }
}
