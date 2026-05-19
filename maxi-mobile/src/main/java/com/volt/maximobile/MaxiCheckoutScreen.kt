package com.volt.maximobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volt.shared.engine.DiscountDisplay
import com.volt.shared.engine.OrderTaxBreakdownEntry
import com.volt.shared.engine.OrderTaxCalculator
import com.volt.shared.engine.OrderTaxLine
import com.volt.shared.engine.OrderTaxRule
import java.util.Locale

private val BrandPurple = Color(0xFF5E4085)
private val CreditBlue = Color(0xFF1565C0)
private val DebitTeal = Color(0xFF00695C)
private val CashGreen = Color(0xFF2E7D32)
private val CancelRed = Color(0xFFC62828)
private val SurfaceLight = Color(0xFFF5F6F8)
private val DividerColor = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF757575)
private val CardSurface = Color.White
private val PanelDark = Color(0xFF2D1F4E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxiCheckoutScreen(
    cart: List<CartLine>,
    taxes: List<OrderTaxRule>,
    orderChannelLabel: String,
    busy: Boolean,
    statusMessage: String?,
    creditEnabled: Boolean = true,
    debitEnabled: Boolean = true,
    cashEnabled: Boolean = true,
    onCredit: () -> Unit,
    onDebit: () -> Unit,
    onCash: () -> Unit,
    onBack: () -> Unit,
) {
    val subtotalCents = cart.sumOf { Math.round(it.unitPriceDollars * it.quantity * 100.0) }
    val taxLines = cart.map { line ->
        OrderTaxLine(
            lineKey = line.menuItemId,
            lineTotalDollars = line.unitPriceDollars * line.quantity,
            taxMode = line.taxMode,
            taxIds = line.taxIds,
        )
    }
    val taxBreakdown = OrderTaxCalculator.computeBreakdown(taxLines, taxes)
    val taxTotalCents = OrderTaxCalculator.taxTotalCents(taxBreakdown)
    val totalCents = subtotalCents + taxTotalCents

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceLight),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Checkout", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandPurple,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                OrderSummaryCard(cart, subtotalCents, taxBreakdown, taxTotalCents, totalCents)
                Spacer(Modifier.height(20.dp))
                AmountDueBlock(totalCents)
                Spacer(Modifier.height(24.dp))
                PaymentMethodsSection(
                    creditEnabled = creditEnabled,
                    debitEnabled = debitEnabled,
                    cashEnabled = cashEnabled,
                    busy = busy,
                    onCredit = onCredit,
                    onDebit = onDebit,
                    onCash = onCash,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = DividerColor)
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = CancelRed),
                ) {
                    Text("Cancel", fontSize = 15.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        AnimatedVisibility(
            visible = busy,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.92f)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BrandPurple, strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        statusMessage ?: "Processing payment…",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF424242),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Please present card on terminal",
                        fontSize = 14.sp,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderSummaryCard(
    cart: List<CartLine>,
    subtotalCents: Long,
    taxBreakdown: List<OrderTaxBreakdownEntry>,
    taxTotalCents: Long,
    totalCents: Long,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        color = CardSurface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Order Summary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            for (line in cart) {
                CheckoutCartLineRow(line = line)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(Modifier.height(8.dp))
            SummaryRow("Subtotal", formatCents(subtotalCents))
            for (entry in taxBreakdown) {
                SummaryRow(
                    DiscountDisplay.formatTaxLabel(entry.name, entry.type, entry.rate),
                    formatCents(entry.amountInCents),
                    valueColor = TextSecondary,
                )
            }
            if (taxTotalCents > 0) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = DividerColor)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Total", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        formatCents(totalCents),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckoutCartLineRow(line: CartLine) {
    val lineTotalCents = Math.round(line.unitPriceDollars * line.quantity * 100.0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "${line.quantity}x  ${line.name}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF424242),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatCents(lineTotalCents),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF424242),
            )
        }
        if (line.modifiers.isEmpty()) {
            Text(
                CartLineDisplay.formatMoney(line.basePriceDollars) + " each",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                CartLineDisplay.formatMoney(line.basePriceDollars),
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, start = 8.dp),
            )
            CheckoutModifierRows(line.modifiers, indentLevel = 1)
            Text(
                "Subtotal: ${CartLineDisplay.formatMoney(line.unitPriceDollars)} each",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, start = 8.dp),
            )
        }
    }
}

@Composable
private fun CheckoutModifierRows(modifiers: List<com.volt.shared.data.OrderModifier>, indentLevel: Int) {
    val indent = (8 + indentLevel * 12).dp
    for (mod in modifiers) {
        val label = when (mod.action.trim().uppercase(Locale.US)) {
            "REMOVE" -> "• ${ModifierRemoveDisplay.cartLine(mod.name)}"
            else -> {
                if (mod.price > 0.0) {
                    "• ${mod.name}  +${CartLineDisplay.formatMoney(mod.price)}"
                } else {
                    "• ${mod.name}"
                }
            }
        }
        Text(
            label,
            fontSize = 12.sp,
            color = if (mod.action == "REMOVE") Color(0xFFC62828) else TextSecondary,
            modifier = Modifier.padding(top = 2.dp, start = indent),
        )
        if (mod.children.isNotEmpty()) {
            CheckoutModifierRows(mod.children, indentLevel + 1)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color = Color(0xFF424242)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, color = valueColor)
    }
}

@Composable
private fun AmountDueBlock(totalCents: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelDark)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "AMOUNT DUE",
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                color = Color(0xFFB0A3D4),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatCents(totalCents),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PaymentMethodsSection(
    creditEnabled: Boolean,
    debitEnabled: Boolean,
    cashEnabled: Boolean,
    busy: Boolean,
    onCredit: () -> Unit,
    onDebit: () -> Unit,
    onCash: () -> Unit,
) {
    val anyCard = creditEnabled || debitEnabled
    if (anyCard) {
        SectionLabel("CARD PAYMENTS")
        Spacer(Modifier.height(8.dp))
    }
    if (creditEnabled) {
        PaymentButton(
            label = "Credit",
            color = CreditBlue,
            enabled = !busy,
            onClick = onCredit,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (debitEnabled) {
        PaymentButton(
            label = "Debit",
            color = DebitTeal,
            enabled = !busy,
            onClick = onDebit,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (cashEnabled) {
        Spacer(Modifier.height(6.dp))
        SectionLabel("CASH PAYMENTS")
        Spacer(Modifier.height(8.dp))
        PaymentButton(
            label = "Cash",
            color = CashGreen,
            enabled = !busy,
            onClick = onCash,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = TextSecondary,
    )
}

@Composable
private fun PaymentButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatCents(cents: Long): String {
    val dollars = cents / 100.0
    return String.format(Locale.US, "$%.2f", dollars)
}
