package com.ernesto.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class DeviceActivationActivity : AppCompatActivity() {

    private var forceLock: Boolean = false
    private var enrollmentRequired: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forceLock = intent.getBooleanExtra(EXTRA_FORCE_LOCK, false)
        enrollmentRequired = intent.getBooleanExtra(EXTRA_ENROLLMENT_REQUIRED, false)

        setContentView(R.layout.activity_device_activation)
        supportActionBar?.hide()

        if (forceLock || enrollmentRequired) {
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { }
                },
            )
        }

        val subtitleRes = when {
            enrollmentRequired -> R.string.device_activation_required_first
            forceLock -> R.string.device_activation_forced_instructions
            else -> R.string.device_activation_instructions
        }

        val composeView = findViewById<ComposeView>(R.id.composeActivation)
        composeView.setContent {
            MaterialTheme {
                ActivationScreen(
                    subtitle = getString(subtitleRes),
                    onSubmit = { code, setLoading, setError ->
                        submitCode(code, setLoading, setError)
                    },
                )
            }
        }
    }

    private fun submitCode(
        code: String,
        setLoading: (Boolean) -> Unit,
        setError: (String?) -> Unit,
    ) {
        setLoading(true)
        setError(null)
        PosDeviceActivation.redeemCode(
            this,
            code,
            onSuccess = {
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(
                        this,
                        R.string.device_activation_success,
                        Toast.LENGTH_LONG,
                    ).show()
                    if (forceLock || enrollmentRequired) {
                        startActivity(
                            Intent(this, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            },
                        )
                    }
                    finish()
                }
            },
            onError = { msg ->
                runOnUiThread {
                    setLoading(false)
                    setError(msg)
                }
            },
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        if (forceLock || enrollmentRequired) return true
        finish()
        return true
    }

    companion object {
        const val EXTRA_FORCE_LOCK = "extra_force_device_activation"
        const val EXTRA_ENROLLMENT_REQUIRED = "extra_enrollment_required"

        fun launchForceLock(context: Context) {
            context.startActivity(
                Intent(context, DeviceActivationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_FORCE_LOCK, true)
                },
            )
        }

        /**
         * Blocks PIN login until a valid dashboard activation code is redeemed
         * ([PosDeviceActivation.FIELD_ENROLLED_FROM_DASHBOARD] on [PosDevices]).
         */
        fun launchEnrollmentRequired(context: Context) {
            context.startActivity(
                Intent(context, DeviceActivationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_FORCE_LOCK, true)
                    putExtra(EXTRA_ENROLLMENT_REQUIRED, true)
                },
            )
        }
    }
}

private const val CODE_LENGTH = 6

@Composable
private fun ActivationScreen(
    subtitle: String,
    onSubmit: (String, (Boolean) -> Unit, (String?) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val doSubmit: () -> Unit = {
        if (!loading && code.length == CODE_LENGTH) {
            onSubmit(code, { loading = it }, { error = it })
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
            text = "Enter activation code",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color(0xFF1A1A2E),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = Color(0xFF6B6B80),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )

        Spacer(modifier = Modifier.height(28.dp))

        ActivationDigitBoxes(code = code, hasError = error != null)

        if (error != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        ActivationKeypad(
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
            onGo = doSubmit,
            goEnabled = !loading && code.length == CODE_LENGTH,
            loading = loading,
        )
    }
}

@Composable
private fun ActivationDigitBoxes(code: String, hasError: Boolean) {
    val borderIdle = Color(0xFFD0D0DC)
    val borderFilled = Color(0xFF6A4FB3)
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
                    .size(width = 48.dp, height = 58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (char != null) borderFilled.copy(alpha = 0.06f)
                        else Color(0xFFF0EDF5),
                    )
                    .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (char != null) {
                    Text(
                        text = char.toString(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1A2E),
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
                            color = Color(0xFF1A1A2E),
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
                    color = Color(0xFF6B6B80),
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
                    color = Color(0xFF1A1A2E),
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
