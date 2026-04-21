package com.ernesto.myapplication

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * POS-style decimal money keypad (same visual language as [MenuActivity]'s embedded keys:
 * [R.drawable.bg_keyboard_key], etc.). Used where the system IME must stay hidden.
 */
class PosMoneyAmountKeypad(
    private val context: Context,
    private val container: LinearLayout,
    private val onBufferChanged: (raw: String) -> Unit,
) {
    private val buf = StringBuilder()

    fun currentBuffer(): String = buf.toString()

    fun clear() {
        buf.clear()
        notifyChanged()
    }

    /** Sets cash entry to exactly [amountCents] / 100 (two decimal places), e.g. tap EXACT on cash screen. */
    fun applyExactAmountCents(amountCents: Long) {
        buf.clear()
        val text = String.format(Locale.US, "%.2f", amountCents / 100.0)
        buf.append(text)
        notifyChanged()
    }

    fun attach() {
        container.removeAllViews()
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫"),
        )
        for (chars in rows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(5) }
            }
            for (ch in chars) {
                val bg = when (ch) {
                    "⌫" -> R.drawable.bg_keyboard_key_dark
                    else -> R.drawable.bg_keyboard_key
                }
                rowLayout.addView(makeKey(ch, bg))
            }
            container.addView(rowLayout)
        }
    }

    private fun makeKey(value: String, bg: Int): TextView {
        val keyHeight = dp(48)
        return TextView(context).apply {
            text = value
            textSize = if (value == "⌫") 18f else 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1C1B1F"))
            setBackgroundResource(bg)
            isClickable = true
            isFocusable = true
            minHeight = keyHeight
            layoutParams = LinearLayout.LayoutParams(0, keyHeight, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener { onKeyPress(value) }
        }
    }

    private fun onKeyPress(key: String) {
        when (key) {
            "⌫" -> {
                if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            }
            "." -> {
                if (buf.contains('.')) return
                if (buf.isEmpty()) buf.append('0')
                buf.append('.')
            }
            else -> {
                if (!key[0].isDigit()) return
                val dotIdx = buf.indexOf('.')
                if (dotIdx >= 0) {
                    val fracLen = buf.length - dotIdx - 1
                    if (fracLen >= 2) return
                }
                if (buf.length == 1 && buf[0] == '0' && dotIdx < 0 && key != "0") {
                    buf.clear()
                }
                if (buf.toString() == "0" && key == "0" && dotIdx < 0) return
                buf.append(key)
            }
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        onBufferChanged(buf.toString())
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
