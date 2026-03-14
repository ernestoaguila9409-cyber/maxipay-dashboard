package com.ernesto.myapplication

import android.content.Context
import android.content.SharedPreferences

object PaymentMethodsConfig {
    private const val PREFS_NAME = "app_config"
    private const val KEY_CREDIT_ENABLED = "payment_credit_enabled"
    private const val KEY_DEBIT_ENABLED = "payment_debit_enabled"
    private const val KEY_CASH_ENABLED = "payment_cash_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCreditEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CREDIT_ENABLED, true)

    fun setCreditEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CREDIT_ENABLED, enabled).apply()
    }

    fun isDebitEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBIT_ENABLED, true)

    fun setDebitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBIT_ENABLED, enabled).apply()
    }

    fun isCashEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CASH_ENABLED, true)

    fun setCashEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CASH_ENABLED, enabled).apply()
    }

    private const val KEY_SPLIT_ENABLED = "payment_split_enabled"

    fun isSplitPaymentsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPLIT_ENABLED, true)

    fun setSplitPaymentsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPLIT_ENABLED, enabled).apply()
    }
}
