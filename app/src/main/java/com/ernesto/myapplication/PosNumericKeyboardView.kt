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
 * Full-width numeric keypad for price entry. Matches [PosKeyboardView] styling but uses a
 * **calculator-style layout**: 3×4 digit block + a dedicated action column (no empty spacer cells).
 */
class PosNumericKeyboardView private constructor(
    val view: View,
) {

    companion object {
        fun create(context: Context, onKeyPress: (String) -> Unit): PosNumericKeyboardView {
            val density = context.resources.displayMetrics.density
            fun dp(v: Float) = (v * density).toInt()

            val keyMinH = dp(52f)
            val rowGap = dp(6f)
            val gutter = dp(4f)
            val leftBlockH = 4 * keyMinH + 3 * rowGap
            val actionSegmentH = (leftBlockH - rowGap) / 2

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
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

            val outer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(dp(8f), dp(10f), dp(8f), dp(12f))
            }
            root.addView(outer)

            fun keyLp(weight: Float): LinearLayout.LayoutParams {
                return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                    marginStart = gutter
                    marginEnd = gutter
                }
            }

            fun makeDigit(label: String, weight: Float = 1f): TextView {
                return TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(0xFF1A1A1A.toInt())
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    background = ContextCompat.getDrawable(context, R.drawable.bg_pos_key)?.mutate()
                    minHeight = keyMinH
                    setPadding(dp(6f), dp(12f), dp(6f), dp(12f))
                    isClickable = true
                    isFocusable = false
                    layoutParams = keyLp(weight)
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onKeyPress(label)
                    }
                }
            }

            fun makeSpecial(
                label: String,
                weight: Float,
                bg: Int,
                textSp: Float = 18f,
                textColor: Int = 0xFF333333.toInt(),
            ): TextView {
                return TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                    setTextColor(textColor)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    background = ContextCompat.getDrawable(context, bg)?.mutate()
                    minHeight = keyMinH
                    setPadding(dp(6f), dp(12f), dp(6f), dp(12f))
                    isClickable = true
                    isFocusable = false
                    layoutParams = keyLp(weight)
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        when (label) {
                            "\u232B" -> onKeyPress(PosKeyboardView.KEY_BACKSPACE)
                            else -> onKeyPress(label)
                        }
                    }
                }
            }

            fun makeEnter(): TextView {
                return TextView(context).apply {
                    text = "\u23CE"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    background = ContextCompat.getDrawable(context, R.drawable.bg_pos_key_enter)?.mutate()
                    setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
                    isClickable = true
                    isFocusable = false
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onKeyPress(PosKeyboardView.KEY_ENTER)
                    }
                }
            }

            fun digitRow(vararg keys: String): LinearLayout {
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = rowGap }
                    for (k in keys) {
                        addView(makeDigit(k))
                    }
                }
            }

            // Left: 3×4 digit pad (no fourth-column holes)
            val mainPad = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4.2f)
            }
            mainPad.addView(digitRow("1", "2", "3"))
            mainPad.addView(digitRow("4", "5", "6"))
            mainPad.addView(digitRow("7", "8", "9"))
            mainPad.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    addView(makeDigit(".", weight = 1f))
                    addView(makeDigit("0", weight = 1.25f))
                    addView(makeDigit(",", weight = 1f))
                },
            )

            // Right: tall backspace + tall enter (aligned to 4 rows on the left)
            val actionCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            actionCol.addView(
                makeSpecial("\u232B", 1f, R.drawable.bg_pos_key_special).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        actionSegmentH,
                    ).apply {
                        marginStart = gutter
                        marginEnd = gutter
                        bottomMargin = rowGap
                    }
                },
            )
            actionCol.addView(
                makeEnter().apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        actionSegmentH,
                    ).apply {
                        marginStart = gutter
                        marginEnd = gutter
                    }
                },
            )

            outer.addView(mainPad)
            outer.addView(actionCol)

            return PosNumericKeyboardView(root)
        }
    }
}
