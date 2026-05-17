package com.volt.maximobile

/**
 * Floor-plan scaling for maxi-mobile (Dejavoo P8 and other small POS screens).
 * The main [app] module uses full table size and 1:1 logical position mapping;
 * maxi-mobile uses smaller tables so the floor plan fits the small screen.
 * Positions map proportionally (same relative placement on both devices).
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
