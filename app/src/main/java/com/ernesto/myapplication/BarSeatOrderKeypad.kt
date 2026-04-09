package com.ernesto.myapplication

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton

/**
 * Bottom-anchored custom keyboard for the bar-seat-order dialog.
 * The keyboard slides up from the bottom like a system IME, overlaying
 * the content. Height is capped at ~35 % of the screen.
 *
 * @see InventoryPriceKeypad — same UX contract for price fields.
 */
object BarSeatOrderKeypad {

    private fun outlinedStyle(context: Context): Int {
        val attrId = context.resources.getIdentifier(
            "materialButtonOutlinedStyle", "attr", context.packageName
        )
        if (attrId == 0) return 0
        val tv = TypedValue()
        context.theme.resolveAttribute(attrId, tv, true)
        return tv.data
    }

    private enum class KeypadKind { ALPHA, PHONE }

    /**
     * Wraps [formContent] and [actionBar] in a [FrameLayout]:
     *
     *   FrameLayout (fill screen)
     *   ├── content  (scroll + action bar)  ← gets bottom padding when keyboard visible
     *   └── keyboard (layout_gravity=BOTTOM, max 35 % height)
     *
     * @param actionBar Horizontal button bar (Skip / Cancel / Start Tab) placed
     *                  below the scroll area but above the keyboard.
     */
    fun wrapFormWithKeypads(context: Context, formContent: LinearLayout, actionBar: View): View {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxKbHeight = (screenHeight * 0.48f).toInt()

        val etSearch = formContent.findViewById<AutoCompleteTextView>(R.id.etSearchCustomer)
        val etName   = formContent.findViewById<EditText>(R.id.etCustomerName)
        val etPhone  = formContent.findViewById<EditText>(R.id.etCustomerPhone)
        val etEmail  = formContent.findViewById<EditText>(R.id.etCustomerEmail)

        val alphaFields = listOf(etSearch, etName, etEmail)
        val phoneFields = listOf(etPhone)
        val allFields   = alphaFields + phoneFields

        fun dp(v: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
        ).toInt()

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
            setBackgroundColor(Color.parseColor("#ECEFF4"))
            visibility = View.GONE
        }

        keypadOuter.addView(
            View(context).apply {
                setBackgroundColor(Color.parseColor("#D1D5DB"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
                )
            }
        )

        val alphaPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(2f))
        }

        val phonePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(2f))
            visibility = View.GONE
        }

        var activeField: EditText? = null
        var activeKind: KeypadKind = KeypadKind.ALPHA
        var capsOn = false

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

        /** Phone-pad key (same outlined style as [InventoryPriceKeypad]). */
        fun makeKeyButton(label: String, grid: GridLayout, onPress: () -> Unit): MaterialButton {
            return MaterialButton(context, null, outlinedStyle(context)).apply {
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
            }.also { grid.addView(it) }
        }

        /* ── alpha key rows (compact: 36 dp height, 2 dp margins) ── */

        val letterButtons = mutableListOf<MaterialButton>()
        val m    = dp(2f)
        val btnH = dp(32f)

        fun refreshCapsLabels() {
            for (btn in letterButtons) {
                val k = btn.tag as? String ?: continue
                btn.text = if (capsOn) k.uppercase() else k
            }
        }

        fun addAlphaRow(keys: List<String>) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val w = 1f / keys.size.coerceAtLeast(1)
            for (k in keys) {
                val isLetter = k.length == 1 && k[0].isLetter()
                val display  = if (isLetter && capsOn) k.uppercase() else k
                val btn = MaterialButton(context, null, outlinedStyle(context)).apply {
                    text = display
                    tag  = if (isLetter) k else null
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.length > 2) 13f else 16f)
                    minimumHeight = btnH
                    minimumWidth  = 0
                    insetTop    = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
                        .apply { setMargins(m, m, m, m) }
                    setOnClickListener {
                        activeField?.let { e ->
                            when (k) {
                                "⌫" -> insertAtCaret(e, "⌫")
                                else -> {
                                    val ch = if (isLetter && capsOn) k.uppercase() else k
                                    insertAtCaret(e, ch)
                                }
                            }
                        }
                    }
                }
                if (isLetter) letterButtons.add(btn)
                row.addView(btn)
            }
            alphaPanel.addView(row)
        }

        addAlphaRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addAlphaRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addAlphaRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))

        val zRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val capsBtn = MaterialButton(context, null, outlinedStyle(context)).apply {
            text = "⇧"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minimumHeight = btnH
            minimumWidth  = 0
            insetTop    = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                .apply { setMargins(m, m, m, m) }
            setOnClickListener {
                capsOn = !capsOn
                text = if (capsOn) "⇩" else "⇧"
                refreshCapsLabels()
            }
        }
        zRow.addView(capsBtn)
        for (k in listOf("z", "x", "c", "v", "b", "n", "m")) {
            val btn = MaterialButton(context, null, outlinedStyle(context)).apply {
                text = k
                tag  = k
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minimumHeight = btnH
                minimumWidth  = 0
                insetTop    = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener {
                    activeField?.let { e ->
                        val ch = if (capsOn) k.uppercase() else k
                        insertAtCaret(e, ch)
                    }
                }
            }
            letterButtons.add(btn)
            zRow.addView(btn)
        }
        zRow.addView(
            MaterialButton(context, null, outlinedStyle(context)).apply {
                text = "⌫"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minimumHeight = btnH
                minimumWidth  = 0
                insetTop    = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { activeField?.let { insertAtCaret(it, "⌫") } }
            }
        )
        alphaPanel.addView(zRow)

        addAlphaRow(listOf("@", ".", "_", "-", "'"))

        val spaceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        spaceRow.addView(
            MaterialButton(context, null, outlinedStyle(context)).apply {
                text = "Space"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minimumHeight = btnH
                minimumWidth  = 0
                insetTop    = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { activeField?.let { insertAtCaret(it, "SPACE") } }
            }
        )
        spaceRow.addView(
            MaterialButton(context, null, outlinedStyle(context)).apply {
                text = "⌫"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minimumHeight = btnH
                minimumWidth  = 0
                insetTop    = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { activeField?.let { insertAtCaret(it, "⌫") } }
            }
        )
        lateinit var doneBtnRef: MaterialButton
        spaceRow.addView(
            MaterialButton(context, null, outlinedStyle(context)).apply {
                text = "Done"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minimumHeight = btnH
                minimumWidth  = 0
                insetTop    = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
                    .apply { setMargins(m, m, m, m) }
                doneBtnRef = this
            }
        )
        alphaPanel.addView(spaceRow)

        /* ── phone keypad ── */

        val phoneGrid = GridLayout(context).apply {
            columnCount = 3
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")) {
            makeKeyButton(k, phoneGrid) { activeField?.let { insertAtCaret(it, k) } }
        }
        makeKeyButton("+", phoneGrid) { activeField?.let { insertAtCaret(it, "+") } }
        makeKeyButton("0", phoneGrid) { activeField?.let { insertAtCaret(it, "0") } }
        makeKeyButton("⌫", phoneGrid) { activeField?.let { insertAtCaret(it, "⌫") } }
        phonePanel.addView(phoneGrid)

        keypadOuter.addView(alphaPanel)
        keypadOuter.addView(phonePanel)

        /* ── show / hide helpers (must be declared before doneBtn / wireAlpha) ── */

        fun hideKeypad() {
            if (keypadOuter.visibility != View.VISIBLE) return
            val h = keypadOuter.height
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

        fun showKeypad() {
            alphaPanel.visibility = if (activeKind == KeypadKind.ALPHA) View.VISIBLE else View.GONE
            phonePanel.visibility = if (activeKind == KeypadKind.PHONE) View.VISIBLE else View.GONE

            if (keypadOuter.visibility == View.VISIBLE) {
                keypadOuter.post { contentArea.setPadding(0, 0, 0, keypadOuter.height) }
                return
            }
            keypadOuter.visibility = View.INVISIBLE
            keypadOuter.post {
                val parentW = (keypadOuter.parent as? View)?.width ?: keypadOuter.width
                if (parentW <= 0) {
                    keypadOuter.visibility = View.VISIBLE
                    return@post
                }
                keypadOuter.measure(
                    MeasureSpec.makeMeasureSpec(parentW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(maxKbHeight, MeasureSpec.AT_MOST)
                )
                val kbH = keypadOuter.measuredHeight.coerceAtMost(maxKbHeight)

                val lp = keypadOuter.layoutParams as FrameLayout.LayoutParams
                lp.height = kbH
                keypadOuter.layoutParams = lp

                contentArea.setPadding(0, 0, 0, kbH)
                keypadOuter.translationY = kbH.toFloat()
                keypadOuter.visibility = View.VISIBLE
                keypadOuter.animate()
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        doneBtnRef.setOnClickListener {
            allFields.forEach { it.clearFocus() }
            imm.hideSoftInputFromWindow(doneBtnRef.windowToken, 0)
            hideKeypad()
        }

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

        fun wireAlpha(et: EditText, inputTypeFlags: Int) {
            et.inputType = inputTypeFlags
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

        wireAlpha(etSearch, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        wireAlpha(etName,   InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        wireAlpha(etEmail,  InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

        for (et in phoneFields) {
            et.inputType = InputType.TYPE_CLASS_PHONE
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
