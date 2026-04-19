package com.ernesto.kds.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ernesto.kds.data.KdsPairingRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val CODE_LENGTH = 6

@Composable
fun PairingScreen(
    repository: KdsPairingRepository,
    onPaired: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val onConnect: () -> Unit = {
        if (!loading && code.length == CODE_LENGTH) {
            error = null
            scope.launch {
                loading = true
                try {
                    val auth = FirebaseAuth.getInstance()
                    if (auth.currentUser == null) {
                        auth.signInAnonymously().await()
                    }
                    val result = repository.pairWithCode(code)
                    result.fold(
                        onSuccess = { id -> onPaired(id) },
                        onFailure = { e ->
                            error = when (e.message) {
                                "Invalid code" -> "Invalid code"
                                "Code already used" -> "This code was already used"
                                "Enter all 6 digits" -> "Enter all 6 digits"
                                else -> "Could not connect. Try again."
                            }
                        },
                    )
                } catch (_: Exception) {
                    error = "Could not connect. Try again."
                } finally {
                    loading = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Enter pairing code",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Use the 6-digit code from the KDS settings page in your dashboard.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )

        Spacer(modifier = Modifier.height(28.dp))

        DigitBoxes(code = code, hasError = error != null)

        if (error != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        NumericKeypad(
            onDigit = { digit ->
                if (code.length < CODE_LENGTH) {
                    code += digit
                    error = null
                }
            },
            onBackspace = {
                if (code.isNotEmpty()) {
                    code = code.dropLast(1)
                    error = null
                }
            },
            onConnect = onConnect,
            connectEnabled = !loading && code.length == CODE_LENGTH,
            loading = loading,
        )
    }
}

@Composable
private fun DigitBoxes(code: String, hasError: Boolean) {
    val borderIdle = MaterialTheme.colorScheme.outlineVariant
    val borderFilled = MaterialTheme.colorScheme.primary
    val borderError = MaterialTheme.colorScheme.error

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(CODE_LENGTH) { index ->
            val char = code.getOrNull(index)
            val isCursor = index == code.length && code.length < CODE_LENGTH

            val targetColor = when {
                hasError -> borderError
                char != null -> borderFilled
                isCursor -> borderFilled
                else -> borderIdle
            }
            val borderColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(150),
                label = "digitBorder",
            )
            val borderWidth = if (char != null || isCursor) 2.dp else 1.5.dp

            Box(
                modifier = Modifier
                    .size(width = 52.dp, height = 62.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (char != null) borderFilled.copy(alpha = 0.06f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    )
                    .border(borderWidth, borderColor, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (char != null) {
                    Text(
                        text = char.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConnect: () -> Unit,
    connectEnabled: Boolean,
    loading: Boolean,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(
        modifier = Modifier.widthIn(max = 340.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (digit in row) {
                    KeypadButton(
                        onClick = { onDigit(digit) },
                        enabled = !loading,
                    ) {
                        Text(
                            text = digit.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeypadButton(
                onClick = onBackspace,
                enabled = !loading,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Delete",
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            KeypadButton(
                onClick = { onDigit('0') },
                enabled = !loading,
            ) {
                Text(
                    text = "0",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            ConnectButton(
                onClick = onConnect,
                enabled = connectEnabled,
                loading = loading,
            )
        }
    }
}

@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bg = if (pressed) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    val animBg by animateColorAsState(
        targetValue = bg,
        animationSpec = tween(100),
        label = "keyBg",
    )

    Box(
        modifier = Modifier
            .size(90.dp)
            .clip(CircleShape)
            .background(animBg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
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
private fun ConnectButton(
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val enabledBg = MaterialTheme.colorScheme.primary
    val pressedBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val disabledBg = MaterialTheme.colorScheme.surfaceVariant

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> disabledBg
            pressed -> pressedBg
            else -> enabledBg
        },
        animationSpec = tween(100),
        label = "connectBg",
    )

    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .size(90.dp)
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
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                text = "GO",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}
