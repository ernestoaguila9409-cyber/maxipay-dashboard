package com.volt.maximobile

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Builds the full "Add / Edit Option" bottom-sheet-style layout:
 * drag handle → title → form → Cancel / Save → keyboard.
 */
object ModifierOptionKeypadHost {

    data class WrappedForm(
        val root: View,
        val btnSave: TextView,
        val btnCancel: TextView,
    )

    fun configureDialogWindow(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val metrics = dialog.context.resources.displayMetrics
        val density = metrics.density
        val sideMarginPx = (16 * density).toInt()
        val targetWidthPx = (560 * density).toInt()
        val width = targetWidthPx.coerceAtMost((metrics.widthPixels - sideMarginPx).coerceAtLeast(1))
        val height = (metrics.heightPixels * 0.95f).toInt().coerceAtLeast((320 * density).toInt())
        window.setLayout(width, height)
        window.setGravity(Gravity.BOTTOM)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        try {
            val custom = dialog.findViewById<View>(android.R.id.custom)
            if (custom != null) {
                custom.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                val customPanel = custom.parent as? ViewGroup
                if (customPanel != null) {
                    val panelLp = customPanel.layoutParams
                    if (panelLp is LinearLayout.LayoutParams) {
                        panelLp.height = 0
                        panelLp.weight = 1f
                    } else {
                        panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    customPanel.requestLayout()
                }
            }
        } catch (_: Exception) { }
    }

    fun wrap(
        context: Context,
        form: ModifierOptionFormHelper.InflatedModifierOptionForm,
        title: String,
    ): WrappedForm {
        prepareFields(context, form)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val density = context.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = context.getDrawable(R.drawable.bg_edit_option_sheet)
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }

        // ── Drag handle ─────────────────────────────────────────
        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(5f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20f)
            }
            background = context.getDrawable(R.drawable.bg_drag_handle)
        }
        root.addView(handle)

        // ── Title ───────────────────────────────────────────────
        val titleTv = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(0xFF111827.toInt())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
            } else {
                setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(18f) }
        }
        root.addView(titleTv)

        // ── Form (scrollable) ───────────────────────────────────
        val scroll = form.scrollView
        scroll.isFillViewport = false
        scroll.clipToPadding = false

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.40f,
            ),
        )

        // ── Bottom buttons (above keyboard so the form + option name stay visible;
        // keyboard sits at the bottom of the sheet.) ─────────────────────
        val buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8f) }
        }

        val btnCancel = TextView(context).apply {
            text = "Cancel"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(context.getColor(R.color.brand_primary))
            background = context.getDrawable(R.drawable.bg_btn_cancel_outlined)
            layoutParams = LinearLayout.LayoutParams(dp(140f), dp(56f)).apply {
                marginEnd = dp(16f)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
            }
        }
        buttonBar.addView(btnCancel)

        val btnSave = TextView(context).apply {
            text = "Save"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(0xFFFFFFFF.toInt())
            background = context.getDrawable(R.drawable.bg_btn_save_filled)
            layoutParams = LinearLayout.LayoutParams(dp(140f), dp(56f))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
            }
        }
        buttonBar.addView(btnSave)
        root.addView(buttonBar)

        // ── Keyboard ────────────────────────────────────────────
        val keyboardShell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_pos_keyboard_panel)
            ViewCompat.setElevation(this, context.resources.getDimension(R.dimen.pos_keyboard_elevation))
        }

        var keyboardExpanded = true
        var suppressScrollIntoView = false

        val toggleKeyboard = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setText(R.string.pos_keyboard_hide)
            setTextColor(context.getColor(R.color.brand_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
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
            addView(toggleKeyboard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
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

        switchHost.addView(alphaPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        switchHost.addView(decimalPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        keyboardShell.addView(switchHost)

        root.addView(
            keyboardShell,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.60f,
            ),
        )

        // ── Keyboard logic ──────────────────────────────────────
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

        fun scrollFieldIntoView(field: View) {
            scroll.post {
                val target = Rect()
                field.getDrawingRect(target)
                scroll.requestChildRectangleOnScreen(field, target, true)
            }
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

            (scroll.layoutParams as LinearLayout.LayoutParams).weight = 0.40f
            (keyboardShell.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 0.60f
                height = 0
            }
            root.requestLayout()
            updateToggleLabel()
            root.post {
                syncPanelToFocus()
                scroll.scrollTo(0, 0)
            }
        }

        fun collapseKeyboard() {
            if (!keyboardExpanded) return
            keyboardExpanded = false
            switchHost.visibility = View.GONE
            form.editName.clearFocus()
            form.editPrice.clearFocus()
            imm.hideSoftInputFromWindow(keyboardShell.windowToken, 0)

            (scroll.layoutParams as LinearLayout.LayoutParams).weight = 1f
            (keyboardShell.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (keyboardShell.layoutParams as LinearLayout.LayoutParams).height =
                ViewGroup.LayoutParams.WRAP_CONTENT
            root.requestLayout()
            updateToggleLabel()
        }

        toggleKeyboard.setOnClickListener {
            if (keyboardExpanded) {
                collapseKeyboard()
            } else {
                suppressScrollIntoView = true
                expandKeyboard()
                form.editName.requestFocus()
                scroll.post {
                    syncPanelToFocus()
                    scroll.scrollTo(0, 0)
                    suppressScrollIntoView = false
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
                if (hasFocus && !suppressScrollIntoView) scrollFieldIntoView(form.tilName)
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
                if (hasFocus && !suppressScrollIntoView) scrollFieldIntoView(form.tilPrice)
            }
        }

        showAlpha()
        root.post {
            suppressScrollIntoView = true
            form.editName.requestFocus()
            syncPanelToFocus()
            scroll.scrollTo(0, 0)
            scroll.post { suppressScrollIntoView = false }
        }

        root.setOnTouchListener { _, event ->
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
            imm.hideSoftInputFromWindow(root.windowToken, 0)
            false
        }

        return WrappedForm(root, btnSave, btnCancel)
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

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

        val grid = GridLayout(context).apply { columnCount = 3 }

        fun addKey(label: String, colSpan: Int = 1): TextView {
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
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

        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")) addKey(k)
        addKey(".")
        addKey("0")
        addKey("⌫")

        col.addView(grid)

        val done = TextView(context).apply {
            text = context.getString(R.string.pos_keypad_done)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            setTextColor(context.getColor(R.color.pos_key_text_primary))
            setBackgroundResource(R.drawable.bg_pos_key_enter)
            minimumHeight = minH
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(mH, mV, mH, mV) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
            }
            setOnClickListener { onDone() }
        }
        col.addView(done)

        return col
    }
}
