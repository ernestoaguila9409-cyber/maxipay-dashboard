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
fun TablePickScreen(
    onBack: () -> Unit,
    onPick: (tableId: String, tableName: String) -> Unit,
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
            rows = loadDineInTables(mid)
        } catch (e: Exception) {
            error = e.message ?: "Load failed"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dine in · Select table") },
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

/**
 * Tries `tableLayouts` first (the editor's preferred storage); falls back to the
 * legacy `Tables` collection if no layout exists or no active tables are found there.
 */
private suspend fun loadDineInTables(mid: String): List<Pair<String, String>> {
    val db = FirebaseFirestore.getInstance()
    val merchantDoc = db.collection("Merchants").document(mid)

    val layoutSnap = merchantDoc.collection("tableLayouts").get().await()
    if (!layoutSnap.isEmpty) {
        val layoutDoc = layoutSnap.documents.find { it.getBoolean("isDefault") == true }
            ?: layoutSnap.documents.minByOrNull { it.getLong("sortOrder") ?: 0L }
            ?: layoutSnap.documents.first()
        val tablesSnap = merchantDoc.collection("tableLayouts").document(layoutDoc.id)
            .collection("tables").get().await()
        val layoutRows = tablesSnap.documents.mapNotNull { doc ->
            val isActive: Boolean = when {
                doc.contains("isActive") -> doc.getBoolean("isActive") ?: true
                doc.contains("active") -> doc.getBoolean("active") ?: true
                else -> true
            }
            if (!isActive) return@mapNotNull null
            val area = doc.getString("areaType") ?: "DINING_TABLE"
            if (area == "BAR_SEAT") return@mapNotNull null
            val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
            doc.id to name
        }.sortedBy { it.second.lowercase() }
        if (layoutRows.isNotEmpty()) return layoutRows
    }

    val legacySnap = merchantDoc.collection("tables")
        .whereEqualTo("active", true)
        .get().await()
    return legacySnap.documents.mapNotNull { doc ->
        val area = doc.getString("areaType") ?: "DINING_TABLE"
        if (area == "BAR_SEAT") return@mapNotNull null
        val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
        doc.id to name
    }.sortedBy { it.second.lowercase() }
}
