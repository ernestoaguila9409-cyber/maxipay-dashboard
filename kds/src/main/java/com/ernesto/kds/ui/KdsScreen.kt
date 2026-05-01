package com.ernesto.kds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ernesto.kds.data.KdsDisplaySettings
import com.ernesto.kds.data.KdsTextSettingKey
import com.ernesto.kds.data.KdsTextSettings
import com.ernesto.kds.data.Order
import com.ernesto.kds.data.OrderItem
import com.ernesto.kds.data.OrderModifierLine
import com.ernesto.kds.data.headerColorForOrderType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Avoid static SimpleDateFormat with default locale (stale after runtime locale change). */
private fun formatKitchenTime(date: Date): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)

private val CardCorner = 20.dp
private val OrderCardWidth = 300.dp

/** Preset ARGB colors for modifier line behavior (add vs remove) in KDS settings. */
private val KdsModifierColorSwatches: List<Long> = listOf(
    0xFF212121L,
    0xFF424242L,
    0xFF555555L,
    0xFF1565C0L,
    0xFF1976D2L,
    0xFF2E7D32L,
    0xFF00897BL,
    0xFF6A4FB3L,
    0xFFE65100L,
    0xFFF9A825L,
    0xFFC62828L,
    0xFFAD1457L,
    0xFFFFFFFFL,
)

private fun kdsColorFromArgb(argb: Long): Color = Color((argb and 0xFFFFFFFFL).toInt())

private fun formatOrderTypeLabel(type: String): String {
    return when (type.trim().uppercase()) {
        "DINE_IN" -> "Dine in"
        "TO_GO", "TAKEOUT", "TAKE_OUT" -> "TO-GO"
        "BAR", "BAR_TAB" -> "Bar"
        "UBER_EATS" -> "Uber Eats"
        "ONLINE_PICKUP" -> "Online"
        else -> type.replace('_', ' ').lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

private fun orderHeaderNumber(order: Order): String {
    if (order.orderNumber > 0L) return "#${order.orderNumber}"
    return order.tableName
}

private fun isDineIn(order: Order): Boolean =
    order.orderType.trim().equals("DINE_IN", ignoreCase = true)

/** Table line for Dine In — omit when tableName is only the same as the header order #. */
private fun dineInTableDisplay(order: Order): String? {
    if (!isDineIn(order)) return null
    val t = order.tableName.trim()
    if (t.isEmpty()) return null
    if (order.orderNumber > 0L && t == "#${order.orderNumber}") return null
    return t
}

/** Elapsed: after START use prep anchor; while waiting use last kitchen send so follow-up batches reset the timer. */
private fun urgencyElapsedAnchor(order: Order): Date? =
    when {
        order.isPreparing() && order.kitchenStartedAt != null -> order.kitchenStartedAt
        order.isOpen() -> {
            val batchLatest = order.items.mapNotNull { it.batchSentAt }.maxOfOrNull { it.time }
            when {
                batchLatest != null -> Date(batchLatest)
                else -> order.lastKitchenSentAt ?: order.createdAt
            }
        }
        else -> order.createdAt
    }

/** Elapsed since order createdAt — kitchen-style M:SS or H:MM:SS. */
private fun formatOrderElapsed(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun getUrgencyColor(
    minutes: Int,
    yellowAfter: Int,
    redAfter: Int,
): Color {
    return when {
        minutes >= redAfter -> Color(0xFFFFCDD2)
        minutes >= yellowAfter -> Color(0xFFFFF9C4)
        else -> Color.White
    }
}

private fun headerBarHeight(text: KdsTextSettings): androidx.compose.ui.unit.Dp =
    (text.headerSp * 2.35f).dp.coerceIn(52.dp, 92.dp)

private fun actionButtonHeight(text: KdsTextSettings): androidx.compose.ui.unit.Dp =
    (text.buttonsSp * 2.65f).dp.coerceIn(48.dp, 80.dp)

/** Sample ticket for the settings dialog preview; anchorTimeMs keeps elapsed timer moving. */
private fun previewSampleOrder(anchorTimeMs: Long): Order {
    val createdMs = (anchorTimeMs - 95_000L).coerceAtLeast(0L)
    return Order(
        id = "__kds_preview__",
        tableName = "Table 2",
        customerName = "Alex Kim",
        status = "OPEN",
        createdAt = Date(createdMs),
        lastKitchenSentAt = null,
        kitchenStartedAt = null,
        items = listOf(
            OrderItem(
                name = "Chicken",
                quantity = 1,
                modifierLines = listOf(
                    OrderModifierLine("No lettuce", isRemove = true),
                    OrderModifierLine("Add cheese", isRemove = false),
                ),
                itemId = "",
            ),
            OrderItem(
                name = "French fries",
                quantity = 2,
                modifierLines = emptyList(),
                itemId = "",
            ),
        ),
        orderType = "DINE_IN",
        orderNumber = 294L,
    )
}

@Composable
private fun rememberNowTicker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun KdsScreen(viewModel: KdsViewModel) {
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val dashboardColorKeys by viewModel.dashboardColorKeys.collectAsStateWithLifecycle()
    val kdsDisplay by viewModel.kdsDisplaySettings.collectAsStateWithLifecycle()
    val textSettings by viewModel.textSettings.collectAsStateWithLifecycle()
    val nowMs = rememberNowTicker()
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Kitchen display",
                fontSize = textSettings.headerSp.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "KDS settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size((textSettings.headerSp * 1.1f).dp.coerceIn(24.dp, 40.dp)),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (orders.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No active orders",
                    fontSize = textSettings.itemNameSp.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF888888),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(end = 24.dp),
                ) {
                    items(
                        items = orders,
                        key = { it.cardKey },
                    ) { order ->
                        OrderKdsCard(
                            modifier = Modifier
                                .width(OrderCardWidth)
                                .fillMaxHeight(),
                            order = order,
                            textSettings = textSettings,
                            moduleColorKeys = dashboardColorKeys,
                            showTimers = kdsDisplay.showTimers,
                            ticketYellowAfterMinutes = kdsDisplay.ticketYellowAfterMinutes,
                            ticketRedAfterMinutes = kdsDisplay.ticketRedAfterMinutes,
                            nowMs = nowMs,
                            onStart = { viewModel.markAsPreparing(order) },
                            onReady = { viewModel.markAsReady(order) },
                            onMarkItemReady = { item -> viewModel.markItemReady(order, item) },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        KdsSettingsDialog(
            textSettings = textSettings,
            dashboardColorKeys = dashboardColorKeys,
            kdsDisplay = kdsDisplay,
            onDismiss = { showSettings = false },
            onAdjust = { key, delta -> viewModel.adjustTextSetting(key, delta) },
            onSetModifierAddColor = { viewModel.setModifierAddColor(it) },
            onSetModifierRemoveColor = { viewModel.setModifierRemoveColor(it) },
        )
    }
}

@Composable
private fun KdsSettingsDialog(
    textSettings: KdsTextSettings,
    dashboardColorKeys: Map<String, String>,
    kdsDisplay: KdsDisplaySettings,
    onDismiss: () -> Unit,
    onAdjust: (KdsTextSettingKey, Int) -> Unit,
    onSetModifierAddColor: (Long) -> Unit,
    onSetModifierRemoveColor: (Long) -> Unit,
) {
    val scroll = rememberScrollState()
    val nowMs = rememberNowTicker()
    val previewOrder = previewSampleOrder(nowMs)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = "KDS Settings",
                    fontSize = textSettings.headerSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TextSizeRow(
                            label = "Header",
                            valueSp = textSettings.headerSp,
                            settingKey = KdsTextSettingKey.Header,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                        )
                        TextSizeRow(
                            label = "Table info",
                            valueSp = textSettings.tableInfoSp,
                            settingKey = KdsTextSettingKey.TableInfo,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                        )
                        TextSizeRow(
                            label = "Customer name",
                            valueSp = textSettings.customerNameSp,
                            settingKey = KdsTextSettingKey.CustomerName,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                        )
                        TextSizeRow(
                            label = "Timer",
                            valueSp = textSettings.timerSp,
                            settingKey = KdsTextSettingKey.Timer,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                        )
                        TextSizeRow(
                            label = "Item name",
                            valueSp = textSettings.itemNameSp,
                            settingKey = KdsTextSettingKey.ItemName,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                        )
                        TextSizeRow(
                            label = "Modifiers",
                            valueSp = textSettings.modifiersSp,
                            settingKey = KdsTextSettingKey.Modifiers,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                            maxSp = KdsTextSettings.MAX_SP_MODIFIERS,
                        )
                        ModifierBehaviorColorsSection(
                            textSettings = textSettings,
                            onPickAdd = onSetModifierAddColor,
                            onPickRemove = onSetModifierRemoveColor,
                        )
                        TextSizeRow(
                            label = "Buttons",
                            valueSp = textSettings.buttonsSp,
                            settingKey = KdsTextSettingKey.Buttons,
                            textSettings = textSettings,
                            onAdjust = onAdjust,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .width(OrderCardWidth)
                            .fillMaxHeight(),
                    ) {
                        Text(
                            text = "Live preview",
                            fontSize = textSettings.tableInfoSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            OrderKdsCard(
                                modifier = Modifier.fillMaxSize(),
                                order = previewOrder,
                                textSettings = textSettings,
                                moduleColorKeys = dashboardColorKeys,
                                showTimers = kdsDisplay.showTimers,
                                ticketYellowAfterMinutes = kdsDisplay.ticketYellowAfterMinutes,
                                ticketRedAfterMinutes = kdsDisplay.ticketRedAfterMinutes,
                                nowMs = nowMs,
                                onStart = {},
                                onReady = {},
                                onMarkItemReady = {},
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Done",
                            fontSize = textSettings.buttonsSp.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextSizeRow(
    label: String,
    valueSp: Float,
    settingKey: KdsTextSettingKey,
    textSettings: KdsTextSettings,
    onAdjust: (KdsTextSettingKey, Int) -> Unit,
    maxSp: Float = KdsTextSettings.MAX_SP,
) {
    val atMin = valueSp <= KdsTextSettings.MIN_SP
    val atMax = valueSp >= maxSp
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = textSettings.tableInfoSp.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${valueSp.toInt()} sp",
            modifier = Modifier.padding(end = 8.dp),
            fontSize = textSettings.tableInfoSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = { onAdjust(settingKey, -1) },
            enabled = !atMin,
            modifier = Modifier
                .widthIn(min = 44.dp)
                .heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = "−",
                fontSize = textSettings.buttonsSp.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = { onAdjust(settingKey, 1) },
            enabled = !atMax,
            modifier = Modifier
                .widthIn(min = 44.dp)
                .heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = "+",
                fontSize = textSettings.buttonsSp.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ModifierBehaviorColorsSection(
    textSettings: KdsTextSettings,
    onPickAdd: (Long) -> Unit,
    onPickRemove: (Long) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Modifier colors",
            fontSize = textSettings.tableInfoSp.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Add (options & extras)",
            fontSize = textSettings.tableInfoSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModifierColorSwatchRow(
            selectedArgb = textSettings.modifierAddColorArgb,
            onPick = onPickAdd,
        )
        Text(
            text = "Remove (hold / No …)",
            fontSize = textSettings.tableInfoSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModifierColorSwatchRow(
            selectedArgb = textSettings.modifierRemoveColorArgb,
            onPick = onPickRemove,
        )
    }
}

@Composable
private fun ModifierColorSwatchRow(
    selectedArgb: Long,
    onPick: (Long) -> Unit,
) {
    val hScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (argb in KdsModifierColorSwatches) {
            val selected = (argb and 0xFFFFFFFFL) == (selectedArgb and 0xFFFFFFFFL)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color(0x33000000)
                        },
                        shape = CircleShape,
                    )
                    .background(kdsColorFromArgb(argb))
                    .clickable { onPick(argb) },
            )
        }
    }
}

@Composable
private fun OrderKdsCard(
    modifier: Modifier = Modifier,
    order: Order,
    textSettings: KdsTextSettings,
    moduleColorKeys: Map<String, String>,
    showTimers: Boolean,
    ticketYellowAfterMinutes: Int,
    ticketRedAfterMinutes: Int,
    nowMs: Long,
    onStart: () -> Unit,
    onReady: () -> Unit,
    onMarkItemReady: (OrderItem) -> Unit,
) {
    val headerShape = RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner)
    val headerColor = headerColorForOrderType(order.orderType, moduleColorKeys)
    val bodyScroll = rememberScrollState()
    val headerH = headerBarHeight(textSettings)
    val btnH = actionButtonHeight(textSettings)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CardCorner),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerH)
                    .clip(headerShape)
                    .background(headerColor),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatOrderTypeLabel(order.orderType),
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = textSettings.headerSp.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = orderHeaderNumber(order),
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = textSettings.headerSp.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = order.createdAt?.let { formatKitchenTime(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = textSettings.headerSp.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                }
            }

            val anchor = urgencyElapsedAnchor(order)
            val elapsedMinutes = anchor?.let { t ->
                ((nowMs - t.time) / 60_000L).toInt().coerceAtLeast(0)
            } ?: 0
            val urgencyBackground = getUrgencyColor(
                elapsedMinutes,
                ticketYellowAfterMinutes,
                ticketRedAfterMinutes,
            )
            val bodyShape = RoundedCornerShape(bottomStart = CardCorner, bottomEnd = CardCorner)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(bodyShape)
                    .background(urgencyBackground),
            ) {
                val tableLine = dineInTableDisplay(order)
                val customerLine = order.customerName.trim().takeIf { it.isNotEmpty() }
                val showCustomerLine =
                    customerLine != null && (isDineIn(order) || order.isOnlineOrder)
                if (tableLine != null || showCustomerLine) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        tableLine?.let { name ->
                            Text(
                                text = "Table · $name",
                                fontSize = textSettings.tableInfoSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF37474F),
                            )
                        }
                        if (showCustomerLine) {
                            Text(
                                text = "Customer · $customerLine",
                                fontSize = textSettings.customerNameSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF37474F),
                                modifier = Modifier.padding(
                                    top = if (tableLine != null) 4.dp else 0.dp,
                                ),
                            )
                        }
                    }
                }

                if (showTimers && anchor != null) {
                    val elapsed = (nowMs - anchor.time).coerceAtLeast(0L)
                    val warnColor = when {
                        elapsed >= 30 * 60 * 1000L -> Color(0xFFC62828)
                        elapsed >= 15 * 60 * 1000L -> Color(0xFFE65100)
                        else -> Color(0xFF424242)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Elapsed",
                            fontSize = textSettings.timerSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF64748B),
                        )
                        Text(
                            text = formatOrderElapsed(elapsed),
                            fontSize = textSettings.timerSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = warnColor,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(bodyScroll)
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp),
                    ) {
                        order.items.forEachIndexed { index, item ->
                            KdsItemRow(
                                item = item,
                                textSettings = textSettings,
                                swipeEnabled = order.isPreparing(),
                                onSwipedReady = { onMarkItemReady(item) },
                                rowKey = "${order.cardKey}|${item.lineDocId}|${item.kdsBatchId}|$index",
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    ) {
                        when {
                            order.isOpen() -> {
                                Button(
                                    onClick = onStart,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(btnH),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1565C0),
                                    ),
                                    contentPadding = PaddingValues(
                                        vertical = (textSettings.buttonsSp * 0.45f).dp.coerceIn(8.dp, 20.dp),
                                    ),
                                ) {
                                    Text(
                                        text = "START",
                                        fontSize = textSettings.buttonsSp.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            order.isPreparing() -> {
                                Button(
                                    onClick = onReady,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(btnH),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                    ),
                                    contentPadding = PaddingValues(
                                        vertical = (textSettings.buttonsSp * 0.45f).dp.coerceIn(8.dp, 20.dp),
                                    ),
                                ) {
                                    Text(
                                        text = "READY",
                                        fontSize = textSettings.buttonsSp.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KdsItemRow(
    item: OrderItem,
    textSettings: KdsTextSettings,
    swipeEnabled: Boolean,
    onSwipedReady: () -> Unit,
    rowKey: String,
) {
    val itemSp = textSettings.itemNameSp
    val modSp = textSettings.modifiersSp
    val bottomGap = (modSp * 0.35f).dp.coerceIn(8.dp, 16.dp)

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = "${item.displayQuantity}x ${item.name}",
                modifier = Modifier.fillMaxWidth(),
                fontSize = itemSp.sp,
                lineHeight = (itemSp * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF212121),
            )
            item.modifierLines.forEach { line ->
                Text(
                    text = "  • ${line.text}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 8.dp,
                            top = (modSp * 0.28f).dp.coerceIn(3.dp, 12.dp),
                        ),
                    fontSize = modSp.sp,
                    lineHeight = (modSp * 1.45f).sp,
                    fontWeight = FontWeight.Normal,
                    color = if (line.isRemove) {
                        kdsColorFromArgb(textSettings.modifierRemoveColorArgb)
                    } else {
                        kdsColorFromArgb(textSettings.modifierAddColorArgb)
                    },
                )
            }
        }
    }

    if (!swipeEnabled) {
        content()
        Spacer(modifier = Modifier.height(bottomGap))
        return
    }

    androidx.compose.runtime.key(rowKey) {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.StartToEnd) {
                    onSwipedReady()
                    true
                } else {
                    false
                }
            },
            positionalThreshold = { totalDistance -> totalDistance * 0.45f },
        )
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2E7D32))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "READY",
                        color = Color.White,
                        fontSize = textSettings.buttonsSp.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            content = { content() },
        )
    }
    Spacer(modifier = Modifier.height(bottomGap))
}
