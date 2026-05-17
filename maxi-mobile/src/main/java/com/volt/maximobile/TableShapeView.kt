package com.volt.maximobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TableShapeView(context: Context) : View(context) {

    enum class Shape { ROUND, SQUARE, RECTANGLE, BOOTH }

    /** Pill style for the bottom status row (Dine-In / reservation picker). */
    enum class StatusPill { NONE, WAITING, RESERVED, OCCUPIED, OPEN_ORDER }

    var tableName: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var seatCount: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var shape: Shape = Shape.SQUARE
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var isOccupied: Boolean = false
        set(value) {
            field = value
            applyStateColors()
            invalidate()
        }

    var isWaitingForOrder: Boolean = false
        set(value) {
            field = value
            applyStateColors()
            invalidate()
        }

    /** Table doc [status] == RESERVED — shown when not occupied by an open order. */
    var isReserved: Boolean = false
        set(value) {
            field = value
            applyStateColors()
            invalidate()
        }

    /** Highlight while picking tables to link (join group). */
    var isJoinLinkSelected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Floor-plan editor selection (rotate handle visible). */
    var isEditorSelected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * When set, [onMeasure] uses this exact pixel size instead of the default for [shape].
     * Used for linked tables that render as one combined rectangle.
     */
    var forcedSizePx: Pair<Int, Int>? = null
        set(value) {
            field = value
            requestLayout()
        }

    /** Legacy single-line slot (e.g. reservation picker). Prefer [detailStatusLabel] + [statusPill]. */
    var guestInfo: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** e.g. "Party of 4". */
    var detailPartyOf: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Status caption drawn inside the pill (e.g. Waiting for order, Reserved). */
    var detailStatusLabel: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var statusPill: StatusPill = StatusPill.NONE
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val dp = context.resources.displayMetrics.density

    /** Vertical gap between name and seat badge / detail rows. */
    private val detailLineGap = 5f * dp

    /** Inner padding for label block inside the card. */
    private val contentPadding = 8f * dp

    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
    }
    private val tableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5D4037.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val boothCushionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8D6E63.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 15f * dp
        isFakeBoldText = true
    }
    private val seatBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8E8.toInt()
        style = Paint.Style.FILL
    }
    private val seatBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF616161.toInt()
        textAlign = Paint.Align.LEFT
        textSize = 10.5f * dp
        isFakeBoldText = true
    }
    private val seatBadgeIconTint = 0xFF757575.toInt()
    private var seatPersonIcon: Drawable? = null
    private val partyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF424242.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * dp
    }
    private val statusLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * dp
        isFakeBoldText = true
    }
    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val joinRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3949AB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * dp
    }
    private val editorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
    }

    /** Inset from view edge to the filled table shape (stroke sits inside view). */
    private val tableInset = 10f * dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val forced = forcedSizePx
        if (forced != null) {
            setMeasuredDimension(
                forced.first.coerceAtLeast((32f * dp).toInt()),
                forced.second.coerceAtLeast((32f * dp).toInt()),
            )
        } else {
            val (wDp, hDp) = sizeDpForShape(shape)
            val extra = extraHeightDpForDetailBlock()
            setMeasuredDimension(
                (wDp * dp).toInt(),
                ((hDp + extra) * dp).toInt(),
            )
        }
    }

    private fun extraHeightDpForDetailBlock(): Float {
        if (usesStructuredDetail()) return 30f
        if (guestInfo.isNotBlank()) return 18f
        return 0f
    }

    private fun usesStructuredDetail(): Boolean =
        detailPartyOf.isNotBlank() ||
            detailStatusLabel.isNotBlank() ||
            statusPill != StatusPill.NONE

    /** Hide "Seats: N" for busy tables (occupied / reserved / structured floor state). */
    private fun showSeatsRow(): Boolean = !isOccupied && !isReserved && !usesStructuredDetail() && guestInfo.isBlank()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (shape) {
            Shape.ROUND -> drawRound(canvas)
            Shape.SQUARE -> drawSquare(canvas)
            Shape.RECTANGLE -> drawRectangle(canvas)
            Shape.BOOTH -> drawBooth(canvas)
        }
        if (isJoinLinkSelected) {
            drawJoinSelectionRing(canvas, joinRingPaint)
        }
        if (isEditorSelected) {
            drawJoinSelectionRing(canvas, editorRingPaint)
        }
    }

    private fun drawJoinSelectionRing(canvas: Canvas, ringPaint: Paint) {
        val inset = 4f * dp
        when (shape) {
            Shape.ROUND -> {
                val cx = width / 2f
                val cy = height / 2f
                val r = min(width, height) / 2f - inset
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
            Shape.SQUARE, Shape.RECTANGLE -> {
                val rect = RectF(inset, inset, width - inset, height - inset)
                canvas.drawRoundRect(rect, 8f * dp, 8f * dp, ringPaint)
            }
            Shape.BOOTH -> {
                val rect = RectF(inset, inset, width - inset, height - inset)
                canvas.drawRoundRect(rect, 10f * dp, 10f * dp, ringPaint)
            }
        }
    }

    private fun drawRound(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f - tableInset
        val tableRadius = min(44f * dp, maxR)
        canvas.drawCircle(cx, cy, tableRadius, tablePaint)
        canvas.drawCircle(cx, cy, tableRadius, tableBorderPaint)
        // Square inscribed in circle for text (keeps labels inside the round card)
        val side = tableRadius * sqrt(2f) * 0.98f
        val textRect = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        drawTextInCard(canvas, textRect)
    }

    private fun drawSquare(canvas: Canvas) {
        val side = min(width, height) - 2f * tableInset
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val rect = RectF(left, top, left + side, top + side)
        val r = 6f * dp
        canvas.drawRoundRect(rect, r, r, tablePaint)
        canvas.drawRoundRect(rect, r, r, tableBorderPaint)
        drawTextInCard(canvas, rect)
    }

    private fun drawRectangle(canvas: Canvas) {
        val rect = RectF(tableInset, tableInset, width - tableInset, height - tableInset)
        val r = 8f * dp
        canvas.drawRoundRect(rect, r, r, tablePaint)
        canvas.drawRoundRect(rect, r, r, tableBorderPaint)
        drawTextInCard(canvas, rect)
    }

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

        drawTextInCard(canvas, innerRect)
    }

    private fun lineBlockHeight(paint: Paint, text: String): Float {
        if (text.isBlank()) return 0f
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }

    private fun seatBadgeBlockHeight(): Float {
        if (seatCount <= 0) return 0f
        val padV = 4f * dp
        val iconSize = 11f * dp
        val fm = seatBadgeTextPaint.fontMetrics
        return padV * 2f + max(iconSize, fm.descent - fm.ascent)
    }

    /** Scale [paint.textSize] down so [text] fits [maxWidth]; restores size in [finally]. */
    private inline fun withFittedTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        minSizePx: Float,
        maxSizePx: Float,
        block: () -> Unit,
    ) {
        if (text.isBlank()) {
            block()
            return
        }
        val saved = paint.textSize
        var size = maxSizePx.coerceAtMost(saved)
        paint.textSize = size
        while (size > minSizePx && paint.measureText(text) > maxWidth) {
            size -= 0.5f * dp
            paint.textSize = size
        }
        try {
            block()
        } finally {
            paint.textSize = saved
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty()) return text
        if (paint.measureText(text) <= maxWidth) return text
        val ell = "…"
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text.substring(0, mid) + ell) <= maxWidth) lo = mid
            else hi = mid - 1
        }
        return if (lo <= 0) ell else text.take(lo) + ell
    }

    private fun baselineForCenterY(centerY: Float, paint: Paint): Float {
        val fm = paint.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawCenteredLine(
        canvas: Canvas,
        text: String,
        cx: Float,
        centerY: Float,
        paint: Paint,
        maxW: Float,
        fitToWidth: Boolean = false,
        minFitSizePx: Float = 9f * dp,
    ) {
        if (text.isBlank()) return
        if (fitToWidth) {
            withFittedTextSize(paint, text, maxW, minFitSizePx, paint.textSize) {
                val line = ellipsize(text, paint, maxW)
                canvas.drawText(line, cx, baselineForCenterY(centerY, paint), paint)
            }
        } else {
            val line = ellipsize(text, paint, maxW)
            canvas.drawText(line, cx, baselineForCenterY(centerY, paint), paint)
        }
    }

    /** Grey pill with person icon + seat count (floor-plan editor / dine-in picker). */
    private fun drawSeatCountBadge(
        canvas: Canvas,
        inner: RectF,
        cx: Float,
        centerY: Float,
        maxW: Float,
    ) {
        if (seatCount <= 0) return
        val countText = seatCount.toString()
        val padH = 7f * dp
        val padV = 4f * dp
        val iconSize = 11f * dp
        val iconGap = 3f * dp
        val textW = seatBadgeTextPaint.measureText(countText)
        val contentW = iconSize + iconGap + textW
        var badgeW = padH * 2f + contentW
        badgeW = badgeW.coerceAtMost(maxW.coerceAtMost(inner.width()))
        val badgeH = seatBadgeBlockHeight()
        val left = (cx - badgeW / 2f).coerceIn(inner.left, inner.right - badgeW)
        val top = (centerY - badgeH / 2f).coerceIn(inner.top, inner.bottom - badgeH)
        val rect = RectF(left, top, left + badgeW, top + badgeH)
        val corner = badgeH / 2f
        canvas.drawRoundRect(rect, corner, corner, seatBadgeFillPaint)

        val contentLeft = rect.centerX() - contentW / 2f
        val iconLeft = contentLeft
        val iconTop = rect.centerY() - iconSize / 2f
        val icon = seatPersonIcon ?: ContextCompat.getDrawable(context, R.drawable.ic_table_person)?.also {
            seatPersonIcon = it
        }
        icon?.let { drawable ->
            val tinted = DrawableCompat.wrap(drawable.mutate())
            DrawableCompat.setTint(tinted, seatBadgeIconTint)
            tinted.setBounds(
                iconLeft.toInt(),
                iconTop.toInt(),
                (iconLeft + iconSize).toInt(),
                (iconTop + iconSize).toInt(),
            )
            tinted.draw(canvas)
        }
        val textX = contentLeft + iconSize + iconGap
        canvas.drawText(
            countText,
            textX,
            baselineForCenterY(rect.centerY(), seatBadgeTextPaint),
            seatBadgeTextPaint,
        )
    }

    private fun pillColors(pill: StatusPill): Pair<Int, Int> {
        return when (pill) {
            StatusPill.WAITING -> 0x40E65100.toInt() to 0xFFE65100.toInt()
            StatusPill.RESERVED -> 0x40C62828.toInt() to 0xFFC62828.toInt()
            StatusPill.OCCUPIED -> 0x405D4037.toInt() to 0xFF5D4037.toInt()
            StatusPill.OPEN_ORDER -> 0x401E88E5.toInt() to 0xFF1565C0.toInt()
            StatusPill.NONE -> 0x22000000.toInt() to 0xFF424242.toInt()
        }
    }

    private fun drawStatusPill(
        canvas: Canvas,
        inner: RectF,
        cx: Float,
        centerY: Float,
        label: String,
        pill: StatusPill,
        maxPillWidth: Float,
    ) {
        if (label.isBlank() || pill == StatusPill.NONE) return
        val (bg, fg) = pillColors(pill)
        pillFillPaint.color = bg
        statusLabelPaint.color = fg
        val fm = statusLabelPaint.fontMetrics
        val innerPadH = 10f * dp
        val innerPadV = 5f * dp
        val text = ellipsize(label, statusLabelPaint, (maxPillWidth - 2f * innerPadH).coerceAtLeast(8f * dp))
        var tw = statusLabelPaint.measureText(text) + 2f * innerPadH
        tw = tw.coerceAtMost(maxPillWidth.coerceAtMost(inner.width()))
        val th = (fm.descent - fm.ascent) + 2f * innerPadV
        val left = (cx - tw / 2f).coerceIn(inner.left, inner.right - tw)
        val top = (centerY - th / 2f).coerceIn(inner.top, inner.bottom - th)
        val rect = RectF(left, top, left + tw, top + th)
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, pillFillPaint)
        canvas.drawText(text, rect.centerX(), baselineForCenterY(rect.centerY(), statusLabelPaint), statusLabelPaint)
    }

    private fun drawTextInCard(canvas: Canvas, cardRect: RectF) {
        val inner = RectF(
            cardRect.left + contentPadding,
            cardRect.top + contentPadding,
            cardRect.right - contentPadding,
            cardRect.bottom - contentPadding,
        )
        val maxW = (inner.width()).coerceAtLeast(24f * dp)
        val cx = inner.centerX()

        canvas.save()
        canvas.clipRect(
            cardRect.left + 1f,
            cardRect.top + 1f,
            cardRect.right - 1f,
            cardRect.bottom - 1f,
        )

        if (usesStructuredDetail()) {
            val nameH = lineBlockHeight(namePaint, tableName)
            val seatsH = if (showSeatsRow()) seatBadgeBlockHeight() else 0f
            val partyH = lineBlockHeight(partyPaint, detailPartyOf)
            val pillH = if (detailStatusLabel.isNotBlank() && statusPill != StatusPill.NONE) {
                val fm = statusLabelPaint.fontMetrics
                (fm.descent - fm.ascent) + 10f * dp
            } else 0f

            var block = nameH
            if (seatsH > 0f) block += detailLineGap + seatsH
            if (detailPartyOf.isNotBlank()) block += detailLineGap + partyH
            if (pillH > 0f) block += detailLineGap + pillH

            val innerH = inner.height()
            val yOffset = if (block <= innerH) (innerH - block) / 2f else 0f
            var y = inner.top + yOffset

            drawCenteredLine(canvas, tableName, cx, y + nameH / 2f, namePaint, maxW, fitToWidth = true)
            y += nameH
            if (seatsH > 0f) {
                y += detailLineGap
                drawSeatCountBadge(canvas, inner, cx, y + seatsH / 2f, maxW)
                y += seatsH
            }
            if (detailPartyOf.isNotBlank()) {
                y += detailLineGap
                drawCenteredLine(canvas, detailPartyOf, cx, y + partyH / 2f, partyPaint, maxW)
                y += partyH
            }
            if (detailStatusLabel.isNotBlank() && statusPill != StatusPill.NONE) {
                y += detailLineGap
                drawStatusPill(canvas, inner, cx, y + pillH / 2f, detailStatusLabel, statusPill, maxW)
            }
            canvas.restore()
            return
        }

        if (guestInfo.isNotBlank()) {
            val nameH = lineBlockHeight(namePaint, tableName)
            val seatsH = if (showSeatsRow()) seatBadgeBlockHeight() else 0f
            val pill = when {
                guestInfo.contains("Reserved", ignoreCase = true) -> StatusPill.RESERVED
                guestInfo.contains("Occupied", ignoreCase = true) -> StatusPill.OCCUPIED
                else -> StatusPill.NONE
            }
            val thirdH = if (pill != StatusPill.NONE) {
                lineBlockHeight(statusLabelPaint, guestInfo) + 10f * dp
            } else {
                lineBlockHeight(partyPaint, guestInfo)
            }
            var block = nameH
            if (seatsH > 0f) block += detailLineGap + seatsH
            block += detailLineGap + thirdH
            val innerH = inner.height()
            val yOffset = if (block <= innerH) (innerH - block) / 2f else 0f
            var y = inner.top + yOffset
            drawCenteredLine(canvas, tableName, cx, y + nameH / 2f, namePaint, maxW, fitToWidth = true)
            y += nameH
            if (seatsH > 0f) {
                y += detailLineGap
                drawSeatCountBadge(canvas, inner, cx, y + seatsH / 2f, maxW)
                y += seatsH
            }
            y += detailLineGap
            if (pill != StatusPill.NONE) {
                drawStatusPill(canvas, inner, cx, y + thirdH / 2f, guestInfo, pill, maxW)
            } else {
                drawCenteredLine(canvas, guestInfo, cx, y + thirdH / 2f, partyPaint, maxW)
            }
            canvas.restore()
            return
        }

        val nameH = lineBlockHeight(namePaint, tableName)
        val seatsH = if (showSeatsRow()) seatBadgeBlockHeight() else 0f
        val pillH = if (isWaitingForOrder) {
            lineBlockHeight(statusLabelPaint, "Waiting for order") + 10f * dp
        } else 0f
        var block = nameH
        if (seatsH > 0f) block += detailLineGap + seatsH
        if (pillH > 0f) block += detailLineGap + pillH
        val innerH = inner.height()
        val yOffset = if (block <= innerH) (innerH - block) / 2f else 0f
        var y = inner.top + yOffset
        drawCenteredLine(canvas, tableName, cx, y + nameH / 2f, namePaint, maxW, fitToWidth = true)
        y += nameH
        if (seatsH > 0f) {
            y += detailLineGap
            drawSeatCountBadge(canvas, inner, cx, y + seatsH / 2f, maxW)
            y += seatsH
        }
        if (isWaitingForOrder && pillH > 0f) {
            y += detailLineGap
            drawStatusPill(canvas, inner, cx, y + pillH / 2f, "Waiting for order", StatusPill.WAITING, maxW)
        }
        canvas.restore()
    }

    private fun applyStateColors() {
        when {
            isOccupied && isWaitingForOrder -> {
                tablePaint.color = 0x44B71C1C.toInt()
                tableBorderPaint.color = 0xFFB71C1C.toInt()
                boothCushionPaint.color = 0xFFB71C1C.toInt()
            }
            isOccupied -> {
                tablePaint.color = 0x44EF9A9A.toInt()
                tableBorderPaint.color = 0xFFEF9A9A.toInt()
                boothCushionPaint.color = 0xFFEF9A9A.toInt()
            }
            isReserved -> {
                tablePaint.color = 0x44F9A825.toInt()
                tableBorderPaint.color = 0xFFF9A825.toInt()
                boothCushionPaint.color = 0xFFF9A825.toInt()
            }
            else -> {
                tablePaint.color = 0xFFFFFFFF.toInt()
                tableBorderPaint.color = 0xFF5D4037.toInt()
                boothCushionPaint.color = 0xFF8D6E63.toInt()
            }
        }
    }

    companion object {
        /** Width/height in dp — keep in sync with [onMeasure]. */
        private fun sizeDpForShape(shape: Shape): Pair<Float, Float> = when (shape) {
            Shape.ROUND, Shape.SQUARE -> Pair(108f, 112f)
            Shape.RECTANGLE -> Pair(160f, 104f)
            Shape.BOOTH -> Pair(128f, 104f)
        }

        fun defaultMeasuredWidthPx(context: Context, shape: Shape): Int {
            val d = context.resources.displayMetrics.density
            return (sizeDpForShape(shape).first * d).toInt()
        }

        fun defaultMeasuredHeightPx(context: Context, shape: Shape): Int {
            val d = context.resources.displayMetrics.density
            return (sizeDpForShape(shape).second * d).toInt()
        }

        fun editorMeasuredWidthPx(context: Context, shape: Shape): Int {
            val d = context.resources.displayMetrics.density
            val minPx = (40f * d).toInt()
            return (defaultMeasuredWidthPx(context, shape) * TableLayoutMobileScale.EDITOR_TABLE_SIZE_SCALE).toInt()
                .coerceAtLeast(minPx)
        }

        fun editorMeasuredHeightPx(context: Context, shape: Shape): Int {
            val d = context.resources.displayMetrics.density
            val minPx = (42f * d).toInt()
            return (defaultMeasuredHeightPx(context, shape) * TableLayoutMobileScale.EDITOR_TABLE_SIZE_SCALE).toInt()
                .coerceAtLeast(minPx)
        }

        fun dineInMeasuredWidthPx(context: Context, shape: Shape): Int {
            val d = context.resources.displayMetrics.density
            val minPx = (44f * d).toInt()
            return (defaultMeasuredWidthPx(context, shape) * TableLayoutMobileScale.DINE_IN_TABLE_SIZE_SCALE).toInt()
                .coerceAtLeast(minPx)
        }

        fun dineInMeasuredHeightPx(context: Context, shape: Shape): Int {
            val d = context.resources.displayMetrics.density
            val minPx = (46f * d).toInt()
            return (defaultMeasuredHeightPx(context, shape) * TableLayoutMobileScale.DINE_IN_TABLE_SIZE_SCALE).toInt()
                .coerceAtLeast(minPx)
        }

        fun shapeFromString(value: String?): Shape {
            return when (value?.uppercase()) {
                "ROUND", "CIRCLE" -> Shape.ROUND
                "RECTANGLE" -> Shape.RECTANGLE
                "BOOTH" -> Shape.BOOTH
                else -> Shape.SQUARE
            }
        }

        fun shapeToString(shape: Shape): String = shape.name
    }
}
