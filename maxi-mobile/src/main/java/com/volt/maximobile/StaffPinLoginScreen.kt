package com.volt.maximobile

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * PIN login matching main POS [activity_login.xml] / [LoginActivity] (Landi C20 Pro): purple gradient,
 * merchant title, "Powered by Volt", four dots, circular keypad, back / 0 / check.
 */
private const val PIN_LEN = 4

private val GradientTop = Color(0xFF2A0E5C)
private val GradientBottom = Color(0xFF12002F)
private val KeyBg = Color(0xFF3B1A78)
private val KeyBgPressed = Color(0xFF4D2A8E)
private val ConfirmBg = Color(0xFF6D28D9)
private val ConfirmBgPressed = Color(0xFF7C3AED)
private val SubtitleViolet = Color(0xFF9F7BFF)
private val TaglineColor = Color(0x99FFFFFF)
private val TitleWhite = Color.White
private val DotStroke = Color(0x4DFFFFFF)
private val DotFillInner = Color(0xFF8B5CF6)
private val DotFillRing = Color(0x338B5CF6)

@Composable
fun StaffPinLoginScreen(
    businessTitle: String,
    errorMessage: String?,
    busy: Boolean,
    onClearError: () -> Unit,
    onSubmitPin: (String) -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(errorMessage) {
        val msg = errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        val sequence = listOf(0f, 20f, -20f, 15f, -15f, 8f, -8f, 0f)
        for (i in 1 until sequence.size) {
            offsetX.animateTo(sequence[i], tween(50))
        }
        pin = ""
        delay(400)
        onClearError()
    }

    fun appendDigit(d: Char) {
        if (busy || pin.length >= PIN_LEN) return
        pin += d
        if (pin.length == PIN_LEN) {
            onSubmitPin(pin)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientTop, GradientBottom),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.Center)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x1A8B5CF6), Color.Transparent),
                        center = Offset(with(density) { 180.dp.toPx() }, with(density) { 180.dp.toPx() }),
                        radius = with(density) { 180.dp.toPx() },
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = businessTitle,
                color = TitleWhite,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.login_powered_by),
                color = SubtitleViolet,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.login_tagline),
                color = TaglineColor,
                fontSize = 12.sp,
                letterSpacing = 0.7.sp,
            )

            Spacer(Modifier.height(36.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                repeat(PIN_LEN) { index ->
                    val filled = index < pin.length
                    PinDot(filled = filled)
                }
            }

            Spacer(Modifier.height(40.dp))

            PinKeypad(
                busy = busy,
                onDigit = { appendDigit(it) },
                onBackspace = {
                    if (pin.isNotEmpty() && !busy) {
                        pin = pin.dropLast(1)
                    }
                },
                onConfirm = {
                    if (pin.length == PIN_LEN && !busy) {
                        onSubmitPin(pin)
                    }
                },
            )
        }

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color(0xFFB8A0FF),
                    strokeWidth = 3.dp,
                )
            }
        }
    }
}

@Composable
private fun PinDot(filled: Boolean) {
    if (filled) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(DotFillRing)
                .padding(3.dp)
                .clip(CircleShape)
                .background(DotFillInner),
        )
    } else {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, DotStroke, CircleShape),
        )
    }
}

@Composable
private fun PinKeypad(
    busy: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (d in row) {
                    PinCircleKey(
                        onClick = { onDigit(d) },
                        enabled = !busy,
                    ) {
                        Text(
                            text = d.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = TitleWhite,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PinCircleKey(onClick = onBackspace, enabled = !busy) {
                Text(text = "⌫", fontSize = 22.sp, color = Color(0xFFE0E0E0))
            }
            PinCircleKey(onClick = { onDigit('0') }, enabled = !busy) {
                Text(
                    text = "0",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = TitleWhite,
                )
            }
            PinConfirmKey(onClick = onConfirm, enabled = !busy)
        }
    }
}

@Composable
private fun PinCircleKey(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bg = if (pressed) KeyBgPressed else KeyBg
    val animBg by animateColorAsState(targetValue = bg, animationSpec = tween(100), label = "pinKeyBg")

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(color = animBg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun PinConfirmKey(onClick: () -> Unit, enabled: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bg = if (pressed) ConfirmBgPressed else ConfirmBg
    val animBg by animateColorAsState(targetValue = bg, animationSpec = tween(100), label = "pinConfirmBg")

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(color = animBg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✔", fontSize = 22.sp, color = TitleWhite, fontWeight = FontWeight.Bold)
    }
}
