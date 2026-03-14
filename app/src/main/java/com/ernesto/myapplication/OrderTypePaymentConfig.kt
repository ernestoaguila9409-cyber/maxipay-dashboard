package com.ernesto.myapplication

import android.content.Context
import android.content.SharedPreferences

object OrderTypePaymentConfig {
    private const val PREFS_NAME = "order_type_payment_config"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(orderType: String, method: String): String =
        "${orderType.lowercase()}_${method}_enabled"

    fun isCreditEnabled(context: Context, orderType: String): Boolean =
        prefs(context).getBoolean(key(orderType, "credit"), true)

    fun setCreditEnabled(context: Context, orderType: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(key(orderType, "credit"), enabled).apply()
    }

    fun isDebitEnabled(context: Context, orderType: String): Boolean =
        prefs(context).getBoolean(key(orderType, "debit"), true)

    fun setDebitEnabled(context: Context, orderType: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(key(orderType, "debit"), enabled).apply()
    }

    fun isCashEnabled(context: Context, orderType: String): Boolean =
        prefs(context).getBoolean(key(orderType, "cash"), true)

    fun setCashEnabled(context: Context, orderType: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(key(orderType, "cash"), enabled).apply()
    }

    fun isMixPaymentsEnabled(context: Context, orderType: String): Boolean =
        prefs(context).getBoolean(key(orderType, "mix"), true)

    fun setMixPaymentsEnabled(context: Context, orderType: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(key(orderType, "mix"), enabled).apply()
    }
}
