package com.ernesto.myapplication

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class TableShapeView(context: Context) : View(context) {

    enum class Shape { ROUND, SQUARE, RECTANGLE, BOOTH }

    var tableName: String = ""
        set(value) { field = value; invalidate() }

    var seatCount: Int = 0
        set(value) { field = value; invalidate() }

    var shape: Shape = Shape.SQUARE
        set(value) { field = value; requestLayout(); invalidate() }

    var isOccupied: Boolean = false
        set(value) { field = value; applyStateColors(); invalidate() }

    var isWaitingForOrder: Boolean = false
        set(value) { field = value; applyStateColors(); invalidate() }

    var guestInfo: String = ""
        set(value) { field = value; invalidate() }

    private val dp = context.resources.displayMetrics.density

    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
    }
    private val tableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5D4037.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val chairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        style = Paint.Style.FILL
    }
    private val chairBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF757575.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val boothCushionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8D6E63.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 12f * dp
        isFakeBoldText = true
    }
    private val seatsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF777777.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 9f * dp
    }
    private val guestInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD32F2F.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * dp
    }
    private val waitingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE65100.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 8f * dp
        isFakeBoldText = true
    }

    private val chairRadius = 7f * dp
    private val chairGap = 5f * dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = when (shape) {
            Shape.ROUND -> Pair(130f * dp, 130f * dp)
            Shape.SQUARE -> Pair(130f * dp, 130f * dp)
            Shape.RECTANGLE -> Pair(180f * dp, 115f * dp)
            Shape.BOOTH -> Pair(150f * dp, 120f * dp)
        }
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (shape) {
            Shape.ROUND -> drawRound(canvas)
            Shape.SQUARE -> drawSquare(canvas)
            Shape.RECTANGLE -> drawRectangle(canvas)
            Shape.BOOTH -> drawBooth(canvas)
        }
    }

    // ── ROUND ──────────────────────────────────────────────

    private fun drawRound(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val tableRadius = 38f * dp
        val orbitRadius = tableRadius + chairGap + chairRadius

        drawChairsInCircle(canvas, cx, cy, orbitRadius, seatCount)
        canvas.drawCircle(cx, cy, tableRadius, tablePaint)
        canvas.drawCircle(cx, cy, tableRadius, tableBorderPaint)
        drawText(canvas, cx, cy)
    }

    // ── SQUARE ─────────────────────────────────────────────

    private fun drawSquare(canvas: Canvas) {
        val margin = chairRadius + chairGap + 4f * dp
        val side = min(width, height) - 2 * margin
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val rect = RectF(left, top, left + side, top + side)
        val r = 6f * dp

        drawChairsAroundRect(canvas, rect)
        canvas.drawRoundRect(rect, r, r, tablePaint)
        canvas.drawRoundRect(rect, r, r, tableBorderPaint)
        drawText(canvas, width / 2f, height / 2f)
    }

    // ── RECTANGLE ──────────────────────────────────────────

    private fun drawRectangle(canvas: Canvas) {
        val margin = chairRadius + chairGap + 4f * dp
        val rect = RectF(margin, margin, width - margin, height - margin)
        val r = 6f * dp

        drawChairsAroundRect(canvas, rect)
        canvas.drawRoundRect(rect, r, r, tablePaint)
        canvas.drawRoundRect(rect, r, r, tableBorderPaint)
        drawText(canvas, width / 2f, height / 2f)
    }

    // ── BOOTH ──────────────────────────────────────────────

    private fun drawBooth(canvas: Canvas) {
        val pad = 6f * dp
        val cushionThickness = 18f * dp
        val r = 10f * dp

        val boothRect = RectF(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(boothRect, r, r, boothCushionPaint)

        val innerLeft = boothRect.left + cushionThickness
        val innerTop = boothRect.top + cushionThickness
        val innerRight = boothRect.right - cushionThickness
        val innerBottom = boothRect.bottom - 2f * dp
        val innerRect = RectF(innerLeft, innerTop, innerRight, innerBottom)
        val ir = 4f * dp

        canvas.drawRoundRect(innerRect, ir, ir, tablePaint)
        canvas.drawRoundRect(innerRect, ir, ir, tableBorderPaint)

        drawText(canvas, width / 2f, (innerTop + innerBottom) / 2f)
    }

    // ── HELPERS ────────────────────────────────────────────

    private fun drawChairsInCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, count: Int) {
        if (count <= 0) return
        val step = 360.0 / count
        for (i in 0 until count) {
            val angle = Math.toRadians(step * i - 90.0)
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            canvas.drawCircle(x, y, chairRadius, chairPaint)
            canvas.drawCircle(x, y, chairRadius, chairBorderPaint)
        }
    }

    private fun drawChairsAroundRect(canvas: Canvas, rect: RectF) {
        val count = seatCount
        if (count <= 0) return

        val w = rect.width()
        val h = rect.height()
        val perimeter = 2 * (w + h)
        val spacing = perimeter / count
        var dist = spacing / 2f

        for (i in 0 until count) {
            val (x, y) = pointOnRectPerimeter(rect, dist)
            canvas.drawCircle(x, y, chairRadius, chairPaint)
            canvas.drawCircle(x, y, chairRadius, chairBorderPaint)
            dist += spacing
        }
    }

    private fun pointOnRectPerimeter(rect: RectF, distance: Float): Pair<Float, Float> {
        val w = rect.width()
        val h = rect.height()
        val perimeter = 2 * (w + h)
        val d = distance % perimeter
        val offset = chairGap + chairRadius

        return when {
            d <= w -> Pair(rect.left + d, rect.top - offset)
            d <= w + h -> Pair(rect.right + offset, rect.top + (d - w))
            d <= 2 * w + h -> Pair(rect.right - (d - w - h), rect.bottom + offset)
            else -> Pair(rect.left - offset, rect.bottom - (d - 2 * w - h))
        }
    }

    private fun drawText(canvas: Canvas, cx: Float, cy: Float) {
        val nameY = cy - 2f * dp
        canvas.drawText(tableName, cx, nameY, namePaint)
        val seatsStr = "Seats: $seatCount"
        canvas.drawText(seatsStr, cx, nameY + 14f * dp, seatsPaint)
        if (guestInfo.isNotBlank()) {
            canvas.drawText(guestInfo, cx, nameY + 26f * dp, guestInfoPaint)
        }
        if (isWaitingForOrder) {
            val waitingY = if (guestInfo.isNotBlank()) nameY + 38f * dp else nameY + 26f * dp
            canvas.drawText("Waiting for order", cx, waitingY, waitingPaint)
        }
    }

    private fun applyStateColors() {
        when {
            isOccupied && isWaitingForOrder -> {
                // Dark red: waiting time reached, no items in cart
                tablePaint.color = 0x44B71C1C.toInt()
                tableBorderPaint.color = 0xFFB71C1C.toInt()
                boothCushionPaint.color = 0xFFB71C1C.toInt()
            }
            isOccupied -> {
                // Light red: occupied (has items or not yet past waiting threshold)
                tablePaint.color = 0x44EF9A9A.toInt()
                tableBorderPaint.color = 0xFFEF9A9A.toInt()
                boothCushionPaint.color = 0xFFEF9A9A.toInt()
            }
            else -> {
                tablePaint.color = Color.TRANSPARENT
                tableBorderPaint.color = 0xFF5D4037.toInt()
                boothCushionPaint.color = 0xFF8D6E63.toInt()
            }
        }
    }

    companion object {
        fun shapeFromString(value: String?): Shape {
            return when (value?.uppercase()) {
                "ROUND" -> Shape.ROUND
                "RECTANGLE" -> Shape.RECTANGLE
                "BOOTH" -> Shape.BOOTH
                else -> Shape.SQUARE
            }
        }

        fun shapeToString(shape: Shape): String = shape.name
    }
}
