package com.volt.maximobile

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.volt.maximobile.engine.PaymentEngine
import com.volt.maximobile.spin.SpinClient
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private enum class Screen { Activation, Pin, Menu }

private data class CatUi(val id: String, val name: String)
private data class ItemUi(val id: String, val name: String, val categoryId: String, val priceDollars: Double)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MaxiRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxiRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember {
        val mid = PosDeviceIdentity.getMerchantId(context)
        mutableStateOf(if (mid.isNotBlank()) Screen.Pin else Screen.Activation)
    }
    var activationCode by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var employeeName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var categories by remember { mutableStateOf<List<CatUi>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<ItemUi>>(emptyList()) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var payDialog by remember { mutableStateOf(false) }

    fun loadCategories() {
        scope.launch {
            busy = true
            err = null
            try {
                val db = FirebaseFirestore.getInstance()
                val mid = PosDeviceIdentity.getMerchantId(context).trim()
                if (mid.isEmpty()) throw IllegalStateException("Merchant ID missing")
                MerchantFirestore.init(mid)
                val base = db.collection("Merchants").document(mid).collection("categories")
                var snap = base.whereArrayContains("availableOrderTypes", "TO_GO").get().await()
                if (snap.isEmpty) {
                    snap = base.get().await()
                }
                val list = snap.documents.mapNotNull { d -> parseCategory(d) }
                    .sortedBy { it.name.lowercase(Locale.US) }
                categories = list
                selectedCategoryId = list.firstOrNull()?.id
                if (selectedCategoryId != null) {
                    items = loadItemsForCategory(db, mid, selectedCategoryId!!)
                } else {
                    items = emptyList()
                }
            } catch (e: Exception) {
                err = e.message ?: "Load failed"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(screen) {
        if (screen == Screen.Menu) loadCategories()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maxi Mobile · To Go") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            when (screen) {
                Screen.Activation -> {
                    Text(stringResource(R.string.device_activation_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.device_activation_instructions))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = activationCode,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(6)
                            activationCode = digits
                        },
                        label = { Text(stringResource(R.string.device_activation_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            busy = true
                            err = null
                            PosDeviceActivation.redeemCode(
                                context,
                                activationCode,
                                onSuccess = {
                                    activationCode = ""
                                    err = null
                                    screen = Screen.Pin
                                    busy = false
                                },
                                onError = { msg ->
                                    err = msg
                                    busy = false
                                },
                            )
                        },
                        enabled = !busy && activationCode.length == 6,
                    ) { Text(stringResource(R.string.device_activation_submit)) }
                }

                Screen.Pin -> {
                    Text("Staff PIN")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 8) pin = it.filter { ch -> ch.isDigit() } },
                        label = { Text("PIN") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                err = null
                                try {
                                    val mid = PosDeviceIdentity.getMerchantId(context).trim()
                                    val data = hashMapOf<String, Any>("pin" to pin, "merchantId" to mid)
                                    val res = FirebaseFunctions.getInstance()
                                        .getHttpsCallable("verifyPin")
                                        .call(data)
                                        .await()
                                    @Suppress("UNCHECKED_CAST")
                                    val map = res.data as? Map<String, Any?> ?: emptyMap()
                                    if (map["success"] as? Boolean != true) {
                                        err = "Invalid PIN"
                                        return@launch
                                    }
                                    employeeName = map["name"] as? String ?: "Staff"
                                    MerchantFirestore.init(mid)
                                    pin = ""
                                    screen = Screen.Menu
                                } catch (e: Exception) {
                                    err = e.message ?: "Login failed"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && pin.length >= 4,
                    ) { Text("Sign in") }
                }

                Screen.Menu -> {
                    if (busy && categories.isEmpty()) {
                        CircularProgressIndicator()
                        return@Column
                    }
                    Text("Categories", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(categories) { c ->
                            val sel = c.id == selectedCategoryId
                            Text(
                                c.name + if (sel) "  ◀" else "",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCategoryId = c.id
                                        scope.launch {
                                            busy = true
                                            try {
                                                val db = FirebaseFirestore.getInstance()
                                                val mid = PosDeviceIdentity.getMerchantId(context).trim()
                                                items = loadItemsForCategory(db, mid, c.id)
                                            } catch (e: Exception) {
                                                err = e.message
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                    Text("Items (tap to add)", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxWidth(),
                    ) {
                        items(items) { it ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        cart.add(
                                            CartLine(
                                                menuItemId = it.id,
                                                name = it.name,
                                                unitPriceDollars = it.priceDollars,
                                                quantity = 1,
                                            ),
                                        )
                                        Toast.makeText(context, "Added ${it.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(it.name, modifier = Modifier.weight(1f))
                                Text("$${String.format(Locale.US, "%.2f", it.priceDollars)}")
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Cart: ${cart.size} line(s)", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = {
                            if (cart.isEmpty()) {
                                err = "Cart is empty"
                                return@Button
                            }
                            if (!SpinClient.spinConfigured()) {
                                err = "SPIn not configured. Add maxi-mobile/secrets.properties and rebuild."
                                return@Button
                            }
                            payDialog = true
                        },
                        enabled = !busy && cart.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Checkout & pay") }
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }

    if (payDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) payDialog = false },
            title = { Text("Card type") },
            text = { Text("Creates the To Go order, runs SPIn Sale on the terminal, then records payment.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val lines = cart.toList()
                                checkoutToGo(
                                    employeeName = employeeName,
                                    lines = lines,
                                    paymentType = "Credit",
                                )
                                cart.clear()
                                Toast.makeText(context, "Payment complete", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Checkout failed", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                                payDialog = false
                            }
                        }
                    },
                ) { Text("Credit") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val lines = cart.toList()
                                checkoutToGo(
                                    employeeName = employeeName,
                                    lines = lines,
                                    paymentType = "Debit",
                                )
                                cart.clear()
                                Toast.makeText(context, "Payment complete", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Checkout failed", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                                payDialog = false
                            }
                        }
                    },
                ) { Text("Debit") }
            },
        )
    }
}

private fun parseCategory(d: DocumentSnapshot): CatUi? {
    val id = d.id
    val name = d.getString("name")?.trim().orEmpty().ifBlank { id }
    return CatUi(id, name)
}

private fun itemAllowedForToGo(d: DocumentSnapshot): Boolean {
    @Suppress("UNCHECKED_CAST")
    val types = d.get("availableOrderTypes") as? List<*>
    if (types.isNullOrEmpty()) return true
    return types.any { it?.toString().equals("TO_GO", ignoreCase = true) }
}

private fun parseMenuItem(d: DocumentSnapshot): ItemUi? {
    if (!itemAllowedForToGo(d)) return null
    val id = d.id
    val name = d.getString("name")?.trim().orEmpty().ifBlank { return null }
    val cat = d.getString("categoryId")?.trim().orEmpty()
    val cents = d.getLong("basePriceInCents") ?: d.getLong("unitPriceInCents") ?: return null
    val dollars = cents / 100.0
    return ItemUi(id, name, cat, dollars)
}

private suspend fun loadItemsForCategory(db: FirebaseFirestore, merchantId: String, categoryId: String): List<ItemUi> =
    withContext(Dispatchers.IO) {
        val snap = db.collection("Merchants").document(merchantId).collection("menuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .await()
        snap.documents.mapNotNull { parseMenuItem(it) }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

private suspend fun checkoutToGo(
    employeeName: String,
    lines: List<CartLine>,
    paymentType: String,
) {
    val (orderId, totalCents) = suspendCoroutine { cont ->
        ToGoCheckout.createToGoOrderWithLines(
            employeeName = employeeName,
            lines = lines,
            onSuccess = { oid, cents -> cont.resume(oid to cents) },
            onFailure = { e -> cont.resumeWithException(e) },
        )
    }
    val totalDollars = totalCents / 100.0
    val clientRef = SpinClient.newClientReferenceId()
    val body = SpinClient.saleRequestJson(totalDollars, paymentType, clientRef)
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val resp = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(SpinClient.saleUrl())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()
    }
    val text = resp.body?.string().orEmpty()
    if (!resp.isSuccessful) {
        throw IOException("HTTP ${resp.code}: ${text.take(200)}")
    }
    val json = JSONObject(text)
    val resultCode = json.optJSONObject("GeneralResponse")?.optString("ResultCode", "").orEmpty()
    if (resultCode != "0") {
        val msg = json.optJSONObject("GeneralResponse")?.optString("DetailedMessage")
            ?: json.optJSONObject("GeneralResponse")?.optString("Message")
            ?: "Declined"
        throw IOException(msg)
    }
    val referenceId = json.optString("ReferenceId", "").ifBlank { clientRef }
    val authCode = json.optString("AuthCode", "")
    val batchNumber = json.optString("BatchNumber", "")
    val transactionNumber = json.optString("TransactionNumber", "")
    val invoiceNumber = json.optString("InvoiceNumber", "")
    val pnReferenceId = json.optString("PNReferenceId", "")
    val cardData = json.optJSONObject("CardData")
    val cardBrand = cardData?.optString("CardType", "").orEmpty()
    val entryType = cardData?.optString("EntryType", "").orEmpty()
    val last4 = cardData?.optString("Last4", "").orEmpty()

    val batchId = suspendCoroutine { cont ->
        ToGoCheckout.fetchOpenBatchId(
            onResult = { cont.resume(it) },
            onFailure = { e -> cont.resumeWithException(e) },
        )
    }

    suspendCoroutine { cont ->
        PaymentEngine(FirebaseFirestore.getInstance()).processPayment(
            orderId = orderId,
            batchId = batchId,
            paymentType = paymentType,
            amountInCents = totalCents,
            authCode = authCode,
            cardBrand = cardBrand,
            last4 = last4,
            entryType = entryType,
            referenceId = referenceId,
            clientReferenceId = clientRef,
            batchNumber = batchNumber,
            transactionNumber = transactionNumber,
            invoiceNumber = invoiceNumber,
            pnReferenceId = pnReferenceId,
            onSuccess = { cont.resume(Unit) },
            onFailure = { cont.resumeWithException(it) },
        )
    }
}
