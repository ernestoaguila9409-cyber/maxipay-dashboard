package com.volt.maximobile

import android.app.Application
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.view.WindowCompat
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.functions.FirebaseFunctions
import com.volt.maximobile.engine.PaymentEngine
import com.volt.maximobile.spin.SpinClient
import java.io.IOException
import java.util.Calendar
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

private enum class Screen { Activation, Pin, Hub, TablePick, BarSeatPick, Menu }

private enum class OrderChannel { DINE_IN, TO_GO, BAR }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // Keep content above the system navigation bar (readable back/home/recents).
        WindowCompat.setDecorFitsSystemWindows(window, true)
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
    var employeeName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var activationForced by remember { mutableStateOf(false) }
    var businessTitle by remember { mutableStateOf(PosDeviceIdentity.getMerchantBusinessName(context)) }

    var categories by remember { mutableStateOf<List<CatUi>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<ItemUi>>(emptyList()) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var payDialog by remember { mutableStateOf(false) }
    var orderChannel by remember { mutableStateOf(OrderChannel.TO_GO) }
    var contextTableId by remember { mutableStateOf<String?>(null) }
    var contextTableName by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var activeScheduleIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    DisposableEffect(Unit) {
        val app = context.applicationContext as Application
        PosDeviceDeactivationNotifier.onForceActivation = {
            screen = Screen.Activation
            employeeName = ""
            activationCode = ""
            activationForced = true
            categories = emptyList()
            selectedCategoryId = null
            items = emptyList()
            cart.clear()
            orderChannel = OrderChannel.TO_GO
            contextTableId = null
            contextTableName = null
            searchQuery = ""
            activeScheduleIds = emptySet()
            payDialog = false
            busy = false
            err = null
        }
        PosDeviceDeactivationWatch.syncFirestoreListener(app)
        onDispose {
            PosDeviceDeactivationNotifier.onForceActivation = null
        }
    }

    fun loadCategories() {
        scope.launch {
            busy = true
            err = null
            try {
                val mid = PosDeviceIdentity.getMerchantId(context).trim()
                if (mid.isEmpty()) throw IllegalStateException("Merchant ID missing")
                MerchantFirestore.init(mid)
                val scheduleIds = try {
                    fetchActiveScheduleIdsNow()
                } catch (_: Exception) {
                    emptySet()
                }
                activeScheduleIds = scheduleIds
                val base = MerchantFirestore.col("Categories")
                val snap = fetchCategoriesForChannel(base, orderChannel)
                val list = snap.documents.mapNotNull { d -> parseCategory(d) }
                    .sortedBy { it.name.lowercase(Locale.US) }
                categories = list
                selectedCategoryId = list.firstOrNull()?.id
                val first = list.firstOrNull()
                if (first != null) {
                    items = loadItemsForCategory(
                        mid,
                        first.id,
                        orderChannel,
                        first.availableOrderTypes,
                        scheduleIds,
                    )
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

    LaunchedEffect(screen, orderChannel, contextTableId) {
        when (screen) {
            Screen.Menu -> loadCategories()
            Screen.Pin -> {
                PosDeviceIdentity.syncMerchantBusinessNameFromFirestore(context.applicationContext)
                businessTitle = PosDeviceIdentity.getMerchantBusinessName(context)
            }
            else -> Unit
        }
    }

    val activity = context as ComponentActivity
    BackHandler(enabled = screen != Screen.Activation) {
        if (payDialog) {
            payDialog = false
            return@BackHandler
        }
        when (screen) {
            Screen.Pin -> activity.moveTaskToBack(false)
            Screen.Hub -> {
                screen = Screen.Pin
                employeeName = ""
            }
            Screen.TablePick, Screen.BarSeatPick -> {
                screen = Screen.Hub
                err = null
            }
            Screen.Menu -> {
                cart.clear()
                contextTableId = null
                contextTableName = null
                searchQuery = ""
                screen = Screen.Hub
            }
            else -> Unit
        }
    }

    SideEffect {
        val w = activity.window
        val bars = WindowCompat.getInsetsController(w, w.decorView)
        if (screen == Screen.Pin) {
            w.statusBarColor = AndroidColor.parseColor("#12002F")
            w.navigationBarColor = AndroidColor.parseColor("#12002F")
            bars.isAppearanceLightStatusBars = false
            bars.isAppearanceLightNavigationBars = false
        } else {
            w.statusBarColor = AndroidColor.WHITE
            w.navigationBarColor = AndroidColor.parseColor("#E8EAED")
            bars.isAppearanceLightStatusBars = true
            bars.isAppearanceLightNavigationBars = true
        }
    }

    if (screen == Screen.Activation) {
        val subtitle = if (activationForced) {
            stringResource(R.string.device_activation_forced_instructions)
        } else {
            stringResource(R.string.device_activation_instructions)
        }
        MaxiActivationScreen(
            subtitle = subtitle,
            code = activationCode,
            onCodeChange = { raw ->
                activationCode = raw.filter { it.isDigit() }.take(6)
                err = null
            },
            error = err,
            busy = busy,
            onSubmit = {
                busy = true
                err = null
                PosDeviceActivation.redeemCode(
                    context,
                    activationCode,
                    onSuccess = {
                        activationCode = ""
                        err = null
                        activationForced = false
                        screen = Screen.Pin
                        busy = false
                        PosDeviceDeactivationWatch.syncFirestoreListener(
                            context.applicationContext as Application,
                        )
                    },
                    onError = { msg ->
                        err = msg
                        busy = false
                    },
                )
            },
        )
        return
    }

    if (screen == Screen.Pin) {
        val fallbackTitle = stringResource(R.string.login_business_title_fallback)
        val headerTitle = businessTitle.trim().ifBlank { fallbackTitle }
        StaffPinLoginScreen(
            businessTitle = headerTitle,
            errorMessage = err,
            busy = busy,
            onClearError = { err = null },
            onSubmitPin = { code ->
                scope.launch {
                    if (busy) return@launch
                    busy = true
                    err = null
                    try {
                        val mid = PosDeviceIdentity.getMerchantId(context).trim()
                        if (mid.isEmpty()) {
                            err = "Merchant ID missing"
                            return@launch
                        }
                        val data = hashMapOf<String, Any>("pin" to code, "merchantId" to mid)
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
                        screen = Screen.Hub
                    } catch (e: Exception) {
                        err = e.message ?: "Login failed"
                    } finally {
                        busy = false
                    }
                }
            },
        )
        return
    }

    if (screen == Screen.Hub) {
        OrderModeHubScreen(
            onDineIn = {
                orderChannel = OrderChannel.DINE_IN
                contextTableId = null
                contextTableName = null
                cart.clear()
                searchQuery = ""
                err = null
                screen = Screen.TablePick
            },
            onToGo = {
                orderChannel = OrderChannel.TO_GO
                contextTableId = null
                contextTableName = null
                cart.clear()
                searchQuery = ""
                err = null
                screen = Screen.Menu
            },
            onBar = {
                orderChannel = OrderChannel.BAR
                contextTableId = null
                contextTableName = null
                cart.clear()
                searchQuery = ""
                err = null
                screen = Screen.BarSeatPick
            },
        )
        return
    }

    if (screen == Screen.TablePick) {
        TablePickScreen(
            onBack = {
                screen = Screen.Hub
                err = null
            },
            onPick = { id, name ->
                contextTableId = id
                contextTableName = name
                screen = Screen.Menu
            },
        )
        return
    }

    if (screen == Screen.BarSeatPick) {
        BarSeatPickScreen(
            onBack = { screen = Screen.Hub },
            onPick = { id, name ->
                contextTableId = id
                contextTableName = name
                screen = Screen.Menu
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(menuTopBarTitle(orderChannel, contextTableName)) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            cart.clear()
                            contextTableId = null
                            contextTableName = null
                            searchQuery = ""
                            screen = Screen.Hub
                        },
                    ) { Text("Order type") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            MaxiMenuOrderContent(
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                items = items,
                cart = cart.toList(),
                searchQuery = searchQuery,
                busy = busy,
                checkoutEnabled = when (orderChannel) {
                    OrderChannel.TO_GO -> true
                    OrderChannel.DINE_IN -> !contextTableId.isNullOrBlank()
                    OrderChannel.BAR -> !contextTableId.isNullOrBlank()
                } && cart.isNotEmpty() && !busy,
                onCategorySelected = { cat ->
                    selectedCategoryId = cat.id
                    searchQuery = ""
                    scope.launch {
                        busy = true
                        try {
                            val mid = PosDeviceIdentity.getMerchantId(context).trim()
                            items = emptyList()
                            items = loadItemsForCategory(
                                mid,
                                cat.id,
                                orderChannel,
                                cat.availableOrderTypes,
                                activeScheduleIds,
                            )
                        } catch (e: Exception) {
                            err = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                onSearchQueryChange = { searchQuery = it },
                onItemClick = { item ->
                    cart.add(
                        CartLine(
                            menuItemId = item.id,
                            name = item.name,
                            unitPriceDollars = item.priceDollars,
                            quantity = 1,
                        ),
                    )
                },
                onCheckoutClick = {
                    val canPayChannel = when (orderChannel) {
                        OrderChannel.TO_GO -> true
                        OrderChannel.DINE_IN -> !contextTableId.isNullOrBlank()
                        OrderChannel.BAR -> !contextTableId.isNullOrBlank()
                    }
                    if (!canPayChannel) {
                        err = when (orderChannel) {
                            OrderChannel.DINE_IN -> "Select a table first (Order type → Dine in)."
                            OrderChannel.BAR -> "Select a bar seat first (Order type → Bar)."
                            else -> "Cannot checkout"
                        }
                    } else if (cart.isEmpty()) {
                        err = "Cart is empty"
                    } else if (!SpinClient.spinConfigured()) {
                        err = "SPIn not configured. Add maxi-mobile/secrets.properties and rebuild."
                    } else {
                        payDialog = true
                    }
                },
            )
        }
    }

    if (payDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) payDialog = false },
            title = { Text("Card type") },
            text = { Text("Creates the order, runs SPIn Sale on the terminal, then records payment.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val lines = cart.toList()
                                checkoutOrder(
                                    employeeName = employeeName,
                                    lines = lines,
                                    paymentType = "Credit",
                                    channel = orderChannel,
                                    tableId = contextTableId,
                                    tableName = contextTableName,
                                )
                                cart.clear()
                                contextTableId = null
                                contextTableName = null
                                screen = Screen.Hub
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
                                checkoutOrder(
                                    employeeName = employeeName,
                                    lines = lines,
                                    paymentType = "Debit",
                                    channel = orderChannel,
                                    tableId = contextTableId,
                                    tableName = contextTableName,
                                )
                                cart.clear()
                                contextTableId = null
                                contextTableName = null
                                screen = Screen.Hub
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

private suspend fun fetchActiveScheduleIdsNow(): Set<String> = withContext(Dispatchers.IO) {
    val cal = Calendar.getInstance()
    val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "MON"
        Calendar.TUESDAY -> "TUE"
        Calendar.WEDNESDAY -> "WED"
        Calendar.THURSDAY -> "THU"
        Calendar.FRIDAY -> "FRI"
        Calendar.SATURDAY -> "SAT"
        Calendar.SUNDAY -> "SUN"
        else -> ""
    }
    val currentTime = String.format(
        Locale.US,
        "%02d:%02d",
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
    )
    val snap = MerchantFirestore.col("menuSchedules").get().await()
    val active = mutableSetOf<String>()
    for (doc in snap.documents) {
        @Suppress("UNCHECKED_CAST")
        val days = doc.get("days") as? List<*> ?: continue
        val startTime = doc.getString("startTime") ?: continue
        val endTime = doc.getString("endTime") ?: continue
        val dayStrings = days.mapNotNull { it?.toString() }
        if (dayStrings.contains(dayOfWeek) && currentTime >= startTime && currentTime <= endTime) {
            active.add(doc.id)
        }
    }
    active
}

private fun menuTopBarTitle(channel: OrderChannel, tableOrSeatName: String?): String {
    val label = tableOrSeatName?.trim().orEmpty()
    return when (channel) {
        OrderChannel.DINE_IN ->
            if (label.isNotEmpty()) "Dine in · $label" else "Dine in"
        OrderChannel.TO_GO -> "To go"
        OrderChannel.BAR ->
            if (label.isNotEmpty()) "Bar · $label" else "Bar"
    }
}

private suspend fun fetchCategoriesForChannel(
    base: CollectionReference,
    channel: OrderChannel,
): QuerySnapshot {
    val types = when (channel) {
        OrderChannel.DINE_IN -> listOf("DINE_IN")
        OrderChannel.TO_GO -> listOf("TO_GO")
        OrderChannel.BAR -> listOf("BAR", "BAR_TAB")
    }
    for (t in types) {
        val s = base.whereArrayContains("availableOrderTypes", t).get().await()
        if (!s.isEmpty) return s
    }
    return base.get().await()
}

private fun parseCategory(d: DocumentSnapshot): CatUi? {
    val id = d.id
    val name = d.getString("name")?.trim().orEmpty().ifBlank { id }
    @Suppress("UNCHECKED_CAST")
    val types = (d.get("availableOrderTypes") as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }
        ?: emptyList()
    return CatUi(id, name, types)
}

private fun itemAllowedForChannel(
    d: DocumentSnapshot,
    channel: OrderChannel,
    categoryFallbackTypes: List<String>,
): Boolean {
    @Suppress("UNCHECKED_CAST")
    val itemTypesRaw = d.get("availableOrderTypes") as? List<*>
    val effective: List<String> = if (!itemTypesRaw.isNullOrEmpty()) {
        itemTypesRaw.mapNotNull { it?.toString()?.trim()?.uppercase(Locale.US) }
    } else {
        categoryFallbackTypes.map { it.trim().uppercase(Locale.US) }
    }
    if (effective.isEmpty()) return true
    return when (channel) {
        OrderChannel.DINE_IN -> effective.any { it == "DINE_IN" }
        OrderChannel.TO_GO -> effective.any { it == "TO_GO" || it == "ONLINE_PICKUP" }
        OrderChannel.BAR -> effective.any { it == "BAR" || it == "BAR_TAB" }
    }
}

private fun resolveMenuItemPriceDollars(d: DocumentSnapshot): Double {
    @Suppress("UNCHECKED_CAST")
    val pricingRaw = d.get("pricing") as? Map<String, Any>
    val pricingPos = (pricingRaw?.get("pos") as? Number)?.toDouble()
    @Suppress("UNCHECKED_CAST")
    val pricesRaw = d.get("prices") as? Map<String, Any>
    val pricesMap = pricesRaw?.mapValues { (it.value as? Number)?.toDouble() ?: 0.0 } ?: emptyMap()
    val legacy = d.getDouble("price")
    val legacyLong = d.getLong("price")?.toDouble()
    val cents1 = d.getLong("basePriceInCents")
    val cents2 = d.getLong("unitPriceInCents")
    return pricingPos
        ?: if (pricesMap.isNotEmpty()) pricesMap.values.first()
        else legacy
        ?: legacyLong
        ?: cents1?.let { it / 100.0 }
        ?: cents2?.let { it / 100.0 }
        ?: 0.0
}

private fun parseMenuItem(
    d: DocumentSnapshot,
    channel: OrderChannel,
    categoryOrderTypes: List<String>,
    activeScheduleIds: Set<String>,
): ItemUi? {
    @Suppress("UNCHECKED_CAST")
    val channelsRaw = d.get("channels") as? Map<String, Any>
    val channelPos = (channelsRaw?.get("pos") as? Boolean) ?: true
    if (!channelPos) return null

    val isScheduled = d.getBoolean("isScheduled") ?: false
    if (isScheduled) {
        @Suppress("UNCHECKED_CAST")
        val scheduleIds = (d.get("scheduleIds") as? List<*>)?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()
        if (scheduleIds.isNotEmpty() &&
            (activeScheduleIds.isEmpty() || scheduleIds.none { it in activeScheduleIds })
        ) {
            return null
        }
    }

    if (!itemAllowedForChannel(d, channel, categoryOrderTypes)) return null

    val id = d.id
    val name = d.getString("name")?.trim().orEmpty().ifBlank { return null }
    val cat = d.getString("categoryId")?.trim().orEmpty()
    val dollars = resolveMenuItemPriceDollars(d)
    return ItemUi(id, name, cat, dollars)
}

private suspend fun loadItemsForCategory(
    merchantId: String,
    categoryId: String,
    channel: OrderChannel,
    categoryOrderTypes: List<String>,
    activeScheduleIds: Set<String>,
): List<ItemUi> =
    withContext(Dispatchers.IO) {
        MerchantFirestore.init(merchantId)
        val col = MerchantFirestore.col("MenuItems")
        val snap = col.where(
            Filter.or(
                Filter.equalTo("categoryId", categoryId),
                Filter.arrayContains("categoryIds", categoryId),
            ),
        ).get().await()
        snap.documents.mapNotNull { parseMenuItem(it, channel, categoryOrderTypes, activeScheduleIds) }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

private suspend fun checkoutOrder(
    employeeName: String,
    lines: List<CartLine>,
    paymentType: String,
    channel: OrderChannel,
    tableId: String?,
    tableName: String?,
) {
    val (orderId, totalCents) = suspendCoroutine { cont ->
        when (channel) {
            OrderChannel.TO_GO ->
                ToGoCheckout.createToGoOrderWithLines(
                    employeeName = employeeName,
                    lines = lines,
                    orderType = "TO_GO",
                    onSuccess = { oid, cents -> cont.resume(oid to cents) },
                    onFailure = { cont.resumeWithException(it) },
                )
            OrderChannel.DINE_IN -> {
                val tid = tableId?.trim().orEmpty()
                if (tid.isEmpty()) {
                    cont.resumeWithException(IllegalStateException("No table selected"))
                } else {
                    val tn = tableName?.trim().orEmpty().ifBlank { null }
                    ToGoCheckout.createToGoOrderWithLines(
                        employeeName = employeeName,
                        lines = lines,
                        orderType = "DINE_IN",
                        tableId = tid,
                        tableName = tn,
                        onSuccess = { oid, cents -> cont.resume(oid to cents) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
            }
            OrderChannel.BAR -> {
                val tid = tableId?.trim().orEmpty()
                if (tid.isEmpty()) {
                    cont.resumeWithException(IllegalStateException("No bar seat selected"))
                } else {
                    val tn = tableName?.trim().orEmpty().ifBlank { tid }
                    ToGoCheckout.createToGoOrderWithLines(
                        employeeName = employeeName,
                        lines = lines,
                        orderType = "BAR_TAB",
                        tableId = tid,
                        tableName = tn,
                        seatIds = listOf(tid),
                        seatName = tn,
                        area = "Bar",
                        onSuccess = { oid, cents -> cont.resume(oid to cents) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
            }
        }
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
