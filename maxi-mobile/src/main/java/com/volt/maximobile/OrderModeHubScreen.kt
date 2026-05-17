package com.volt.maximobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same color keys as main POS [ColorRegistry] hero tiles. */
private val DineInGreen = Color(0xFF2E7D32)
private val ToGoOrange = Color(0xFFE65100)
private val BarTeal = Color(0xFF00897B)

private val TabHeight = 52.dp
private val TabIconSize = 20.dp
private val TabIconCircle = 30.dp

/**
 * Post-PIN hub: compact **horizontal** order-type control at the top (Dejavoo P8–friendly).
 */
@Composable
fun OrderModeHubScreen(
    onDineIn: () -> Unit,
    onToGo: () -> Unit,
    onBar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Maxi Mobile",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Choose order type",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrderTypeTab(
                label = "DINE IN",
                background = DineInGreen,
                iconRes = R.drawable.ic_dine_in,
                onClick = onDineIn,
                modifier = Modifier.weight(1f),
            )
            OrderTypeTab(
                label = "TO-GO",
                background = ToGoOrange,
                iconRes = R.drawable.ic_to_go,
                onClick = onToGo,
                modifier = Modifier.weight(1f),
            )
            OrderTypeTab(
                label = "BAR",
                background = BarTeal,
                iconRes = R.drawable.ic_bar,
                onClick = onBar,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OrderTypeTab(
    label: String,
    background: Color,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(TabHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(TabIconCircle)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(TabIconSize),
                )
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
