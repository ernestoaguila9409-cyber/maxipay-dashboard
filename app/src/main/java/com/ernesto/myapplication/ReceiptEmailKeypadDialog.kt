package com.ernesto.myapplication

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.R as MTR
import com.google.android.material.button.MaterialButton

/**
 * Email entry with an in-app keypad (no system IME), matching POS / bar-seat keyboard styling.
 */
object ReceiptEmailKeypadDialog {

    fun insertAtCaret(edit: EditText, token: String) {
        val ed = edit.text ?: return
        var start = edit.selectionStart.coerceIn(0, ed.length)
        var end = edit.selectionEnd.coerceIn(0, ed.length)
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

    private fun dp(context: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v,
        context.resources.displayMetrics
    ).toInt()

    /**
     * Vertical [LinearLayout] of email-oriented keys; calls [onInsert] with a character or "⌫" / "SPACE".
     */
    fun buildKeypadView(context: Context, onInsert: (String) -> Unit): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ECEFF4"))
            setPadding(dp(context, 6f), dp(context, 8f), dp(context, 6f), dp(context, 8f))
        }

        val letterButtons = mutableListOf<MaterialButton>()
        var capsOn = false
        val m = dp(context, 2f)
        val btnH = dp(context, 36f)

        fun refreshCapsLabels() {
            for (btn in letterButtons) {
                val k = btn.tag as? String ?: continue
                btn.text = if (capsOn) k.uppercase() else k
            }
        }

        fun addRow(keys: List<String>) {
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
                val display = if (isLetter && capsOn) k.uppercase() else k
                val btn = MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                    text = display
                    tag = if (isLetter) k else null
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.length > 2) 12f else 15f)
                    minimumHeight = btnH
                    minimumWidth = 0
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
                        .apply { setMargins(m, m, m, m) }
                    setOnClickListener {
                        when (k) {
                            "⌫" -> onInsert("⌫")
                            else -> {
                                val ch = if (isLetter && capsOn) k.uppercase() else k
                                onInsert(ch)
                            }
                        }
                    }
                }
                if (isLetter) letterButtons.add(btn)
                row.addView(btn)
            }
            panel.addView(row)
        }

        addRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))

        val zRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        zRow.addView(
            MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = "⇧"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                minimumHeight = btnH
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener {
                    capsOn = !capsOn
                    text = if (capsOn) "⇩" else "⇧"
                    refreshCapsLabels()
                }
            }
        )
        for (k in listOf("z", "x", "c", "v", "b", "n", "m")) {
            val btn = MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = k
                tag = k
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minimumHeight = btnH
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener {
                    val ch = if (capsOn) k.uppercase() else k
                    onInsert(ch)
                }
            }
            letterButtons.add(btn)
            zRow.addView(btn)
        }
        zRow.addView(
            MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = "⌫"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minimumHeight = btnH
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { onInsert("⌫") }
            }
        )
        panel.addView(zRow)

        addRow(listOf("@", ".", "_", "-", "+"))

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bottomRow.addView(
            MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = ".com"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minimumHeight = btnH
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { onInsert(".com") }
            }
        )
        bottomRow.addView(
            MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = "Space"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minimumHeight = btnH
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { onInsert("SPACE") }
            }
        )
        bottomRow.addView(
            MaterialButton(context, null, MTR.attr.materialButtonOutlinedStyle).apply {
                text = "⌫"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minimumHeight = btnH
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener { onInsert("⌫") }
            }
        )
        panel.addView(bottomRow)

        return panel
    }

    fun show(
        activity: Activity,
        title: String,
        message: String? = null,
        hint: String = "customer@email.com",
        initialText: String = "",
        cancelable: Boolean = true,
        emptyEmailToast: String = "Please enter an email",
        onSend: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ): AlertDialog {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val input = EditText(activity).apply {
            this.hint = hint
            setText(initialText)
            setSelection(text?.length ?: 0)
            setPadding(dp(activity, 14f), dp(activity, 14f), dp(activity, 14f), dp(activity, 10f))
            inputType = InputType.TYPE_NULL
            showSoftInputOnFocus = false
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            textSize = 17f
        }

        val keypad = buildKeypadView(activity) { token -> insertAtCaret(input, token) }

        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20f), dp(activity, 4f), dp(activity, 20f), dp(activity, 4f))
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                keypad,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 10f) }
            )
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(
                inner,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .apply { if (!message.isNullOrBlank()) setMessage(message) }
            .setView(scroll)
            .setPositiveButton("Send", null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(cancelable)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            imm.hideSoftInputFromWindow(input.windowToken, 0)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(activity, emptyEmailToast, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                onSend(email)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                onCancel?.invoke()
                dialog.dismiss()
            }
        }

        dialog.show()
        input.post { input.requestFocus(); imm.hideSoftInputFromWindow(input.windowToken, 0) }
        return dialog
    }
}
