package com.ernesto.myapplication

import android.app.Activity
import android.content.Context
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
import androidx.core.content.ContextCompat

/**
 * Email and guest-name entry with an in-app keypad (no system IME), matching POS / bar-seat keyboard styling.
 */
object ReceiptEmailKeypadDialog {

    enum class KeypadVariant { EMAIL, GUEST_NAME }

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
     * Vertical [LinearLayout] of keys; calls [onInsert] with a character or "⌫" / "SPACE".
     * [KeypadVariant.GUEST_NAME] omits email-specific keys (uses hyphen, apostrophe, period row).
     * Sizing parameters are kept for API compatibility; layout uses [R.dimen.pos_key_*].
     */
    fun buildKeypadView(
        context: Context,
        variant: KeypadVariant = KeypadVariant.EMAIL,
        keyMinHeightDp: Float = 36f,
        keyMarginDp: Float = 2f,
        keyTextSizeSp: Float = 15f,
        keyTextSizeCompactSp: Float = 12f,
        panelPaddingHorizontalDp: Float = 6f,
        panelPaddingVerticalDp: Float = 8f,
        onInsert: (String) -> Unit,
    ): LinearLayout {
        val posVariant = when (variant) {
            KeypadVariant.EMAIL -> PosQwertyKeypad.Variant.EMAIL
            KeypadVariant.GUEST_NAME -> PosQwertyKeypad.Variant.GUEST_NAME
        }
        val keys = PosQwertyKeypad.build(context, posVariant, onInsert)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.pos_keyboard_panel_bg))
            addView(
                keys,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
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
