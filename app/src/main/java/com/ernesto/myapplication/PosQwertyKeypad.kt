package com.ernesto.myapplication

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat

/**
 * Modern embedded QWERTY for POS flows (no system IME). Styling uses [R.dimen.pos_key_*] and
 * [R.color.pos_key_*]. Special keys use Material-style icons.
 */
object PosQwertyKeypad {

    enum class Variant {
        EMAIL,
        GUEST_NAME,
        /** Name-style keys + prominent Enter (next field). */
        MODIFIER_OPTION,
    }

    fun build(
        context: Context,
        variant: Variant,
        onInsert: (String) -> Unit,
        onEnter: (() -> Unit)? = null,
    ): LinearLayout {
        val res = context.resources
        val marginH = res.getDimensionPixelSize(R.dimen.pos_key_margin_h)
        val marginV = res.getDimensionPixelSize(R.dimen.pos_key_margin_v)
        val minH = res.getDimensionPixelSize(R.dimen.pos_key_min_height)
        val pad = res.getDimensionPixelSize(R.dimen.pos_keyboard_panel_padding)
        val labelSp = res.getDimension(R.dimen.pos_key_label_sp) / res.displayMetrics.scaledDensity
        val compactSp =
            res.getDimension(R.dimen.pos_key_label_compact_sp) / res.displayMetrics.scaledDensity
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val letterButtons = mutableListOf<TextView>()
        var capsOn = false

        fun refreshCapsLabels() {
            for (tv in letterButtons) {
                val k = tv.tag as? String ?: continue
                tv.text = if (capsOn) k.uppercase() else k
            }
        }

        fun updateShiftButton(btn: AppCompatImageButton) {
            val active = capsOn
            btn.setBackgroundResource(
                if (active) R.drawable.bg_pos_key_shift_active else R.drawable.bg_pos_key_special,
            )
            val tint = context.getColor(if (active) R.color.brand_primary else R.color.pos_key_text_primary)
            ImageViewCompat.setImageTintList(btn, android.content.res.ColorStateList.valueOf(tint))
        }

        fun addTextRow(keys: List<String>) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val w = 1f / keys.size.coerceAtLeast(1)
            for (k in keys) {
                val isLetter = k.length == 1 && k[0].isLetter()
                val display = if (isLetter && capsOn) k.uppercase() else k
                val tv = TextView(context).apply {
                    text = display
                    tag = if (isLetter) k else null
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.length > 2) compactSp else labelSp)
                    setTextColor(context.getColor(R.color.pos_key_text_primary))
                    background = AppCompatResources.getDrawable(context, R.drawable.bg_pos_key_default)
                    minimumHeight = minH
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
                        setMargins(marginH, marginV, marginH, marginV)
                    }
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        android.graphics.Typeface.NORMAL,
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        typeface = android.graphics.Typeface.create(typeface, 500, false)
                    }
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
                if (isLetter) letterButtons.add(tv)
                row.addView(tv)
            }
            panel.addView(row)
        }

        fun iconButton(
            iconRes: Int,
            bgRes: Int,
            weight: Float,
            contentDesc: String,
            onClick: () -> Unit,
        ): AppCompatImageButton {
            return AppCompatImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                    setMargins(marginH, marginV, marginH, marginV)
                }
                minimumHeight = minH
                setBackgroundResource(bgRes)
                setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
                ImageViewCompat.setImageTintList(
                    this,
                    android.content.res.ColorStateList.valueOf(context.getColor(R.color.pos_key_text_primary)),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = contentDesc
                setOnClickListener { onClick() }
            }
        }

        addTextRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addTextRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addTextRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))

        val zRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val shiftBtn = AppCompatImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                setMargins(marginH, marginV, marginH, marginV)
            }
            minimumHeight = minH
            setBackgroundResource(R.drawable.bg_pos_key_special)
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_pos_keyboard_shift))
            ImageViewCompat.setImageTintList(
                this,
                android.content.res.ColorStateList.valueOf(context.getColor(R.color.pos_key_text_primary)),
            )
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Shift"
            setOnClickListener {
                capsOn = !capsOn
                refreshCapsLabels()
                updateShiftButton(this)
            }
        }
        zRow.addView(shiftBtn)

        for (k in listOf("z", "x", "c", "v", "b", "n", "m")) {
            val btn = TextView(context).apply {
                text = if (capsOn) k.uppercase() else k
                tag = k
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
                setTextColor(context.getColor(R.color.pos_key_text_primary))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_pos_key_default)
                minimumHeight = minH
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(marginH, marginV, marginH, marginV)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        500,
                        false,
                    )
                }
                setOnClickListener {
                    val ch = if (capsOn) k.uppercase() else k
                    onInsert(ch)
                }
            }
            letterButtons.add(btn)
            zRow.addView(btn)
        }

        val backBtn = iconButton(
            R.drawable.ic_pos_keyboard_backspace,
            R.drawable.bg_pos_key_special,
            1.15f,
            "Backspace",
        ) { onInsert("⌫") }
        zRow.addView(backBtn)
        panel.addView(zRow)

        when (variant) {
            Variant.EMAIL -> {
                addTextRow(listOf("@", ".", "_", "-", "+"))
                val bottomRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                bottomRow.addView(
                    textAuxKey(context, ".com", 1.4f, minH, marginH, marginV, compactSp) { onInsert(".com") },
                )
                bottomRow.addView(
                    textAuxKey(context, "Space", 1.2f, minH, marginH, marginV, compactSp) { onInsert("SPACE") },
                )
                bottomRow.addView(
                    textAuxKey(context, "⌫", 0.9f, minH, marginH, marginV, labelSp) { onInsert("⌫") },
                )
                panel.addView(bottomRow)
            }
            Variant.GUEST_NAME -> {
                addTextRow(listOf("-", "'", "."))
                val bottomRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                bottomRow.addView(
                    textAuxKey(context, "Space", 2.2f, minH, marginH, marginV, compactSp) { onInsert("SPACE") },
                )
                bottomRow.addView(
                    textAuxKey(context, "⌫", 0.85f, minH, marginH, marginV, labelSp) { onInsert("⌫") },
                )
                panel.addView(bottomRow)
            }
            Variant.MODIFIER_OPTION -> {
                addTextRow(listOf("-", "'", "."))
                val bottomRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                bottomRow.addView(
                    textAuxKey(context, "Space", 2f, minH, marginH, marginV, compactSp) { onInsert("SPACE") },
                )
                val enterWrap = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(marginH, marginV, marginH, marginV)
                    }
                    minimumHeight = minH
                }
                val enterBtn = AppCompatImageButton(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    minimumHeight = minH
                    setBackgroundResource(R.drawable.bg_pos_key_enter)
                    setImageResource(R.drawable.ic_pos_keyboard_return)
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = "Next"
                    setOnClickListener { onEnter?.invoke() }
                }
                enterWrap.addView(enterBtn)
                bottomRow.addView(enterWrap)
                panel.addView(bottomRow)
            }
        }

        return panel
    }

    private fun textAuxKey(
        context: Context,
        label: String,
        weight: Float,
        minH: Int,
        marginH: Int,
        marginV: Int,
        textSp: Float,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTextColor(context.getColor(R.color.pos_key_text_primary))
            background = AppCompatResources.getDrawable(context, R.drawable.bg_pos_key_default)
            minimumHeight = minH
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(marginH, marginV, marginH, marginV)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 500, false)
            }
            setOnClickListener { onClick() }
        }
    }
}
