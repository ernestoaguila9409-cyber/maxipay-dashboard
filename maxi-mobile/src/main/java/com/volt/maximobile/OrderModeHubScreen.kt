package com.volt.maximobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same color keys as main POS [ColorRegistry] hero tiles. */
private val DineInGreen = Color(0xFF2E7D32)
private val ToGoOrange = Color(0xFFE65100)
private val BarTeal = Color(0xFF00897B)

/**
 * Portrait-first: three stacked hero tiles with [Modifier.weight] so narrow vertical screens
 * (e.g. Dejavoo P8) get large tap targets like the main POS dashboard order-type row.
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Maxi Mobile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Choose order type",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        OrderHeroTile(
            label = "DINE IN",
            background = DineInGreen,
            iconRes = R.drawable.ic_dine_in,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onClick = onDineIn,
        )
        OrderHeroTile(
            label = "TO-GO",
            background = ToGoOrange,
            iconRes = R.drawable.ic_to_go,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onClick = onToGo,
        )
        OrderHeroTile(
            label = "BAR",
            background = BarTeal,
            iconRes = R.drawable.ic_bar,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onClick = onBar,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun OrderHeroTile(
    label: String,
    background: Color,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
