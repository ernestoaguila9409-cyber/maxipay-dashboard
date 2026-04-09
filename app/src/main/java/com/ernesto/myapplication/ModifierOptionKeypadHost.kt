package com.ernesto.myapplication

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.textfield.TextInputEditText

/**
 * Wraps [ModifierOptionFormHelper.InflatedModifierOptionForm] with a bottom POS keyboard (QWERTY + decimal)
 * and blocks the system IME on the option fields.
 */
object ModifierOptionKeypadHost {

    fun wrap(context: Context, form: ModifierOptionFormHelper.InflatedModifierOptionForm): View {
        prepareFields(context, form)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val scroll = form.scrollView
        (scroll as? NestedScrollView)?.clipToPadding = false
        val frame = FrameLayout(context)

        val keyboardShell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_pos_keyboard_panel)
            ViewCompat.setElevation(this, resources.getDimension(R.dimen.pos_keyboard_elevation))
        }

        fun dp(v: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            context.resources.displayMetrics,
        ).toInt()

        var keyboardExpanded = true

        val toggleKeyboard = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setText(R.string.pos_keyboard_hide)
            setTextColor(context.getColor(R.color.brand_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = typedArray.getDrawable(0)
            typedArray.recycle()
            isClickable = true
            isFocusable = false
        }

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                toggleKeyboard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        keyboardShell.addView(toolbar)

        val switchHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val alphaPanel = PosQwertyKeypad.build(
            context,
            PosQwertyKeypad.Variant.MODIFIER_OPTION,
            onInsert = { token -> ReceiptEmailKeypadDialog.insertAtCaret(form.editName, token) },
            onEnter = {
                if (form.tilPrice.visibility == View.VISIBLE) {
                    form.editPrice.requestFocus()
                } else {
                    form.editName.clearFocus()
                }
            },
        )

        val decimalPanel = buildDecimalKeypad(context, form.editPrice) {
            form.editPrice.clearFocus()
            imm.hideSoftInputFromWindow(form.editPrice.windowToken, 0)
        }

        switchHost.addView(
            alphaPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        switchHost.addView(
            decimalPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        keyboardShell.addView(switchHost)

        fun showAlpha() {
            alphaPanel.visibility = View.VISIBLE
            decimalPanel.visibility = View.GONE
        }

        fun showDecimal() {
            alphaPanel.visibility = View.GONE
            decimalPanel.visibility = View.VISIBLE
        }

        fun syncPanelToFocus() {
            when {
                form.editPrice.hasFocus() && form.tilPrice.visibility == View.VISIBLE -> showDecimal()
                else -> showAlpha()
            }
        }

        fun applyScrollBottomPad() {
            val h = keyboardShell.height
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, h)
        }

        fun updateToggleLabel() {
            toggleKeyboard.setText(
                if (keyboardExpanded) R.string.pos_keyboard_hide else R.string.pos_keyboard_show,
            )
        }

        fun expandKeyboard() {
            if (keyboardExpanded) return
            keyboardExpanded = true
            switchHost.visibility = View.VISIBLE
            updateToggleLabel()
            keyboardShell.post {
                syncPanelToFocus()
                applyScrollBottomPad()
            }
        }

        fun collapseKeyboard() {
            if (!keyboardExpanded) return
            keyboardExpanded = false
            switchHost.visibility = View.GONE
            form.editName.clearFocus()
            form.editPrice.clearFocus()
            imm.hideSoftInputFromWindow(keyboardShell.windowToken, 0)
            updateToggleLabel()
            keyboardShell.post { applyScrollBottomPad() }
        }

        toggleKeyboard.setOnClickListener {
            if (keyboardExpanded) {
                collapseKeyboard()
            } else {
                expandKeyboard()
                form.editName.requestFocus()
                keyboardShell.post {
                    syncPanelToFocus()
                    applyScrollBottomPad()
                }
            }
        }

        form.editName.setOnClickListener { v ->
            if (!keyboardExpanded) expandKeyboard()
            v.requestFocus()
        }
        form.editPrice.setOnClickListener { v ->
            if (!keyboardExpanded) expandKeyboard()
            v.requestFocus()
        }

        form.editName.setOnFocusChangeListener { v, hasFocus ->
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            if (hasFocus) {
                if (!keyboardExpanded) expandKeyboard()
                showAlpha()
            }
            v.post {
                syncPanelToFocus()
                applyScrollBottomPad()
            }
        }
        form.editPrice.setOnFocusChangeListener { v, hasFocus ->
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            if (hasFocus) {
                if (!keyboardExpanded) expandKeyboard()
                showDecimal()
            }
            v.post {
                syncPanelToFocus()
                applyScrollBottomPad()
            }
        }

        keyboardShell.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyScrollBottomPad()
        }

        frame.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addView(
            keyboardShell,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        showAlpha()
        frame.post {
            form.editName.requestFocus()
            syncPanelToFocus()
            applyScrollBottomPad()
        }

        frame.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val kb = Rect()
            keyboardShell.getGlobalVisibleRect(kb)
            if (kb.contains(x, y)) return@setOnTouchListener false
            for (et in listOf(form.editName, form.editPrice)) {
                val r = Rect()
                et.getGlobalVisibleRect(r)
                if (r.contains(x, y)) return@setOnTouchListener false
            }
            form.editName.clearFocus()
            form.editPrice.clearFocus()
            imm.hideSoftInputFromWindow(frame.windowToken, 0)
            false
        }

        return frame
    }

    private fun prepareFields(context: Context, form: ModifierOptionFormHelper.InflatedModifierOptionForm) {
        fun prepText(et: TextInputEditText, inputType: Int) {
            et.inputType = inputType
            et.showSoftInputOnFocus = false
            et.isLongClickable = true
        }
        prepText(
            form.editName,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        prepText(form.editPrice, InputType.TYPE_NULL)
        if (form.tilPrice.visibility != View.VISIBLE) {
            form.editPrice.isFocusable = false
            form.editPrice.isFocusableInTouchMode = false
        }
    }

    private fun applyDecimalKey(edit: EditText, key: String) {
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
                    s == "0" && key != "." -> key
                    else -> s + key
                }
            }
        }
        edit.setText(s)
        edit.setSelection(s.length)
    }

    private fun buildDecimalKeypad(
        context: Context,
        priceField: TextInputEditText,
        onDone: () -> Unit,
    ): LinearLayout {
        val res = context.resources
        val minH = res.getDimensionPixelSize(R.dimen.pos_key_min_height)
        val mH = res.getDimensionPixelSize(R.dimen.pos_key_margin_h)
        val mV = res.getDimensionPixelSize(R.dimen.pos_key_margin_v)
        val pad = res.getDimensionPixelSize(R.dimen.pos_keyboard_panel_padding)
        val labelSp = res.getDimension(R.dimen.pos_key_label_sp) / res.displayMetrics.scaledDensity

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val grid = GridLayout(context).apply {
            columnCount = 3
        }

        fun addKey(label: String, colSpan: Int = 1): TextView {
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelSp)
                setTextColor(context.getColor(R.color.pos_key_text_primary))
                background = context.getDrawable(R.drawable.bg_pos_key_default)
                minimumHeight = minH
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, colSpan, 1f)
                    setMargins(mH, mV, mH, mV)
                }
                if (label == "⌫") {
                    setBackgroundResource(R.drawable.bg_pos_key_special)
                }
                setOnClickListener { applyDecimalKey(priceField, label) }
            }
            grid.addView(tv)
            return tv
        }

        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")) {
            addKey(k)
        }
        addKey(".")
        addKey("0")
        addKey("⌫")

        col.addView(grid)

        val done = TextView(context).apply {
            text = context.getString(R.string.pos_keypad_done)
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelSp)
            setTextColor(context.getColor(R.color.pos_key_text_primary))
            setBackgroundResource(R.drawable.bg_pos_key_enter)
            minimumHeight = minH
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(mH, mV, mH, mV)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    500,
                    false,
                )
            }
            setOnClickListener { onDone() }
        }
        col.addView(done)

        return col
    }
}
