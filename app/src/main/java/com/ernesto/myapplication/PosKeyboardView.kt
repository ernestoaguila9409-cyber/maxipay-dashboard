package com.ernesto.myapplication

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Full-width docked QWERTY keyboard with a numbers/symbols page.
 * Layout matches the POS mockup: white tray, top hairline, 8dp rounded keys,
 * staggered second row, gray specials, purple Enter.
 */
class PosKeyboardView private constructor(
    private val root: LinearLayout,
    private val keyRows: LinearLayout,
) {

    companion object {
        const val KEY_BACKSPACE = "\u0008"
        const val KEY_ENTER = "\n"
        const val KEY_SHIFT = "\u21E7"
        const val KEY_TOGGLE = "?123"
        const val KEY_TOGGLE_BACK = "ABC"
        const val KEY_SPACE = " "

        private val ROW1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW3 = listOf(KEY_SHIFT, "z", "x", "c", "v", "b", "n", "m", KEY_BACKSPACE)

        private val SYM_ROW1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val SYM_ROW2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")")
        private val SYM_ROW3 = listOf("!", "\"", "'", ":", ";", "/", "?", KEY_BACKSPACE)

        fun create(context: Context, onKeyPress: (String) -> Unit): PosKeyboardView {
            val density = context.resources.displayMetrics.density
            fun dp(v: Float) = (v * density).toInt()

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                elevation = 0f
                clipToPadding = false
                clipChildren = false
            }

            val topRule = View(context).apply {
                setBackgroundColor(0xFFE0E0E0.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1f),
                )
            }
            root.addView(topRule)

            val keyRows = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(dp(6f), dp(8f), dp(6f), dp(10f))
            }
            root.addView(keyRows)

            val kbd = PosKeyboardView(root, keyRows)
            kbd.onKeyPress = onKeyPress
            kbd.buildAlphaPage(context)
            return kbd
        }
    }

    var onKeyPress: (String) -> Unit = {}
    private var shifted = false
    private var capsLock = false
    private var showingSymbols = false

    val view: View get() = root

    private fun buildAlphaPage(context: Context) {
        showingSymbols = false
        keyRows.removeAllViews()
        addEqualKeyRow(context, ROW1, isAlpha = true)
        addStaggeredKeyRow(context, ROW2, isAlpha = true)
        addShiftRow(context)
        keyRows.addView(buildBottomBar(context, isAlpha = true))
    }

    private fun buildSymbolPage(context: Context) {
        showingSymbols = true
        keyRows.removeAllViews()
        addEqualKeyRow(context, SYM_ROW1, isAlpha = false)
        addEqualKeyRow(context, SYM_ROW2, isAlpha = false)
        addSymbolShiftRow(context)
        keyRows.addView(buildBottomBar(context, isAlpha = false))
    }

    private fun addEqualKeyRow(context: Context, keys: List<String>, isAlpha: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowLayoutParams(context)
        }
        for (key in keys) {
            row.addView(makeLetterKey(context, key, weight = 1f, isAlpha = isAlpha))
        }
        keyRows.addView(row)
    }

    /** Second row: half-key stagger via weighted side spacers (standard QWERTY). */
    private fun addStaggeredKeyRow(context: Context, keys: List<String>, isAlpha: Boolean) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val keyH = dp(52f)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowLayoutParams(context)
        }
        row.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, keyH, 0.5f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
        )
        for (key in keys) {
            row.addView(makeLetterKey(context, key, weight = 1f, isAlpha = isAlpha))
        }
        row.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, keyH, 0.5f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
        )
        keyRows.addView(row)
    }

    private fun addShiftRow(context: Context) {
        val weights = listOf(1.5f) + List(7) { 1f } + listOf(1.5f)
        val keys = ROW3
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowLayoutParams(context)
        }
        for (i in keys.indices) {
            val key = keys[i]
            val w = weights[i]
            if (key == KEY_SHIFT || key == KEY_BACKSPACE) {
                row.addView(makeSpecialKey(context, key, w))
            } else {
                row.addView(makeLetterKey(context, key, weight = w, isAlpha = true))
            }
        }
        keyRows.addView(row)
    }

    private fun addSymbolShiftRow(context: Context) {
        val keys = SYM_ROW3
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowLayoutParams(context)
        }
        for (key in keys) {
            val w = if (key == KEY_BACKSPACE) 1.5f else 1f
            if (key == KEY_BACKSPACE) {
                row.addView(makeSpecialKey(context, key, w))
            } else {
                row.addView(makeLetterKey(context, key, weight = w, isAlpha = false))
            }
        }
        keyRows.addView(row)
    }

    private fun rowLayoutParams(context: Context): LinearLayout.LayoutParams {
        val density = context.resources.displayMetrics.density
        val marginBottom = (6 * density).toInt()
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = marginBottom }
    }

    private fun keyMargins(context: Context): LinearLayout.LayoutParams {
        val density = context.resources.displayMetrics.density
        val h = (2 * density).toInt()
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = h
            marginEnd = h
        }
    }

    private fun makeLetterKey(context: Context, key: String, weight: Float, isAlpha: Boolean): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val label = if (isAlpha && (shifted || capsLock)) key.uppercase() else key
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(0xFF333333.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_pos_key)?.mutate()
            minHeight = dp(52f)
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            isClickable = true
            isFocusable = false
            layoutParams = keyMargins(context).apply { this.width = 0; this.weight = weight }
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val ch = if (isAlpha && (shifted || capsLock)) key.uppercase() else key
                onKeyPress(ch)
                if (isAlpha && shifted && !capsLock) {
                    shifted = false
                    buildAlphaPage(context)
                }
            }
        }
    }

    private fun makeSpecialKey(context: Context, key: String, weight: Float): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val label = when (key) {
            KEY_SHIFT -> "\u21E7"
            KEY_BACKSPACE -> "\u232B"
            else -> key
        }
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFF333333.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_pos_key_special)?.mutate()
            minHeight = dp(52f)
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            isClickable = true
            isFocusable = false
            layoutParams = keyMargins(context).apply { this.width = 0; this.weight = weight }
            if (key == KEY_SHIFT && (shifted || capsLock)) {
                setTextColor(0xFF5E4085.toInt())
            }
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handleSpecialKey(context, key)
            }
            if (key == KEY_SHIFT) {
                setOnLongClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    capsLock = !capsLock
                    shifted = capsLock
                    if (showingSymbols) buildSymbolPage(context) else buildAlphaPage(context)
                    true
                }
            }
        }
    }

    private fun handleSpecialKey(context: Context, key: String) {
        when (key) {
            KEY_SHIFT -> {
                if (!capsLock) shifted = !shifted
                if (showingSymbols) buildSymbolPage(context) else buildAlphaPage(context)
            }
            KEY_BACKSPACE -> onKeyPress(KEY_BACKSPACE)
        }
    }

    private fun buildBottomBar(context: Context, isAlpha: Boolean): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        fun makeKey(label: String, bgRes: Int, weight: Float, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFF333333.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, bgRes)?.mutate()
                minHeight = dp(52f)
                setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
                isClickable = true
                isFocusable = false
                layoutParams = keyMargins(context).apply { this.width = 0; this.weight = weight }
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            }
        }

        val toggleLabel = if (isAlpha) KEY_TOGGLE else KEY_TOGGLE_BACK
        row.addView(makeKey(toggleLabel, R.drawable.bg_pos_key_special, 1.35f) {
            if (isAlpha) buildSymbolPage(context) else buildAlphaPage(context)
        })

        row.addView(makeKey(",", R.drawable.bg_pos_key, 0.85f) {
            onKeyPress(",")
        })

        row.addView(makeKey(KEY_SPACE, R.drawable.bg_pos_key, 5.2f) {
            onKeyPress(KEY_SPACE)
        })

        row.addView(makeKey(".", R.drawable.bg_pos_key, 0.85f) {
            onKeyPress(".")
        })

        row.addView(makeKey("\u23CE", R.drawable.bg_pos_key_enter, 1.35f) {
            onKeyPress(KEY_ENTER)
        })

        return row
    }
}
