package com.volt.maximobile

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat

/**
 * Create-reservation dialog keyboard — same [PosQwertyKeypad] as modifier option editing,
 * with a digits-only pad for phone / party size.
 */
class ReservationDialogKeyboardHelper(
    private val activity: AppCompatActivity,
    private val keyboardRoot: ViewGroup,
    private val fields: List<EditText>,
    private val qwertyFieldIds: Set<Int>,
    private val onAnyFieldFocusChange: ((EditText, Boolean) -> Unit)? = null,
) {
    private val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
    private var activeField: EditText? = null
    private var switchHost: FrameLayout? = null
    private var alphaPanel: View? = null
    private var digitsPanel: View? = null
    private var keyboardExpanded = true

    fun start() {
        keyboardRoot.removeAllViews()
        if (keyboardRoot is LinearLayout) {
            keyboardRoot.orientation = LinearLayout.VERTICAL
        }
        keyboardRoot.background = activity.getDrawable(R.drawable.bg_pos_keyboard_panel)
        ViewCompat.setElevation(keyboardRoot, activity.resources.getDimension(R.dimen.pos_keyboard_elevation))

        val density = activity.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val toggleKeyboard = TextView(activity).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setText(R.string.pos_keyboard_hide)
            setTextColor(activity.getColor(R.color.brand_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            val typedArray = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = typedArray.getDrawable(0)
            typedArray.recycle()
            isClickable = true
            isFocusable = false
        }

        val toolbar = LinearLayout(activity).apply {
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
        keyboardRoot.addView(toolbar)

        val host = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        switchHost = host

        fun focusNext(from: EditText) {
            val idx = fields.indexOf(from)
            if (idx >= 0 && idx < fields.lastIndex) {
                fields[idx + 1].requestFocus()
            } else {
                from.clearFocus()
            }
        }

        val alpha = PosQwertyKeypad.build(
            activity,
            PosQwertyKeypad.Variant.MODIFIER_OPTION,
            onInsert = { token ->
                val et = activeField ?: return@build
                if (et.id in qwertyFieldIds) {
                    ReceiptEmailKeypadDialog.insertAtCaret(et, token)
                }
            },
            onEnter = {
                val et = activeField ?: return@build
                focusNext(et)
            },
        )
        alphaPanel = alpha

        val digits = PosQwertyKeypad.buildPhoneDigitsKeypad(
            activity,
            onInsert = { token ->
                val et = activeField ?: return@buildPhoneDigitsKeypad
                if (et.id !in qwertyFieldIds) {
                    ReceiptEmailKeypadDialog.insertAtCaret(et, token)
                }
            },
            onDone = {
                val et = activeField ?: return@buildPhoneDigitsKeypad
                focusNext(et)
            },
        )
        digitsPanel = digits

        host.addView(
            alpha,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        host.addView(
            digits,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        digits.visibility = View.GONE
        keyboardRoot.addView(host)

        fun updateToggleLabel() {
            toggleKeyboard.setText(
                if (keyboardExpanded) R.string.pos_keyboard_hide else R.string.pos_keyboard_show,
            )
        }

        fun syncPanelToFocus() {
            val et = activeField
            val showDigits = et != null && et.id !in qwertyFieldIds
            alpha.visibility = if (showDigits) View.GONE else View.VISIBLE
            digits.visibility = if (showDigits) View.VISIBLE else View.GONE
        }

        fun expandKeyboard() {
            if (keyboardExpanded) return
            keyboardExpanded = true
            host.visibility = View.VISIBLE
            updateToggleLabel()
            syncPanelToFocus()
        }

        fun collapseKeyboard() {
            if (!keyboardExpanded) return
            keyboardExpanded = false
            host.visibility = View.GONE
            fields.forEach { it.clearFocus() }
            imm.hideSoftInputFromWindow(keyboardRoot.windowToken, 0)
            updateToggleLabel()
        }

        toggleKeyboard.setOnClickListener {
            if (keyboardExpanded) collapseKeyboard() else expandKeyboard()
        }

        fields.forEach { setupField(it, ::expandKeyboard, ::syncPanelToFocus) }

        updateToggleLabel()
        val first = fields.firstOrNull() ?: return
        first.post {
            expandKeyboard()
            first.requestFocus()
            activeField = first
            syncPanelToFocus()
            hideIme(first)
        }
    }

    private fun setupField(
        et: EditText,
        expandKeyboard: () -> Unit,
        syncPanel: () -> Unit,
    ) {
        et.showSoftInputOnFocus = false
        if (et.id in qwertyFieldIds) {
            et.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            et.inputType = InputType.TYPE_CLASS_NUMBER
        }
        et.setOnClickListener {
            activeField = et
            hideIme(it)
            expandKeyboard()
            it.requestFocus()
        }
        et.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                activeField = et
                hideIme(v)
                expandKeyboard()
                syncPanel()
            }
            onAnyFieldFocusChange?.invoke(et, hasFocus)
        }
    }

    private fun hideIme(v: View) {
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }
}
