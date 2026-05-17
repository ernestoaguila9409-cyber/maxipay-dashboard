package com.volt.maximobile

import com.volt.shared.TableLayoutCoords

/**
 * Floor-plan scaling for maxi-mobile (Dejavoo P8 and other small POS screens).
 * Coordinate math is shared with the tablet app via [TableLayoutCoords].
 */
object TableLayoutMobileScale {

    /** Table widget size in the floor-plan editor (0.6 = 60% of full tablet size). */
    const val EDITOR_TABLE_SIZE_SCALE = 0.6f

    /** Dine-in uses the same scale as the editor so layout and picker match. */
    const val DINE_IN_TABLE_SIZE_SCALE = EDITOR_TABLE_SIZE_SCALE

    fun layoutToScreen(
        xL: Double,
        yL: Double,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        layoutCanvasW: Double,
        layoutCanvasH: Double,
        tableWidthPx: Int = 0,
        tableHeightPx: Int = 0,
    ): Pair<Float, Float> = TableLayoutCoords.layoutToScreen(
        xL, yL, viewportWidthPx, viewportHeightPx, layoutCanvasW, layoutCanvasH,
        tableWidthPx, tableHeightPx,
    )

    fun screenToLayout(
        screenX: Float,
        screenY: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        layoutCanvasW: Double,
        layoutCanvasH: Double,
        tableWidthPx: Int = 0,
        tableHeightPx: Int = 0,
    ): Pair<Double, Double> = TableLayoutCoords.screenToLayout(
        screenX, screenY, viewportWidthPx, viewportHeightPx, layoutCanvasW, layoutCanvasH,
        tableWidthPx, tableHeightPx,
    )
}
