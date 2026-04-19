package com.ernesto.myapplication.ui.kds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ernesto.myapplication.OrderLineKdsStatus
import java.util.Locale

private data class KdsStatusVisual(
    val emoji: String,
    val color: Color,
    val toastMessage: String,
)

private fun visualForStatus(raw: String?): KdsStatusVisual? {
    val s = raw?.trim()?.uppercase(Locale.US).orEmpty()
    return when (s) {
        OrderLineKdsStatus.SENT -> KdsStatusVisual(
            emoji = "⏱️",
            color = Color(0xFFFFC107),
            toastMessage = "Sent to kitchen",
        )
        OrderLineKdsStatus.PREPARING -> KdsStatusVisual(
            emoji = "🔥",
            color = Color(0xFF1976D2),
            toastMessage = "Preparing",
        )
        OrderLineKdsStatus.READY -> KdsStatusVisual(
            emoji = "✅",
            color = Color(0xFF2E7D32),
            toastMessage = "Ready",
        )
        else -> null
    }
}

fun hasKdsStatusIndicator(status: String?): Boolean = visualForStatus(status) != null

/**
 * Symbol-only KDS line status for Order Details (emoji + color).
 * Tap or long-press triggers [onStatusExplain] with a short phrase for Toast/snackbar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KdsStatusIcon(
    status: String?,
    onStatusExplain: ((String) -> Unit)? = null,
) {
    val visual = visualForStatus(status)
    if (visual == null) {
        Box(Modifier.size(0.dp))
        return
    }
    Text(
        text = visual.emoji,
        fontSize = 19.sp,
        color = visual.color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(28.dp)
            .combinedClickable(
                onClick = { onStatusExplain?.invoke(visual.toastMessage) },
                onLongClick = { onStatusExplain?.invoke(visual.toastMessage) },
            ),
    )
}
