package com.volt.maximobile

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Hosts the on-screen POS keyboard in its **own** [android.view.Window] via [WindowManager], so
 * it is rendered *above* the activity (or any dialog the focused [EditText] belongs to) instead
 * of competing for layout space inside the dialog's window. Dialogs can subscribe to
 * [VisibilityListener] to resize themselves when the keyboard appears or disappears.
 *
 * [addEditText] wires the QWERTY layout; [addNumericEditText] wires the same behavior with a
 * decimal numpad (inventory price field only).
 */
class PosKeyboardController private constructor(
    private val activity: Activity,
    private val keyboardView: PosKeyboardView,
    private val numericKeyboardView: PosNumericKeyboardView,
    private val panelWrapper: FrameLayout,
    private val editTexts: MutableList<EditText>,
    private val numericTargets: MutableSet<EditText>,
) {

    /** Receives keyboard visibility changes so the host UI (e.g. a dialog) can adapt its size. */
    interface VisibilityListener {
        fun onKeyboardShown(heightPx: Int)
        fun onKeyboardHidden()
    }

    companion object {
        fun attach(activity: Activity, targets: List<EditText>): PosKeyboardController {
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager

            lateinit var controller: PosKeyboardController

            val kbd = PosKeyboardView.create(activity) { key ->
                controller.dispatchKey(key)
            }
            val numKbd = PosNumericKeyboardView.create(activity) { key ->
                controller.dispatchKey(key)
            }

            val panelWrapper = FrameLayout(activity).apply {
                setPadding(0, 0, 0, 0)
                clipChildren = false
                clipToPadding = false
                addView(
                    kbd.view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    numKbd.view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                numKbd.view.visibility = View.GONE
            }

            controller = PosKeyboardController(
                activity,
                kbd,
                numKbd,
                panelWrapper,
                targets.toMutableList(),
                mutableSetOf(),
            )

            for (et in targets) {
                controller.wireEditText(et, imm)
            }

            return controller
        }
    }

    private val visibilityListeners = mutableListOf<VisibilityListener>()
    private var enterAction: (() -> Unit)? = null
    private var activeEditText: EditText? = null

    fun setEnterAction(action: (() -> Unit)?) {
        enterAction = action
    }
    private var addedToWm: Boolean = false
    private var lastReportedHeightPx: Int = 0

    fun addVisibilityListener(listener: VisibilityListener) {
        visibilityListeners.add(listener)
        if (addedToWm && lastReportedHeightPx > 0) {
            listener.onKeyboardShown(lastReportedHeightPx)
        }
    }

    fun removeVisibilityListener(listener: VisibilityListener) {
        visibilityListeners.remove(listener)
    }

    fun addEditText(et: EditText) {
        if (editTexts.contains(et)) return
        editTexts.add(et)
        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        wireEditText(et, imm)
    }

    /**
     * Same focus / overlay behavior as [addEditText], but shows the numeric keypad when this
     * field is focused.
     */
    fun addNumericEditText(et: EditText) {
        numericTargets.add(et)
        if (editTexts.contains(et)) return
        editTexts.add(et)
        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        wireEditText(et, imm)
    }

    private fun wireEditText(et: EditText, imm: InputMethodManager) {
        et.showSoftInputOnFocus = false
        et.setOnClickListener { v -> v.requestFocus() }
        et.setOnFocusChangeListener { v, hasFocus ->
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            if (hasFocus) {
                activeEditText = et
                updateKeyboardSurface(et)
                show(et)
            } else {
                v.post {
                    if (!editTexts.any { it.hasFocus() }) {
                        if (!addedToWm) {
                            activeEditText = null
                            hide()
                        }
                    }
                }
            }
        }
    }

    private fun updateKeyboardSurface(et: EditText) {
        val showNumeric = numericTargets.contains(et)
        keyboardView.view.visibility = if (showNumeric) View.GONE else View.VISIBLE
        numericKeyboardView.view.visibility = if (showNumeric) View.VISIBLE else View.GONE
    }

    private fun dispatchKey(key: String) {
        val et = activeEditText ?: return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        val editable = et.text ?: return

        when (key) {
            PosKeyboardView.KEY_BACKSPACE -> {
                if (start != end) {
                    editable.delete(start.coerceAtMost(end), start.coerceAtLeast(end))
                } else if (start > 0) {
                    editable.delete(start - 1, start)
                }
            }
            PosKeyboardView.KEY_ENTER -> {
                enterAction?.invoke()
                hide()
                editTexts.forEach { it.clearFocus() }
            }
            else -> {
                val keyToInsert =
                    if (numericTargets.contains(et) && key == ",") "." else key
                if (start != end) {
                    editable.replace(start.coerceAtMost(end), start.coerceAtLeast(end), keyToInsert)
                } else {
                    editable.insert(start, keyToInsert)
                }
            }
        }
    }

    private fun show(anchor: EditText) {
        updateKeyboardSurface(anchor)
        if (addedToWm) {
            lastReportedHeightPx = 0
            panelWrapper.post { reportShownIfMeasured() }
            return
        }

        val token = activity.window.decorView.windowToken ?: return

        (panelWrapper.parent as? ViewGroup)?.removeView(panelWrapper)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            this.token = token
            y = navigationBarBottomInset()
            windowAnimations = 0
        }

        try {
            activity.windowManager.addView(panelWrapper, params)
            addedToWm = true
        } catch (_: Exception) {
            return
        }

        keyboardView.view.apply {
            translationY = 0f
            alpha = 1f
        }
        numericKeyboardView.view.apply {
            translationY = 0f
            alpha = 1f
        }
        panelWrapper.apply {
            translationY = 0f
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        panelWrapper.post { reportShownIfMeasured() }
    }

    private fun reportShownIfMeasured() {
        if (!addedToWm) return
        val h = panelWrapper.height
        if (h <= 0) {
            panelWrapper.post { reportShownIfMeasured() }
            return
        }
        if (h == lastReportedHeightPx) return
        lastReportedHeightPx = h
        visibilityListeners.toList().forEach { it.onKeyboardShown(h) }
    }

    private fun hideImmediate() {
        panelWrapper.animate().cancel()
        keyboardView.view.animate().cancel()
        numericKeyboardView.view.animate().cancel()
        if (addedToWm) {
            try {
                activity.windowManager.removeView(panelWrapper)
            } catch (_: Exception) {
                // already removed
            }
            addedToWm = false
        }
        panelWrapper.apply {
            translationY = 0f
            alpha = 1f
        }
        keyboardView.view.apply {
            translationY = 0f
            alpha = 1f
        }
        numericKeyboardView.view.apply {
            translationY = 0f
            alpha = 1f
        }
        if (lastReportedHeightPx != 0) {
            lastReportedHeightPx = 0
            visibilityListeners.toList().forEach { it.onKeyboardHidden() }
        }
    }

    fun dismissWithoutAnimation() {
        hideImmediate()
    }

    fun hide() {
        if (!addedToWm) return
        panelWrapper.animate()
            .alpha(0f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { hideImmediate() }
            .start()
    }

    private fun navigationBarBottomInset(): Int {
        val root = activity.window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = root.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())
            if (insets != null && insets.bottom > 0) return insets.bottom
        }
        val resId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
    }

    fun detach() {
        panelWrapper.animate().cancel()
        keyboardView.view.animate().cancel()
        numericKeyboardView.view.animate().cancel()
        hideImmediate()
        editTexts.clear()
        numericTargets.clear()
        visibilityListeners.clear()
    }
}
