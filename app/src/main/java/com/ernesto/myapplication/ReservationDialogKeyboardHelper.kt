package com.ernesto.myapplication

import android.content.Context
import android.text.Editable
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText

/**
 * In-app alphanumeric keyboard for the create-reservation dialog: same letters + numbers
 * for every field, with the system soft keyboard suppressed.
 * Shift (⇧) toggles caps for letter keys; labels update to match.
 */
class ReservationDialogKeyboardHelper(
    private val context: Context,
    private val keyboardRoot: View,
    private val fields: List<EditText>,
    private val onAnyFieldFocusChange: ((EditText, Boolean) -> Unit)? = null,
) {
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var activeField: EditText? = null
    private var capsOn = false

    private val letterKeys = listOf(
        R.id.keyQ to "q", R.id.keyW to "w", R.id.keyE to "e", R.id.keyR to "r",
        R.id.keyT to "t", R.id.keyY to "y", R.id.keyU to "u", R.id.keyI to "i",
        R.id.keyO to "o", R.id.keyP to "p",
        R.id.keyA to "a", R.id.keyS to "s", R.id.keyD to "d", R.id.keyF to "f",
        R.id.keyG to "g", R.id.keyH to "h", R.id.keyJ to "j", R.id.keyK to "k",
        R.id.keyL to "l",
        R.id.keyZ to "z", R.id.keyX to "x", R.id.keyC to "c", R.id.keyV to "v",
        R.id.keyB to "b", R.id.keyN to "n", R.id.keyM to "m",
    )

    fun start() {
        fields.forEach { setupField(it) }
        wireKeys()
        refreshLetterLabels()
        updateShiftButton()
        val first = fields.firstOrNull() ?: return
        first.post {
            first.requestFocus()
            activeField = first
            hideIme(first)
        }
    }

    private fun setupField(et: EditText) {
        et.showSoftInputOnFocus = false
        et.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                activeField = et
                hideIme(v)
            }
            onAnyFieldFocusChange?.invoke(et, hasFocus)
        }
        et.setOnClickListener {
            activeField = et
            hideIme(it)
            et.requestFocus()
        }
    }

    private fun hideIme(v: View) {
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun insert(s: String) {
        val et = activeField ?: fields.firstOrNull { it.hasFocus() } ?: return
        val ed = et.text as? Editable ?: return
        var start = et.selectionStart
        var end = et.selectionEnd
        if (start < 0) start = ed.length
        if (end < 0) end = ed.length
        if (start > end) {
            val t = start
            start = end
            end = t
        }
        ed.replace(start, end, s)
        et.setSelection(start + s.length)
    }

    private fun backspace() {
        val et = activeField ?: fields.firstOrNull { it.hasFocus() } ?: return
        val ed = et.text as? Editable ?: return
        var start = et.selectionStart
        var end = et.selectionEnd
        if (start < 0) start = 0
        if (end < 0) end = 0
        if (start > end) {
            val t = start
            start = end
            end = t
        }
        if (start != end) {
            ed.delete(start, end)
            et.setSelection(start)
        } else if (start > 0) {
            ed.delete(start - 1, start)
            et.setSelection(start - 1)
        }
    }

    private fun refreshLetterLabels() {
        for ((id, lower) in letterKeys) {
            keyboardRoot.findViewById<Button>(id)?.text =
                if (capsOn) lower.uppercase() else lower
        }
    }

    private fun updateShiftButton() {
        val btn = keyboardRoot.findViewById<Button>(R.id.keyShift) ?: return
        btn.setBackgroundResource(
            if (capsOn) R.drawable.bg_pos_key_shift_active else R.drawable.bg_pos_key_special,
        )
        val color = context.getColor(
            if (capsOn) R.color.brand_primary else R.color.pos_key_text_primary,
        )
        btn.setTextColor(color)
    }

    private fun wireKeys() {
        val digitAndSymbolPairs = listOf(
            R.id.key0 to "0", R.id.key1 to "1", R.id.key2 to "2", R.id.key3 to "3",
            R.id.key4 to "4", R.id.key5 to "5", R.id.key6 to "6", R.id.key7 to "7",
            R.id.key8 to "8", R.id.key9 to "9",
            R.id.keyColon to ":",
            R.id.keyComma to ",", R.id.keyPeriod to ".",
            R.id.keyAt to "@", R.id.keyPlus to "+", R.id.keyMinus to "-",
            R.id.keySlash to "/", R.id.keyLparen to "(", R.id.keyRparen to ")",
        )
        for ((id, ch) in digitAndSymbolPairs) {
            keyboardRoot.findViewById<Button>(id)?.setOnClickListener { insert(ch) }
        }
        for ((id, lower) in letterKeys) {
            keyboardRoot.findViewById<Button>(id)?.setOnClickListener {
                insert(if (capsOn) lower.uppercase() else lower)
            }
        }
        keyboardRoot.findViewById<Button>(R.id.keyShift)?.setOnClickListener {
            capsOn = !capsOn
            refreshLetterLabels()
            updateShiftButton()
        }
        keyboardRoot.findViewById<Button>(R.id.keySpace)?.setOnClickListener { insert(" ") }
        keyboardRoot.findViewById<Button>(R.id.keyBackspace)?.setOnClickListener { backspace() }
    }
}
