package com.volt.maximobile

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Matches main POS [com.ernesto.myapplication.DeviceActivationActivity] activation UI (C20 Pro). */
private const val CODE_LENGTH = 6

private val ScreenBg = Color(0xFFF8F7FC)
private val TitleColor = Color(0xFF1A1A2E)
private val SubtitleColor = Color(0xFF6B6B80)
private val DigitBorderIdle = Color(0xFFD0D0DC)
private val DigitBorderActive = Color(0xFF6A4FB3)
private val DigitFillEmpty = Color(0xFFF0EDF5)

@Composable
fun MaxiActivationScreen(
    subtitle: String,
    code: String,
    onCodeChange: (String) -> Unit,
    error: String?,
    busy: Boolean,
    onSubmit: () -> Unit,
) {
    val doSubmit: () -> Unit = {
        if (!busy && code.length == CODE_LENGTH) onSubmit()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Enter activation code",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = TitleColor,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = SubtitleColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )

        Spacer(modifier = Modifier.height(28.dp))

        ActivationDigitBoxes(code = code, hasError = error != null)

        if (error != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        ActivationKeypad(
            onDigit = { digit ->
                if (code.length < CODE_LENGTH) {
                    onCodeChange(code + digit)
                }
            },
            onBackspace = {
                if (code.isNotEmpty()) {
                    onCodeChange(code.dropLast(1))
                }
            },
            onGo = doSubmit,
            goEnabled = !busy && code.length == CODE_LENGTH,
            loading = busy,
        )
    }
}

@Composable
private fun ActivationDigitBoxes(code: String, hasError: Boolean) {
    val borderError = MaterialTheme.colorScheme.error

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(CODE_LENGTH) { index ->
            val char = code.getOrNull(index)
            val isCursor = index == code.length && code.length < CODE_LENGTH

            val targetColor = when {
                hasError -> borderError
                char != null -> DigitBorderActive
                isCursor -> DigitBorderActive
                else -> DigitBorderIdle
            }
            val borderColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(150),
                label = "digitBorder",
            )
            val borderWidth = if (char != null || isCursor) 2.dp else 1.5.dp

            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (char != null) DigitBorderActive.copy(alpha = 0.06f)
                        else DigitFillEmpty,
                    )
                    .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (char != null) {
                    Text(
                        text = char.toString(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = TitleColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivationKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onGo: () -> Unit,
    goEnabled: Boolean,
    loading: Boolean,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(
        modifier = Modifier.widthIn(max = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (digit in row) {
                    ActivationKeypadButton(
                        onClick = { onDigit(digit) },
                        enabled = !loading,
                    ) {
                        Text(
                            text = digit.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TitleColor,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActivationKeypadButton(
                onClick = onBackspace,
                enabled = !loading,
            ) {
                Text(
                    text = "⌫",
                    fontSize = 24.sp,
                    color = SubtitleColor,
                )
            }

            ActivationKeypadButton(
                onClick = { onDigit('0') },
                enabled = !loading,
            ) {
                Text(
                    text = "0",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TitleColor,
                )
            }

            ActivationGoButton(
                onClick = onGo,
                enabled = goEnabled,
                loading = loading,
            )
        }
    }
}

@Composable
private fun ActivationKeypadButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bg = if (pressed) Color(0xFFE8E4F0) else Color.White
    val animBg by animateColorAsState(
        targetValue = bg,
        animationSpec = tween(100),
        label = "keyBg",
    )

    Box(
        modifier = Modifier
            .size(82.dp)
            .clip(CircleShape)
            .background(animBg)
            .border(1.dp, Color(0xFFD0D0DC), CircleShape)
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
private fun ActivationGoButton(
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val enabledBg = Color(0xFF6A4FB3)
    val pressedBg = Color(0xFF5A3FA3)
    val disabledBg = Color(0xFFE0DCE8)

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> disabledBg
            pressed -> pressedBg
            else -> enabledBg
        },
        animationSpec = tween(100),
        label = "goBg",
    )

    val contentColor = if (enabled) Color.White else Color(0xFFA09AB0)

    Box(
        modifier = Modifier
            .size(82.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = Color.White,
            )
        } else {
            Text(
                text = "GO",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}
