package com.ernesto.myapplication

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat

/**
 * Bottom-anchored custom keyboard for the bar-seat-order dialog.
 * The keyboard slides up from the bottom like a system IME, overlaying
 * the content. Height is capped (lower in landscape so the form + actions stay usable).
 *
 * @see InventoryPriceKeypad — same UX contract for price fields.
 */
object BarSeatOrderKeypad {

    private enum class KeypadKind { ALPHA, PHONE }

    /**
     * Wraps [formContent] and [actionBar] in a [FrameLayout]:
     *
     *   FrameLayout (fill screen)
     *   ├── content  (scroll + action bar)  ← gets bottom padding when keyboard visible
     *   └── keyboard (layout_gravity=BOTTOM; shorter cap + compact keys in landscape)
     *
     * @param actionBar Horizontal button bar (Skip / Cancel / Start Tab) placed
     *                  below the scroll area but above the keyboard.
     */
    fun wrapFormWithKeypads(context: Context, formContent: LinearLayout, actionBar: View): View {
        val etSearch = formContent.findViewById<AutoCompleteTextView>(R.id.etSearchCustomer)
        val etName   = formContent.findViewById<EditText>(R.id.etCustomerName)
        val etPhone  = formContent.findViewById<EditText>(R.id.etCustomerPhone)
        val etEmail  = formContent.findViewById<EditText>(R.id.etCustomerEmail)
        return wrapFormWithKeypads(
            context = context,
            formContent = formContent,
            actionBar = actionBar,
            alphaFields = listOfNotNull(etSearch, etName, etEmail),
            phoneFields = listOfNotNull(etPhone),
        )
    }

    /**
     * Overload with explicit field lists so callers that build their form in code
     * (e.g. [CustomerDialogHelper]) don't need to rely on XML IDs.
     */
    fun wrapFormWithKeypads(
        context: Context,
        formContent: LinearLayout,
        actionBar: View,
        alphaFields: List<EditText>,
        phoneFields: List<EditText>,
    ): View {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val allFields = alphaFields + phoneFields

        fun dp(v: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
        ).toInt()

        val isLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val windowHeight = context.resources.displayMetrics.heightPixels
        val maxKbHeight = if (isLandscape) {
            (windowHeight * 0.70f).toInt().coerceAtLeast(dp(300f))
        } else {
            (windowHeight * 0.55f).toInt().coerceAtLeast(dp(360f))
        }
        /* ── content area: scrollable form + action bar ── */

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

        val contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentArea.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        contentArea.addView(
            actionBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        /* ── keyboard outer shell ── */

        val keypadOuter = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.pos_keyboard_panel_bg))
            visibility = View.GONE
            clipChildren = true
            isClickable = true
        }

        keypadOuter.addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.pos_key_border))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
                )
            }
        )

        val alphaPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.pos_keyboard_panel_bg))
        }

        val phonePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.pos_keyboard_panel_bg))
            visibility = View.GONE
        }

        var activeField: EditText? = null
        var activeKind: KeypadKind = KeypadKind.ALPHA

        /* ── caret insertion helper ── */

        fun insertAtCaret(edit: EditText, token: String) {
            val ed = edit.text ?: return
            var start = edit.selectionStart.coerceIn(0, ed.length)
            var end   = edit.selectionEnd.coerceIn(0, ed.length)
            if (start > end) start = end.also { end = start }

            when (token) {
                "⌫" -> when {
                    start != end -> {
                        ed.replace(start, end, "")
                        edit.setSelection(start.coerceIn(0, ed.length))
                    }
                    start > 0 -> {
                        ed.replace(start - 1, start, "")
                        edit.setSelection((start - 1).coerceIn(0, ed.length))
                    }
                }
                "SPACE" -> {
                    ed.replace(start, end, " ")
                    edit.setSelection((start + 1).coerceIn(0, ed.length))
                }
                else -> {
                    ed.replace(start, end, token)
                    edit.setSelection((start + token.length).coerceIn(0, ed.length))
                }
            }
        }

        fun hideKeypad() {
            if (keypadOuter.visibility != View.VISIBLE) return
            val lpH = (keypadOuter.layoutParams as? FrameLayout.LayoutParams)?.height ?: 0
            val h = if (lpH > 0) lpH else keypadOuter.height
            val slide = if (h > 0) h.toFloat() else 360f
            keypadOuter.animate()
                .translationY(slide)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    keypadOuter.visibility = View.GONE
                    keypadOuter.translationY = 0f
                    contentArea.setPadding(0, 0, 0, 0)
                }
                .start()
        }

        fun scrollFocusedIntoView() {
            val field = activeField ?: return
            scroll.post {
                val r = Rect()
                field.getDrawingRect(r)
                formContent.offsetDescendantRectToMyCoords(field, r)
                scroll.requestChildRectangleOnScreen(formContent, r, true)
            }
        }

        fun applyKeypadGeometry() {
            val parentW = (keypadOuter.parent as? View)?.width ?: keypadOuter.width
            if (parentW <= 0) return
            keypadOuter.measure(
                MeasureSpec.makeMeasureSpec(parentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxKbHeight, MeasureSpec.AT_MOST)
            )
            val kbH = keypadOuter.measuredHeight.coerceAtMost(maxKbHeight)
            val lp = keypadOuter.layoutParams as FrameLayout.LayoutParams
            lp.height = kbH
            keypadOuter.layoutParams = lp
            contentArea.setPadding(0, 0, 0, kbH)
            scrollFocusedIntoView()
        }

        fun showKeypad() {
            alphaPanel.visibility = if (activeKind == KeypadKind.ALPHA) View.VISIBLE else View.GONE
            phonePanel.visibility = if (activeKind == KeypadKind.PHONE) View.VISIBLE else View.GONE

            if (keypadOuter.visibility == View.VISIBLE) {
                keypadOuter.post { applyKeypadGeometry() }
                return
            }
            keypadOuter.visibility = View.INVISIBLE
            keypadOuter.post {
                val parentW = (keypadOuter.parent as? View)?.width ?: keypadOuter.width
                if (parentW <= 0) {
                    keypadOuter.visibility = View.VISIBLE
                    return@post
                }
                applyKeypadGeometry()
                val lpH = (keypadOuter.layoutParams as FrameLayout.LayoutParams).height
                val slideFrom = if (lpH > 0) lpH.toFloat() else 320f
                keypadOuter.translationY = slideFrom
                keypadOuter.visibility = View.VISIBLE
                keypadOuter.animate()
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { scrollFocusedIntoView() }
                    .start()
            }
        }

        val performKeyboardDone: () -> Unit = {
            allFields.forEach { it.clearFocus() }
            imm.hideSoftInputFromWindow(keypadOuter.windowToken, 0)
            hideKeypad()
        }

        val onAlphaInsert: (String) -> Unit = { token ->
            activeField?.let { insertAtCaret(it, token) }
        }
        alphaPanel.addView(
            PosQwertyKeypad.build(context, PosQwertyKeypad.Variant.EMAIL, onAlphaInsert),
        )

        val res = context.resources
        val marginH = res.getDimensionPixelSize(R.dimen.pos_key_margin_h)
        val marginV = res.getDimensionPixelSize(R.dimen.pos_key_margin_v)
        val minH = res.getDimensionPixelSize(R.dimen.pos_key_min_height)
        val compactSp =
            res.getDimension(R.dimen.pos_key_label_compact_sp) / res.displayMetrics.scaledDensity
        val doneRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        doneRow.addView(
            TextView(context).apply {
                text = context.getString(R.string.pos_keypad_done)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, compactSp)
                setTextColor(ContextCompat.getColor(context, R.color.pos_key_text_primary))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_pos_key_default)
                minimumHeight = minH
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { setMargins(marginH, marginV, marginH, marginV) }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        500,
                        false,
                    )
                }
                setOnClickListener { performKeyboardDone() }
            },
        )
        alphaPanel.addView(doneRow)

        val onPhoneInsert: (String) -> Unit = { token ->
            activeField?.let { insertAtCaret(it, token) }
        }
        phonePanel.addView(
            PosQwertyKeypad.buildPhoneDigitsKeypad(context, onPhoneInsert, performKeyboardDone),
        )

        keypadOuter.addView(alphaPanel)
        keypadOuter.addView(phonePanel)

        /* ── root: FrameLayout with keyboard anchored at bottom ── */

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(contentArea)
        root.addView(
            keypadOuter,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        /* ── wire alpha / phone fields ── */

        fun wireAlpha(et: EditText) {
            et.showSoftInputOnFocus = false
            et.setOnClickListener { v -> v.requestFocus() }
            et.setOnFocusChangeListener { v, hasFocus ->
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                if (hasFocus) {
                    activeField = et
                    activeKind = KeypadKind.ALPHA
                    showKeypad()
                } else {
                    v.post {
                        if (!allFields.any { it.hasFocus() }) {
                            activeField = null
                            hideKeypad()
                        }
                    }
                }
            }
        }

        for (et in alphaFields) wireAlpha(et)

        val digitsOnlyFilter = InputFilter { source, _, _, _, _, _ ->
            val filtered = source.filter { it.isDigit() }
            when {
                filtered.length == source.length -> null
                filtered.isEmpty() -> ""
                else -> filtered
            }
        }
        for (et in phoneFields) {
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.filters = arrayOf(digitsOnlyFilter)
            et.showSoftInputOnFocus = false
            et.setOnClickListener { v -> v.requestFocus() }
            et.setOnFocusChangeListener { v, hasFocus ->
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                if (hasFocus) {
                    activeField = et
                    activeKind = KeypadKind.PHONE
                    showKeypad()
                } else {
                    v.post {
                        if (!allFields.any { it.hasFocus() }) {
                            activeField = null
                            hideKeypad()
                        }
                    }
                }
            }
        }

        /* ── touch-outside dismisses keyboard ── */

        root.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            if (keypadOuter.visibility != View.VISIBLE) return@setOnTouchListener false
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val keypadRect = Rect()
            keypadOuter.getGlobalVisibleRect(keypadRect)
            if (keypadRect.contains(x, y)) return@setOnTouchListener false
            for (et in allFields) {
                val r = Rect()
                et.getGlobalVisibleRect(r)
                if (r.contains(x, y)) return@setOnTouchListener false
            }
            allFields.forEach { it.clearFocus() }
            imm.hideSoftInputFromWindow(root.windowToken, 0)
            hideKeypad()
            false
        }

        return root
    }
}
