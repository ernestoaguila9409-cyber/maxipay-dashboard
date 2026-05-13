package com.volt.maximobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarSeatPickScreen(
    onBack: () -> Unit,
    onPick: (seatTableId: String, seatName: String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            val mid = PosDeviceIdentity.getMerchantId(context).trim()
            if (mid.isEmpty()) {
                error = "Not signed in"
                loading = false
                return@LaunchedEffect
            }
            MerchantFirestore.init(mid)
            val snap = FirebaseFirestore.getInstance()
                .collection("Merchants")
                .document(mid)
                .collection("tables")
                .whereEqualTo("active", true)
                .whereEqualTo("areaType", "BAR_SEAT")
                .get()
                .await()
            rows = snap.documents.map { doc ->
                val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
                doc.id to name
            }.sortedWith(compareBy { (_, name) ->
                Regex("(\\d+)").find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE
            })
        } catch (e: Exception) {
            error = e.message ?: "Load failed"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bar · Select seat") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.padding(8.dp))
                }
                if (rows.isEmpty()) {
                    Text(
                        "No bar seats configured (Tables with areaType BAR_SEAT).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.first }) { (id, name) ->
                        Column {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(id, name) }
                                    .padding(vertical = 16.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
