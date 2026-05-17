package com.volt.maximobile

/**
 * Floor-plan scaling for maxi-mobile (Dejavoo P8 and other small POS screens).
 * Maps logical coords to the visible viewport. Reserves table width/height so a table
 * at the right edge on a tablet is fully visible on P8 (not clipped).
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
        tableWidthPx: Int = 0,
        tableHeightPx: Int = 0,
    ): Pair<Float, Float> {
        val maxX = if (tableWidthPx > 0) {
            (viewportWidthPx - tableWidthPx).coerceAtLeast(0f)
        } else {
            viewportWidthPx
        }
        val maxY = if (tableHeightPx > 0) {
            (viewportHeightPx - tableHeightPx).coerceAtLeast(0f)
        } else {
            viewportHeightPx
        }
        val fracX = (xL / layoutCanvasW).toFloat().coerceIn(0f, 1f)
        val fracY = (yL / layoutCanvasH).toFloat().coerceIn(0f, 1f)
        return Pair(fracX * maxX, fracY * maxY)
    }

    fun screenToLayout(
        screenX: Float,
        screenY: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        layoutCanvasW: Double,
        layoutCanvasH: Double,
        tableWidthPx: Int = 0,
        tableHeightPx: Int = 0,
    ): Pair<Double, Double> {
        val maxX = if (tableWidthPx > 0) {
            (viewportWidthPx - tableWidthPx).coerceAtLeast(1f)
        } else {
            viewportWidthPx.coerceAtLeast(1f)
        }
        val maxY = if (tableHeightPx > 0) {
            (viewportHeightPx - tableHeightPx).coerceAtLeast(1f)
        } else {
            viewportHeightPx.coerceAtLeast(1f)
        }
        val fracX = (screenX / maxX).coerceIn(0f, 1f)
        val fracY = (screenY / maxY).coerceIn(0f, 1f)
        return Pair(
            fracX.toDouble() * layoutCanvasW,
            fracY.toDouble() * layoutCanvasH,
        )
    }
}
