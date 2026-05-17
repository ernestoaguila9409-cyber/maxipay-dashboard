package com.volt.shared

/**
 * Maps logical floor-plan coordinates (Firestore `x`/`y` on `canvasWidth`×`canvasHeight`)
 * to on-screen positions. Reserves table width/height so tables at the right/bottom edge
 * stay fully visible — used by both tablet and maxi-mobile apps.
 */
object TableLayoutCoords {

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
