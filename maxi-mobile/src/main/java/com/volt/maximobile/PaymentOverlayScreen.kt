package com.volt.maximobile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val MaxiPurple = Color(0xFF12002F)
private val SuccessGreen = Color(0xFF2E7D32)
private val FailureRed = Color(0xFFC62828)

enum class PaymentPhase {
    CREATING_ORDER,
    WAITING_FOR_CARD,
    RECORDING_PAYMENT,
    SUCCESS,
    FAILED,
}

@Composable
fun PaymentOverlayScreen(
    phase: PaymentPhase,
    totalDollars: Double,
    paymentType: String,
    errorMessage: String? = null,
    onDone: () -> Unit,
    onPrintReceipt: (() -> Unit)? = null,
) {
    val bgColor = when (phase) {
        PaymentPhase.SUCCESS -> SuccessGreen
        PaymentPhase.FAILED -> FailureRed
        else -> MaxiPurple
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            when (phase) {
                PaymentPhase.CREATING_ORDER -> ProcessingContent(
                    title = "Creating Order",
                    subtitle = "Please wait...",
                    amount = totalDollars,
                    paymentType = paymentType,
                )
                PaymentPhase.WAITING_FOR_CARD -> ProcessingContent(
                    title = "Tap or Insert Card",
                    subtitle = "Waiting for payment...",
                    amount = totalDollars,
                    paymentType = paymentType,
                )
                PaymentPhase.RECORDING_PAYMENT -> ProcessingContent(
                    title = "Finalizing",
                    subtitle = "Recording payment...",
                    amount = totalDollars,
                    paymentType = paymentType,
                )
                PaymentPhase.SUCCESS -> ResultContent(
                    icon = "\u2713",
                    title = "Payment Approved",
                    amount = totalDollars,
                    paymentType = paymentType,
                    onDone = onDone,
                    isSuccess = true,
                    onPrintReceipt = onPrintReceipt,
                )
                PaymentPhase.FAILED -> ResultContent(
                    icon = "\u2717",
                    title = "Payment Failed",
                    amount = totalDollars,
                    paymentType = paymentType,
                    subtitle = errorMessage,
                    onDone = onDone,
                    isSuccess = false,
                )
            }
        }
    }
}

@Composable
private fun ProcessingContent(
    title: String,
    subtitle: String,
    amount: Double,
    paymentType: String,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Text(
        text = "Maxi Mobile",
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )

    Spacer(Modifier.height(24.dp))

    Text(
        text = String.format(Locale.US, "$%.2f", amount),
        color = Color.White,
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = paymentType.uppercase(Locale.US),
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    )

    Spacer(Modifier.height(32.dp))

    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = Color.White,
        strokeWidth = 3.dp,
    )

    Spacer(Modifier.height(24.dp))

    Text(
        text = title,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.alpha(alpha),
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = subtitle,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 14.sp,
    )
}

@Composable
private fun ResultContent(
    icon: String,
    title: String,
    amount: Double,
    paymentType: String,
    subtitle: String? = null,
    onDone: () -> Unit,
    isSuccess: Boolean,
    onPrintReceipt: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(Color.White.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    Spacer(Modifier.height(24.dp))

    Text(
        text = title,
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )

    Spacer(Modifier.height(12.dp))

    Text(
        text = String.format(Locale.US, "$%.2f", amount),
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = paymentType.uppercase(Locale.US),
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 14.sp,
        letterSpacing = 1.sp,
    )

    if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(32.dp))

    if (isSuccess && onPrintReceipt != null) {
        Row {
            Button(
                onClick = onPrintReceipt,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = "Print Receipt",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = SuccessGreen,
                ),
            ) {
                Text(
                    text = "Done",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    } else {
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = if (isSuccess) SuccessGreen else FailureRed,
            ),
        ) {
            Text(
                text = if (isSuccess) "Done" else "Back to Menu",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
}
