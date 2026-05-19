package com.ernesto.myapplication

import android.content.Context
import android.content.SharedPreferences

/**
 * Controls what happens on the register after an order is fully paid.
 */
object ReceiptPrintingConfig {

    const val MODE_AUTO_PRINT_AND_ASK = "AUTO_PRINT_AND_ASK"
    const val MODE_PROMPT_AFTER_ORDER = "PROMPT_AFTER_ORDER"
    const val MODE_DO_NOT_PRINT = "DO_NOT_PRINT"

    private const val PREFS_NAME = "receipt_printing_config"
    private const val KEY_MODE = "mode"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_PROMPT_AFTER_ORDER) ?: MODE_PROMPT_AFTER_ORDER

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_AUTO_PRINT_AND_ASK -> MODE_AUTO_PRINT_AND_ASK
            MODE_DO_NOT_PRINT -> MODE_DO_NOT_PRINT
            else -> MODE_PROMPT_AFTER_ORDER
        }
        prefs(context).edit().putString(KEY_MODE, normalized).apply()
    }

    fun shouldAutoPrintOnPayment(context: Context): Boolean =
        getMode(context) == MODE_AUTO_PRINT_AND_ASK

    fun shouldPromptAfterPayment(context: Context): Boolean =
        getMode(context) == MODE_PROMPT_AFTER_ORDER

    fun shouldSkipReceiptFlow(context: Context): Boolean =
        getMode(context) == MODE_DO_NOT_PRINT
}
