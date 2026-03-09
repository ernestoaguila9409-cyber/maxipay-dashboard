package com.ernesto.myapplication.engine

import java.util.Locale

object MoneyUtils {

    fun centsToDouble(cents: Long): Double {
        return cents / 100.0
    }

    fun centsToDisplay(cents: Long): String {
        return String.format(Locale.US, "$%.2f", cents / 100.0)
    }

    fun dollarsToCents(amount: Double): Long {
        return kotlin.math.round(amount * 100).toLong()
    }
}