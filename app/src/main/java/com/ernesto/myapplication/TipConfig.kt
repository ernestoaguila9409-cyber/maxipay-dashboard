package com.ernesto.myapplication

import android.content.Context
import android.content.SharedPreferences

object TipConfig {
    private const val PREFS_NAME = "tip_config"
    private const val KEY_TIPS_ENABLED = "tips_enabled"
    private const val KEY_CUSTOM_TIP_ENABLED = "custom_tip_enabled"
    private const val KEY_CALCULATION_BASE = "calculation_base"
    private const val PRESET_COUNT = 5
    private const val NOT_SET = -1

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isTipsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TIPS_ENABLED, true)

    fun setTipsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TIPS_ENABLED, enabled).apply()
    }

    fun isCustomTipEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CUSTOM_TIP_ENABLED, true)

    fun setCustomTipEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_TIP_ENABLED, enabled).apply()
    }

    private val DEFAULTS = listOf(15, 18, 20, NOT_SET, NOT_SET)

    fun getPresetValue(context: Context, index: Int): Int {
        val default = if (index < DEFAULTS.size) DEFAULTS[index] else NOT_SET
        return prefs(context).getInt("preset_${index + 1}", default)
    }

    fun setPresetValue(context: Context, index: Int, value: Int) {
        prefs(context).edit().putInt("preset_${index + 1}", value).apply()
    }

    fun clearPreset(context: Context, index: Int) {
        prefs(context).edit().putInt("preset_${index + 1}", NOT_SET).apply()
    }

    fun getPresetCount(): Int = PRESET_COUNT

    fun getPresets(context: Context): List<Int> {
        val result = mutableListOf<Int>()
        for (i in 0 until PRESET_COUNT) {
            val value = getPresetValue(context, i)
            if (value > 0) result.add(value)
        }
        return result
    }

    fun getCalculationBase(context: Context): String =
        prefs(context).getString(KEY_CALCULATION_BASE, "TOTAL") ?: "TOTAL"

    fun setCalculationBase(context: Context, base: String) {
        prefs(context).edit().putString(KEY_CALCULATION_BASE, base).apply()
    }

    fun isSubtotalBased(context: Context): Boolean =
        getCalculationBase(context) == "SUBTOTAL"

    fun calculateTip(subtotal: Double, tax: Double, tipPercentage: Double, context: Context): Double {
        val base = if (isSubtotalBased(context)) subtotal else subtotal + tax
        return base * (tipPercentage / 100.0)
    }
}
