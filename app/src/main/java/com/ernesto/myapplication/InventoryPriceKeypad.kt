package com.ernesto.myapplication

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.text.InputType
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.R as MTR
import com.google.android.material.button.MaterialButton

/**
 * Wraps inventory edit-item form content in a column with a scroll area and a slide-in
 * decimal keypad for price [EditText]s only. Disables the system IME on those fields.
 */
object InventoryPriceKeypad {

    fun wrapFormWithDecimalKeypad(
        context: Context,
        formContent: LinearLayout,
        priceEditTexts: Collection<EditText>,
    ): View {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val priceList = priceEditTexts.toList()

        fun dp(v: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            context.resources.displayMetrics
        ).toInt()

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isScrollbarFadingEnabled = false
            addView(
                formContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val keypadOuter = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ECEFF4"))
            visibility = View.GONE
        }

        val topRule = View(context).apply {
            setBackgroundColor(Color.parseColor("#D1D5DB"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1f)
            )
        }
        keypadOuter.addView(topRule)

        var activePrice: EditText? = null

        fun hideKeypad() {
            if (keypadOuter.visibility != View.VISIBLE) return
            val h = keypadOuter.height
            val slide = if (h > 0) h.toFloat() else 360f
            keypadOuter.animate()
                .translationY(slide)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    keypadOuter.visibility = View.GONE
                    keypadOuter.translationY = 0f
                    keypadOuter.alpha = 1f
                }
                .start()
        }

        fun showKeypad() {
            if (keypadOuter.visibility == View.VISIBLE) {
                keypadOuter.translationY = 0f
                keypadOuter.alpha = 1f
                return
            }
            keypadOuter.visibility = View.INVISIBLE
            keypadOuter.post {
                val parentW = (keypadOuter.parent as? View)?.width ?: keypadOuter.width
                if (parentW <= 0) {
                    keypadOuter.visibility = View.VISIBLE
                    keypadOuter.alpha = 1f
                    return@post
                }
                keypadOuter.measure(
                    MeasureSpec.makeMeasureSpec(parentW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val measuredH = keypadOuter.measuredHeight.coerceAtLeast(1)
                keypadOuter.translationY = measuredH.toFloat()
                keypadOuter.alpha = 0f
                keypadOuter.visibility = View.VISIBLE
                keypadOuter.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        fun applyKeyTo(edit: EditText, key: String) {
            var s = edit.text?.toString() ?: ""
            when (key) {
                "⌫" -> if (s.isNotEmpty()) s = s.dropLast(1)
                "." -> when {
                    s.contains(".") -> return
                    s.isEmpty() -> s = "0."
                    else -> s += "."
                }
                else -> {
                    if (key.length != 1 || !key[0].isDigit()) return
                    val di = s.indexOf('.')
                    if (di >= 0 && s.length - di - 1 >= 2) return
                    s = when {
                        s.isEmpty() -> key
                        s == "0" -> key
                        else -> s + key
                    }
                }
            }
            edit.setText(s)
            edit.setSelection(s.length)
        }

        fun makeKeyButton(label: String, onPress: () -> Unit): MaterialButton {
            return MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                minimumHeight = dp(46f)
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                }
                setOnClickListener { onPress() }
            }
        }

        val grid = GridLayout(context).apply {
            columnCount = 3
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }

        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")) {
            grid.addView(makeKeyButton(k) {
                activePrice?.let { applyKeyTo(it, k) }
            })
        }
        grid.addView(makeKeyButton(".") {
            activePrice?.let { applyKeyTo(it, ".") }
        })
        grid.addView(makeKeyButton("0") {
            activePrice?.let { applyKeyTo(it, "0") }
        })
        grid.addView(makeKeyButton("⌫") {
            activePrice?.let { applyKeyTo(it, "⌫") }
        })

        keypadOuter.addView(grid)

        val doneBtn = MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12f), 0, dp(12f), dp(12f))
            }
            setOnClickListener {
                priceList.forEach { it.clearFocus() }
                imm.hideSoftInputFromWindow(it.windowToken, 0)
                hideKeypad()
            }
        }
        keypadOuter.addView(doneBtn)

        for (et in priceList) {
            et.inputType = InputType.TYPE_NULL
            et.showSoftInputOnFocus = false
            et.isLongClickable = false
            et.setTextIsSelectable(false)
            et.setOnClickListener { v -> v.requestFocus() }
            et.setOnFocusChangeListener { v, hasFocus ->
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                if (hasFocus) {
                    activePrice = et
                    showKeypad()
                } else {
                    v.post {
                        if (!priceList.any { it.hasFocus() }) {
                            activePrice = null
                            hideKeypad()
                        }
                    }
                }
            }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(
                keypadOuter,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        column.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            if (keypadOuter.visibility != View.VISIBLE) return@setOnTouchListener false
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val keypadRect = Rect()
            keypadOuter.getGlobalVisibleRect(keypadRect)
            if (keypadRect.contains(x, y)) return@setOnTouchListener false
            for (et in priceList) {
                val r = Rect()
                et.getGlobalVisibleRect(r)
                if (r.contains(x, y)) return@setOnTouchListener false
            }
            priceList.forEach { it.clearFocus() }
            imm.hideSoftInputFromWindow(column.windowToken, 0)
            hideKeypad()
            false
        }

        return column
    }

    /**
     * Clears price focus so the keypad hides when the user opens another control (e.g. spinner).
     */
    fun clearPriceFocusForSpinner(priceEditTexts: Collection<EditText>) {
        priceEditTexts.forEach { it.clearFocus() }
    }
}
