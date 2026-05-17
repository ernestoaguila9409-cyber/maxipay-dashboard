package com.volt.maximobile

/**
 * Floor-plan scaling for maxi-mobile (Dejavoo P8 and other small POS screens).
 * The main [app] module maps logical coords to the full canvas width.
 * Maxi-mobile maps to the **visible viewport** and applies [POSITION_SPACING_SCALE]
 * so tables sit closer together on narrow POS screens than on a wide tablet.
 */
object TableLayoutMobileScale {

    /** Table widget size in the floor-plan editor (0.6 = 60% of full tablet size). */
    const val EDITOR_TABLE_SIZE_SCALE = 0.6f

    /** Dine-in / reservation picker table size on small POS screens. */
    const val DINE_IN_TABLE_SIZE_SCALE = 0.65f

    /**
     * Compresses table spacing on maxi-mobile (from top-left).
     * Below 1.0 = tables closer than proportional; main app uses 1.0.
     */
    const val POSITION_SPACING_SCALE = 0.72f

    fun layoutToScreen(
        xL: Double,
        yL: Double,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        layoutCanvasW: Double,
        layoutCanvasH: Double,
    ): Pair<Float, Float> {
        val rawX = (xL * viewportWidthPx / layoutCanvasW).toFloat()
        val rawY = (yL * viewportHeightPx / layoutCanvasH).toFloat()
        if (POSITION_SPACING_SCALE == 1f) return Pair(rawX, rawY)
        return Pair(rawX * POSITION_SPACING_SCALE, rawY * POSITION_SPACING_SCALE)
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
        val rawX = if (POSITION_SPACING_SCALE == 1f) screenX else screenX / POSITION_SPACING_SCALE
        val rawY = if (POSITION_SPACING_SCALE == 1f) screenY else screenY / POSITION_SPACING_SCALE
        return Pair(
            rawX.toDouble() * layoutCanvasW / w,
            rawY.toDouble() * layoutCanvasH / h,
        )
    }
}
