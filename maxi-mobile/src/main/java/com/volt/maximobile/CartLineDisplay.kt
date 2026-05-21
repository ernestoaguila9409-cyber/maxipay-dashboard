package com.volt.maximobile

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.volt.shared.data.OrderModifier
import java.util.Locale

object CartLineDisplay {

    fun formatMoney(amount: Double): String =
        "$${String.format(Locale.US, "%.2f", amount)}"

    fun appendModifierRows(
        container: LinearLayout,
        modifiers: List<OrderModifier>,
        context: Context,
        indentLevel: Int = 0,
    ) {
        val density = context.resources.displayMetrics.density
        val indentPx = (12 * indentLevel * density).toInt()
        val padTop = (2 * density).toInt()
        val secondary = ContextCompat.getColor(context, R.color.order_text_secondary)
        val removeColor = Color.parseColor("#C62828")

        for (mod in modifiers) {
            val label = when (mod.action.trim().uppercase(Locale.US)) {
                "REMOVE" -> "• ${ModifierRemoveDisplay.cartLine(mod.name)}"
                else -> {
                    if (mod.price > 0.0) {
                        context.getString(
                            R.string.order_cart_modifier_add,
                            mod.name,
                            formatMoney(mod.price),
                        )
                    } else {
                        "• ${mod.name}"
                    }
                }
            }
            container.addView(
                TextView(context).apply {
                    text = label
                    textSize = 13f
                    setTextColor(if (mod.action == "REMOVE") removeColor else secondary)
                    setPadding(indentPx, padTop, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
            if (mod.children.isNotEmpty()) {
                appendModifierRows(container, mod.children, context, indentLevel + 1)
            }
        }
    }

    fun appendDetailRow(
        container: LinearLayout,
        context: Context,
        text: String,
        bold: Boolean = false,
    ) {
        val density = context.resources.displayMetrics.density
        val secondary = ContextCompat.getColor(context, R.color.order_text_secondary)
        container.addView(
            TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(secondary)
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, (2 * density).toInt(), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
    }

    fun bindCartLineDetails(container: LinearLayout, line: CartLine, context: Context) {
        container.removeAllViews()
        if (line.modifiers.isEmpty()) {
            appendDetailRow(
                container,
                context,
                context.getString(R.string.order_cart_unit_price, formatMoney(line.basePriceDollars)),
            )
        } else {
            appendDetailRow(container, context, formatMoney(line.basePriceDollars))
            appendModifierRows(container, line.modifiers, context)
            appendDetailRow(
                container,
                context,
                context.getString(
                    R.string.order_cart_line_subtotal_each,
                    formatMoney(line.unitPriceDollars),
                ),
                bold = true,
            )
        }
    }
}
