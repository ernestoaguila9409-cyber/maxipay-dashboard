package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.Calendar

/**
 * Touch-only time entry using the same key styling as the reservation keyboard (digits + ⌫ only).
 * No system soft keyboard.
 */
object ReservationNumericTimePicker {

    /**
     * @param onApply return true to dismiss, false to keep the dialog open (e.g. time in the past).
     * @param onBeforeShow e.g. hide the parent reservation keyboard so only the numpad shows.
     * @param onAfterDismiss restore parent UI (show keyboard again).
     */
    fun show(
        activity: AppCompatActivity,
        title: CharSequence,
        initialHourOfDay: Int,
        initialMinute: Int,
        is24Hour: Boolean,
        onBeforeShow: () -> Unit = {},
        onAfterDismiss: () -> Unit = {},
        onApply: (hourOfDay: Int, minute: Int) -> Boolean,
    ) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_reservation_numeric_time, null)
        val dialog = AppCompatDialog(activity).apply {
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
        val res = activity.resources
        val marginH = res.getDimensionPixelSize(R.dimen.reservation_numeric_time_dialog_margin_h)
        val maxContentW = res.getDimensionPixelSize(R.dimen.reservation_numeric_time_dialog_max_width)
        val screenW = res.displayMetrics.widthPixels
        val availableW = (screenW - marginH * 2).coerceAtLeast(1)
        val dialogWidth = availableW.coerceAtMost(maxContentW)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
            val lp = attributes
            lp.gravity = Gravity.CENTER
            lp.width = dialogWidth
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.dimAmount = 0.45f
            attributes = lp
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        root.isFocusableInTouchMode = true

        val txtTitle = root.findViewById<TextView>(R.id.txtNumericTimeTitle)
        val txtHour = root.findViewById<TextView>(R.id.txtNumericTimeHour)
        val txtMinute = root.findViewById<TextView>(R.id.txtNumericTimeMinute)
        val rowAmPm = root.findViewById<View>(R.id.rowNumericTimeAmPm)
        val btnAm = root.findViewById<MaterialButton>(R.id.btnNumericTimeAm)
        val btnPm = root.findViewById<MaterialButton>(R.id.btnNumericTimePm)
        val btnCancel = root.findViewById<MaterialButton>(R.id.btnNumericTimeCancel)
        val btnOk = root.findViewById<MaterialButton>(R.id.btnNumericTimeOk)

        listOf(btnAm, btnPm, btnCancel, btnOk).forEach { it.isFocusable = false }

        txtTitle.text = title

        val init = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, initialHourOfDay)
            set(Calendar.MINUTE, initialMinute)
        }

        var isPm = init.get(Calendar.AM_PM) == Calendar.PM
        var hourBufMut = if (is24Hour) {
            rowAmPm.visibility = View.GONE
            init.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        } else {
            rowAmPm.visibility = View.VISIBLE
            init.get(Calendar.HOUR).toString().padStart(2, '0')
        }
        var minuteBuf = init.get(Calendar.MINUTE).toString().padStart(2, '0')
        var editingHour = true

        val brand = ContextCompat.getColor(activity, R.color.brand_primary)
        val strokeRest = ContextCompat.getColor(activity, R.color.box_stroke_resting)

        fun updateFieldChrome() {
            txtHour.setBackgroundResource(
                if (editingHour) R.drawable.bg_numeric_time_digit_selected else R.drawable.bg_numeric_time_digit,
            )
            txtMinute.setBackgroundResource(
                if (!editingHour) R.drawable.bg_numeric_time_digit_selected else R.drawable.bg_numeric_time_digit,
            )
        }

        fun refreshAmPmStrokes() {
            if (is24Hour) return
            btnAm.strokeColor = ColorStateList.valueOf(if (!isPm) brand else strokeRest)
            btnPm.strokeColor = ColorStateList.valueOf(if (isPm) brand else strokeRest)
            val density = activity.resources.displayMetrics.density
            val wSel = (2 * density).toInt().coerceAtLeast(2)
            val wNorm = (1 * density).toInt().coerceAtLeast(1)
            btnAm.strokeWidth = if (!isPm) wSel else wNorm
            btnPm.strokeWidth = if (isPm) wSel else wNorm
        }

        fun refreshDisplay() {
            txtHour.text = hourBufMut.ifEmpty { " " }
            txtMinute.text = minuteBuf.ifEmpty { " " }
            updateFieldChrome()
            refreshAmPmStrokes()
        }

        fun appendDigit(d: Int) {
            val ch = ('0' + d).toString()
            if (editingHour) {
                hourBufMut = if (hourBufMut.length < 2) hourBufMut + ch else ch
            } else {
                minuteBuf = if (minuteBuf.length < 2) minuteBuf + ch else ch
            }
            refreshDisplay()
        }

        fun backspace() {
            if (editingHour) {
                if (hourBufMut.isNotEmpty()) hourBufMut = hourBufMut.dropLast(1)
            } else {
                if (minuteBuf.isNotEmpty()) minuteBuf = minuteBuf.dropLast(1)
            }
            refreshDisplay()
        }

        fun hourOfDayFromBuffers(): Int? {
            val hb = hourBufMut.trim()
            val mb = minuteBuf.trim()
            if (hb.isEmpty() || mb.isEmpty()) {
                Toast.makeText(activity, R.string.reservation_numeric_time_incomplete, Toast.LENGTH_SHORT).show()
                return null
            }
            val hi = hb.toIntOrNull()
            val mi = mb.toIntOrNull()
            if (hi == null || mi == null || mi !in 0..59) {
                Toast.makeText(activity, R.string.reservation_numeric_time_invalid, Toast.LENGTH_SHORT).show()
                return null
            }
            return if (is24Hour) {
                if (hi !in 0..23) {
                    Toast.makeText(activity, R.string.reservation_numeric_time_invalid, Toast.LENGTH_SHORT).show()
                    null
                } else {
                    hi
                }
            } else {
                if (hi !in 1..12) {
                    Toast.makeText(activity, R.string.reservation_numeric_time_invalid, Toast.LENGTH_SHORT).show()
                    null
                } else {
                    when {
                        hi == 12 && !isPm -> 0
                        hi == 12 && isPm -> 12
                        isPm -> hi + 12
                        else -> hi
                    }
                }
            }
        }

        listOf(txtHour, txtMinute).forEach { tv ->
            tv.isFocusable = false
            tv.isFocusableInTouchMode = false
        }
        txtHour.setOnClickListener {
            editingHour = true
            refreshDisplay()
        }
        txtMinute.setOnClickListener {
            editingHour = false
            refreshDisplay()
        }
        btnAm.setOnClickListener {
            isPm = false
            refreshDisplay()
        }
        btnPm.setOnClickListener {
            isPm = true
            refreshDisplay()
        }

        val digitIds = listOf(
            R.id.timeKey1 to 1, R.id.timeKey2 to 2, R.id.timeKey3 to 3,
            R.id.timeKey4 to 4, R.id.timeKey5 to 5, R.id.timeKey6 to 6,
            R.id.timeKey7 to 7, R.id.timeKey8 to 8, R.id.timeKey9 to 9,
            R.id.timeKey0 to 0,
        )
        for ((id, d) in digitIds) {
            root.findViewById<AppCompatButton>(id)?.apply {
                isFocusable = false
                setOnClickListener { appendDigit(d) }
            }
        }
        root.findViewById<AppCompatButton>(R.id.timeKeyBackspace)?.apply {
            isFocusable = false
            setOnClickListener { backspace() }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
            val hod = hourOfDayFromBuffers() ?: return@setOnClickListener
            if (onApply(hod, minuteBuf.trim().toInt())) {
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener { onAfterDismiss() }

        refreshDisplay()
        onBeforeShow()
        dialog.show()
        dialog.window?.decorView?.post {
            // Re-apply width after show (some devices ignore pre-show sizing); height wraps compact content.
            dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            root.requestFocus()
        }
    }
}
