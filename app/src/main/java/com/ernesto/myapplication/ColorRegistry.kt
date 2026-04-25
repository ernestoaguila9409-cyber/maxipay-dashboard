package com.ernesto.myapplication

import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Maps color keys (from dashboard config) to hex colors.
 * Must match the palette in the web dashboard.
 */
object ColorRegistry {

    private val COLORS = mapOf(
        "green" to 0xFF2E7D32.toInt(),
        "orange" to 0xFFE65100.toInt(),
        "teal" to 0xFF00897B.toInt(),
        "purple" to 0xFF6A4FB3.toInt(),
        "blue" to 0xFF1976D2.toInt(),
        "red" to 0xFFC62828.toInt(),
        "amber" to 0xFFF9A825.toInt(),
        "indigo" to 0xFF3949AB.toInt(),
        "pink" to 0xFFAD1457.toInt(),
        "cyan" to 0xFF0097A7.toInt(),
        "uber_green" to 0xFF06C167.toInt(),
    )

    private const val DEFAULT_COLOR = 0xFF6A4FB3.toInt() // purple

    fun getColor(colorKey: String): Int = COLORS[colorKey] ?: DEFAULT_COLOR

    fun getBackgroundDrawable(view: View, colorKey: String): GradientDrawable {
        val radiusPx = 8 * view.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(getColor(colorKey))
            cornerRadius = radiusPx
        }
    }
}
