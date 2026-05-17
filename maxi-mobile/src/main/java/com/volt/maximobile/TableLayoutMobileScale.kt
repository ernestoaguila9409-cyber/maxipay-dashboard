package com.volt.maximobile

/**
 * Floor-plan scaling for maxi-mobile (Dejavoo P8 and other small POS screens).
 * The main [app] module maps logical coords to the full canvas width.
 * Maxi-mobile maps to the **visible viewport** so the full width and height are usable
 * (e.g. a table at the right edge on the tablet also reaches the right edge on P8).
 */
object TableLayoutMobileScale {

    /** Table widget size in the floor-plan editor (0.6 = 60% of full tablet size). */
    const val EDITOR_TABLE_SIZE_SCALE = 0.6f

    /** Dine-in / reservation picker table size on small POS screens. */
    const val DINE_IN_TABLE_SIZE_SCALE = 0.65f

    fun layoutToScreen(
        xL: Double,
        yL: Double,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        layoutCanvasW: Double,
        layoutCanvasH: Double,
    ): Pair<Float, Float> {
        return Pair(
            (xL * viewportWidthPx / layoutCanvasW).toFloat(),
            (yL * viewportHeightPx / layoutCanvasH).toFloat(),
        )
    }

    fun screenToLayout(
        screenX: Float,
        screenY: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        layoutCanvasW: Double,
        layoutCanvasH: Double,
    ): Pair<Double, Double> {
        val w = viewportWidthPx.coerceAtLeast(1f)
        val h = viewportHeightPx.coerceAtLeast(1f)
        return Pair(
            screenX.toDouble() * layoutCanvasW / w,
            screenY.toDouble() * layoutCanvasH / h,
        )
    }
}
