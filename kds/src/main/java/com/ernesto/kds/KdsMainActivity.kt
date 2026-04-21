package com.ernesto.kds

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.ernesto.kds.data.HeartbeatOutcome
import com.ernesto.kds.data.KdsDevicePrefs
import com.ernesto.kds.data.KdsDevicePresence
import com.ernesto.kds.data.KdsPairingRepository
import com.ernesto.kds.ui.KdsScreen
import com.ernesto.kds.ui.KdsViewModel
import com.ernesto.kds.ui.PairingScreen
import com.ernesto.kds.ui.theme.MyApplicationTheme
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class KdsMainActivity : ComponentActivity() {

    private val viewModel: KdsViewModel by viewModels {
        KdsViewModel.Factory(application)
    }
    private val devicePresence by lazy { KdsDevicePresence(applicationContext) }
    private val pairingRepository by lazy { KdsPairingRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                runCatching { auth.signInAnonymously().await() }
            }
        }
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KdsAppEntry(
                        viewModel = viewModel,
                        devicePresence = devicePresence,
                        pairingRepository = pairingRepository,
                    )
                }
            }
        }
    }
}

@Composable
private fun KdsAppEntry(
    viewModel: KdsViewModel,
    devicePresence: KdsDevicePresence,
    pairingRepository: KdsPairingRepository,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(KdsDevicePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var pairedDeviceId by remember {
        mutableStateOf(prefs.getString(KdsDevicePrefs.KEY_DEVICE_DOC_ID, "").orEmpty())
    }

    LaunchedEffect(pairedDeviceId) {
        if (pairedDeviceId.isBlank()) return@LaunchedEffect
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            runCatching { auth.signInAnonymously().await() }
        }
        while (isActive) {
            when (devicePresence.heartbeatOnce(pairedDeviceId)) {
                HeartbeatOutcome.RevokedOrDeleted -> {
                    KdsDevicePrefs.clearPairedDevice(prefs)
                    pairedDeviceId = ""
                    return@LaunchedEffect
                }
                HeartbeatOutcome.NoDeviceConfigured -> {
                    // Prefs can lag right after pair; keep polling while UI still shows paired.
                    if (pairedDeviceId.isBlank()) return@LaunchedEffect
                    delay(500L)
                }
                HeartbeatOutcome.Ok,
                HeartbeatOutcome.TransientFailure,
                -> delay(5_000L)
            }
        }
    }

    if (pairedDeviceId.isBlank()) {
        PairingScreen(
            repository = pairingRepository,
            onPaired = { id -> pairedDeviceId = id },
        )
    } else {
        LaunchedEffect(pairedDeviceId) {
            viewModel.bindDeviceDocId(pairedDeviceId)
        }
        KdsScreen(viewModel = viewModel)
    }
}
