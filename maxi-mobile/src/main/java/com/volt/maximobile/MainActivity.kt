package com.volt.maximobile

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.volt.shared.MerchantFirestore
import com.volt.shared.engine.OrderEngine
import com.volt.shared.engine.OrderTaxCalculator
import com.volt.shared.engine.OrderTaxLine
import com.volt.shared.engine.OrderTaxRule
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

private enum class Screen { Activation, Pin, Dashboard, Hub, TablePick, BarSeatPick, Menu, Checkout, PaymentProcessing }

private data class PendingVariablePrice(
    val item: ItemUi,
    val guestNum: Int,
)

private enum class OrderChannel { DINE_IN, TO_GO, BAR }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        com.volt.maximobile.dvpaylite.DvPayLiteClient.init(this)
        com.volt.maximobile.dvpaylite.P8ReceiptPrinter.init(this)

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
    var orderTaxes by remember { mutableStateOf<List<OrderTaxRule>>(emptyList()) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var pendingVariablePrice by remember { mutableStateOf<PendingVariablePrice?>(null) }
    var checkoutStatusMessage by remember { mutableStateOf<String?>(null) }
    var variablePriceInput by remember { mutableStateOf("") }
    var orderChannel by remember { mutableStateOf(OrderChannel.TO_GO) }
    var contextTableId by remember { mutableStateOf<String?>(null) }
    var contextTableName by remember { mutableStateOf<String?>(null) }
    var contextOrderId by remember { mutableStateOf<String?>(null) }
    var contextTableLayoutId by remember { mutableStateOf<String?>(null) }
    var contextGuestCount by remember { mutableIntStateOf(0) }
    var contextGuestNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedGuest by remember { mutableIntStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    var activeScheduleIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val hostActivity = context as Activity

    fun addModifierPickToCart(pick: ModifierPickResult, guestNum: Int) {
        val effectiveGuest = when {
            orderChannel == OrderChannel.DINE_IN && contextGuestCount > 0 ->
                guestNum.coerceIn(1, contextGuestCount)
            else -> guestNum
        }
        if (orderChannel == OrderChannel.DINE_IN && contextGuestCount > 0 && selectedGuest != effectiveGuest) {
            selectedGuest = effectiveGuest
        }
        val lineKey = CartLineHelpers.cartLineKey(
            pick.itemId,
            pick.modifiers,
            effectiveGuest,
            pick.basePriceDollars,
        )
        val existingIndex = cart.indexOfFirst { line ->
            CartLineHelpers.cartLineKey(
                line.menuItemId,
                line.modifiers,
                line.guestNumber,
                line.basePriceDollars,
            ) == lineKey
        }
        if (existingIndex >= 0) {
            val line = cart[existingIndex]
            cart[existingIndex] = line.copy(quantity = line.quantity + 1)
        } else {
            cart.add(
                CartLine(
                    menuItemId = pick.itemId,
                    name = pick.name,
                    basePriceDollars = pick.basePriceDollars,
                    modifiers = pick.modifiers,
                    quantity = 1,
                    guestNumber = effectiveGuest,
                    taxMode = pick.taxMode,
                    taxIds = pick.taxIds,
                ),
            )
        }
    }

    val tablePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            screen = Screen.Dashboard
            return@rememberLauncherForActivityResult
        }
        val data = result.data
        val tid = data?.getStringExtra("tableId")?.trim().orEmpty()
        if (tid.isEmpty()) {
            screen = Screen.Dashboard
            return@rememberLauncherForActivityResult
        }
        contextTableId = tid
        contextTableName = data?.getStringExtra("tableName")
        contextTableLayoutId = data?.getStringExtra("tableLayoutId")?.trim()?.takeIf { it.isNotEmpty() }
        val sectionId = data?.getStringExtra("sectionId")?.trim().orEmpty()
        val sectionName = data?.getStringExtra("sectionName")?.trim().orEmpty()
        val guestCount = data?.getIntExtra("guestCount", 1) ?: 1
        val guestNames = data?.getStringArrayListExtra("guestNames")?.filter { it.isNotBlank() }.orEmpty()
        val joinedIds = data?.getStringArrayListExtra("joinedTableIds")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        val joinedForOrder = joinedIds.takeIf { it.size > 1 }
        val existingOrderId = data?.getStringExtra("ORDER_ID")?.trim()?.takeIf { it.isNotEmpty() }
        contextGuestCount = guestCount.coerceAtLeast(1)
        contextGuestNames = guestNames
        selectedGuest = 1

        // Go straight to the menu (same as To-Go/Bar) while the Firestore order is created.
        screen = Screen.Menu
        scope.launch {
            busy = true
            err = null
            try {
                val mid = PosDeviceIdentity.getMerchantId(context).trim()
                if (mid.isEmpty()) throw IllegalStateException("Merchant ID missing")
                MerchantFirestore.init(mid)
                val orderId = suspendCoroutine { cont ->
                    OrderEngine(FirebaseFirestore.getInstance()).ensureOrder(
                        currentOrderId = existingOrderId,
                        employeeName = employeeName,
                        orderType = "DINE_IN",
                        tableId = tid,
                        tableLayoutId = contextTableLayoutId,
                        joinedTableIds = joinedForOrder,
                        tableName = contextTableName,
                        sectionId = sectionId.takeIf { it.isNotBlank() },
                        sectionName = sectionName.takeIf { it.isNotBlank() },
                        guestCount = if (guestCount > 0) guestCount else null,
                        guestNames = guestNames.takeIf { it.isNotEmpty() },
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
                contextOrderId = orderId
            } catch (e: Exception) {
                contextTableId = null
                contextTableName = null
                contextOrderId = null
                contextTableLayoutId = null
                contextGuestCount = 0
                contextGuestNames = emptyList()
                selectedGuest = 1
                val msg = e.message ?: "Failed to start order"
                err = msg
                screen = Screen.Dashboard
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

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
            contextOrderId = null
            contextTableLayoutId = null
            contextGuestCount = 0
            contextGuestNames = emptyList()
            selectedGuest = 1
            searchQuery = ""
            activeScheduleIds = emptySet()
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
        if (pendingVariablePrice != null) {
            pendingVariablePrice = null
            return@BackHandler
        }
        when (screen) {
            Screen.Pin -> activity.moveTaskToBack(false)
            Screen.Dashboard -> {
                screen = Screen.Pin
                employeeName = ""
            }
            Screen.Hub -> {
                screen = Screen.Dashboard
            }
            Screen.TablePick, Screen.BarSeatPick -> {
                screen = Screen.Dashboard
                err = null
            }
            Screen.Checkout -> {
                if (!busy) screen = Screen.Menu
            }
            Screen.Menu -> {
                cart.clear()
                contextTableId = null
                contextTableName = null
                contextOrderId = null
                contextTableLayoutId = null
                contextGuestCount = 0
                contextGuestNames = emptyList()
                selectedGuest = 1
                searchQuery = ""
                screen = Screen.Dashboard
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
        } else if (screen == Screen.Checkout) {
            w.statusBarColor = AndroidColor.parseColor("#5E4085")
            w.navigationBarColor = AndroidColor.parseColor("#F5F6F8")
            bars.isAppearanceLightStatusBars = false
            bars.isAppearanceLightNavigationBars = true
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
                        ReceiptPrintingConfig.startSync(context)
                        ReceiptSettings.startBusinessInfoSync(context)
                        screen = Screen.Dashboard
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

    if (screen == Screen.Dashboard) {
        LaunchedEffect(Unit) {
            val mid = PosDeviceIdentity.getMerchantId(context).trim()
            if (mid.isNotEmpty()) {
                if (!MerchantFirestore.isInitialized) MerchantFirestore.init(mid)
                ReceiptSettings.startBusinessInfoSync(context)
            }
        }
        DashboardScreen(
            employeeName = employeeName,
            employeeRole = "Staff",
            onNewOrder = { orderType ->
                when (orderType) {
                    "DINE_IN" -> {
                        orderChannel = OrderChannel.DINE_IN
                        contextTableId = null
                        contextTableName = null
                        contextOrderId = null
                        contextTableLayoutId = null
                        contextGuestCount = 0
                        contextGuestNames = emptyList()
                        selectedGuest = 1
                        cart.clear()
                        searchQuery = ""
                        err = null
                        screen = Screen.TablePick
                    }
                    "TO_GO" -> {
                        orderChannel = OrderChannel.TO_GO
                        contextTableId = null
                        contextTableName = null
                        cart.clear()
                        searchQuery = ""
                        err = null
                        screen = Screen.Menu
                    }
                    "BAR" -> {
                        orderChannel = OrderChannel.BAR
                        contextTableId = null
                        contextTableName = null
                        cart.clear()
                        searchQuery = ""
                        err = null
                        screen = Screen.BarSeatPick
                    }
                }
            },
            onLogout = {
                screen = Screen.Pin
                employeeName = ""
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
                contextOrderId = null
                contextTableLayoutId = null
                contextGuestCount = 0
                contextGuestNames = emptyList()
                selectedGuest = 1
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
        var launched by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!launched) {
                launched = true
                val mid = PosDeviceIdentity.getMerchantId(context).trim()
                if (mid.isNotEmpty() && !MerchantFirestore.isInitialized) {
                    MerchantFirestore.init(mid)
                }
                val intent = Intent(context, TableSelectionActivity::class.java).apply {
                    putExtra("SELECT_TABLE_ONLY", true)
                    putExtra("batchId", "")
                    putExtra("employeeName", employeeName)
                }
                tablePickLauncher.launch(intent)
            }
        }
        return
    }

    if (screen == Screen.BarSeatPick) {
        BarSeatPickScreen(
            onBack = { screen = Screen.Dashboard },
            onPick = { id, name ->
                contextTableId = id
                contextTableName = name
                screen = Screen.Menu
            },
        )
        return
    }

    if (screen == Screen.Menu) {
        val launchModifierPick: (ItemUi, Double, Int) -> Unit = { item, basePrice, guestNum ->
            val mid = PosDeviceIdentity.getMerchantId(context).trim()
            if (mid.isNotEmpty() && !MerchantFirestore.isInitialized) {
                MerchantFirestore.init(mid)
            }
            MenuModifierPicker(hostActivity).pickForMenuItem(
                itemId = item.id,
                name = item.name,
                basePrice = basePrice,
                taxMode = item.taxMode,
                taxIds = item.taxIds,
                onResult = { pick -> addModifierPickToCart(pick, guestNum) },
            )
        }

        Column(Modifier.fillMaxSize()) {
            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            LaunchedEffect(screen) {
                if (screen == Screen.Menu) {
                    val mid = PosDeviceIdentity.getMerchantId(context).trim()
                    if (mid.isNotBlank()) {
                        runCatching { orderTaxes = loadEnabledOrderTaxes(mid) }
                    }
                }
            }
            MaxiMenuOrderContent(
                orderTitle = orderScreenTitle(orderChannel),
                orderSubtitle = orderScreenSubtitle(employeeName, contextTableName),
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                items = items,
                cart = cart.toList(),
                taxes = orderTaxes,
                searchQuery = searchQuery,
                busy = busy,
                checkoutEnabled = when (orderChannel) {
                    OrderChannel.TO_GO -> true
                    OrderChannel.DINE_IN -> !contextTableId.isNullOrBlank()
                    OrderChannel.BAR -> !contextTableId.isNullOrBlank()
                } && cart.isNotEmpty() && !busy,
                guestCount = if (orderChannel == OrderChannel.DINE_IN) contextGuestCount else 0,
                guestNames = if (orderChannel == OrderChannel.DINE_IN) contextGuestNames else emptyList(),
                selectedGuest = selectedGuest,
                onGuestSelected = { selectedGuest = it },
                onBack = {
                    cart.clear()
                    contextTableId = null
                    contextTableName = null
                    contextOrderId = null
                    contextTableLayoutId = null
                    contextGuestCount = 0
                    contextGuestNames = emptyList()
                    selectedGuest = 1
                    searchQuery = ""
                    screen = Screen.Dashboard
                },
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
                    val guestNum = if (orderChannel == OrderChannel.DINE_IN && selectedGuest > 0) {
                        selectedGuest
                    } else {
                        0
                    }
                    if (item.variablePrice) {
                        variablePriceInput = if (item.priceDollars > 0) {
                            String.format(Locale.US, "%.2f", item.priceDollars)
                        } else {
                            ""
                        }
                        pendingVariablePrice = PendingVariablePrice(item, guestNum)
                    } else {
                        launchModifierPick(item, item.priceDollars, guestNum)
                    }
                },
                onIncreaseLine = { index ->
                    if (index in cart.indices) {
                        val line = cart[index]
                        cart[index] = line.copy(quantity = line.quantity + 1)
                    }
                },
                onDecreaseLine = { index ->
                    if (index in cart.indices) {
                        val line = cart[index]
                        val newQty = line.quantity - 1
                        if (newQty <= 0) {
                            cart.removeAt(index)
                        } else {
                            cart[index] = line.copy(quantity = newQty)
                        }
                    }
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
                    } else {
                        screen = Screen.Checkout
                    }
                },
            )
        }

        pendingVariablePrice?.let { pending ->
            AlertDialog(
                onDismissRequest = { pendingVariablePrice = null },
                title = {
                    Text(stringResource(R.string.variable_price_dialog_title, pending.item.name))
                },
                text = {
                    Column {
                        Text(stringResource(R.string.variable_price_dialog_message))
                        OutlinedTextField(
                            value = variablePriceInput,
                            onValueChange = { variablePriceInput = it },
                            label = { Text(stringResource(R.string.variable_price_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val parsed = variablePriceInput.trim().replace(',', '.').toDoubleOrNull()
                            if (parsed == null || parsed < 0) {
                                Toast.makeText(
                                    context,
                                    R.string.variable_price_invalid,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@TextButton
                            }
                            launchModifierPick(pending.item, parsed, pending.guestNum)
                            pendingVariablePrice = null
                        },
                    ) {
                        Text(stringResource(R.string.variable_price_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingVariablePrice = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        return
    }

    if (screen == Screen.Checkout) {
        val orderTypeForConfig = when (orderChannel) {
            OrderChannel.TO_GO -> "TO_GO"
            OrderChannel.DINE_IN -> "DINE_IN"
            OrderChannel.BAR -> "BAR_TAB"
        }
        val creditOn = OrderTypePaymentConfig.isCreditEnabled(context, orderTypeForConfig)
                && SpinClient.spinConfigured()
        val debitOn = OrderTypePaymentConfig.isDebitEnabled(context, orderTypeForConfig)
                && SpinClient.spinConfigured()
        val cashOn = OrderTypePaymentConfig.isCashEnabled(context, orderTypeForConfig)

        fun onPaymentSuccess(completedOrderId: String) {
            KitchenPrintHelper.maybePrintKitchenTicketsAfterOrderFullyPaid(context, completedOrderId)
            cart.clear()
            contextTableId = null
            contextTableName = null
            contextOrderId = null
            contextTableLayoutId = null
            contextGuestCount = 0
            contextGuestNames = emptyList()
            selectedGuest = 1
            searchQuery = ""
            screen = Screen.Dashboard
            val intent = Intent(context, ReceiptOptionsActivity::class.java).apply {
                putExtra("ORDER_ID", completedOrderId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        fun doCardCheckout(paymentType: String) {
            scope.launch {
                busy = true
                checkoutStatusMessage = "Waiting for card…"
                try {
                    val completedOrderId = checkoutOrder(
                        employeeName = employeeName,
                        lines = cart.toList(),
                        paymentType = paymentType,
                        channel = orderChannel,
                        tableId = contextTableId,
                        tableName = contextTableName,
                        existingOrderId = contextOrderId,
                        tableLayoutId = contextTableLayoutId,
                    )
                    onPaymentSuccess(completedOrderId)
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Checkout failed", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                    checkoutStatusMessage = null
                }
            }
        }

        fun doCashCheckout() {
            scope.launch {
                busy = true
                checkoutStatusMessage = "Recording cash payment…"
                try {
                    val completedOrderId = checkoutOrderCash(
                        employeeName = employeeName,
                        lines = cart.toList(),
                        channel = orderChannel,
                        tableId = contextTableId,
                        tableName = contextTableName,
                        existingOrderId = contextOrderId,
                        tableLayoutId = contextTableLayoutId,
                    )
                    onPaymentSuccess(completedOrderId)
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Cash payment failed", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                    checkoutStatusMessage = null
                }
            }
        }

        MaxiCheckoutScreen(
            cart = cart.toList(),
            taxes = orderTaxes,
            orderChannelLabel = orderTypeForConfig.replace("_", " "),
            busy = busy,
            statusMessage = checkoutStatusMessage,
            creditEnabled = creditOn,
            debitEnabled = debitOn,
            cashEnabled = cashOn,
            onCredit = { doCardCheckout("Credit") },
            onDebit = { doCardCheckout("Debit") },
            onCash = { doCashCheckout() },
            onBack = { if (!busy) screen = Screen.Menu },
        )
        return
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

private fun orderScreenTitle(channel: OrderChannel): String = when (channel) {
    OrderChannel.DINE_IN -> "Dine in"
    OrderChannel.TO_GO -> "To go"
    OrderChannel.BAR -> "Bar"
}

private fun orderScreenSubtitle(employeeName: String, tableOrSeatName: String?): String {
    return buildList {
        if (employeeName.isNotBlank()) add(employeeName)
        tableOrSeatName?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" • ")
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
    val variablePrice = d.getBoolean("variablePrice") ?: false
    val taxIds = MerchantFirestore.mergeMenuItemTaxIds(d)
    val taxMode = MerchantFirestore.menuItemTaxModeFromDoc(d)
    return ItemUi(id, name, cat, dollars, variablePrice, taxMode, taxIds)
}

private suspend fun loadEnabledOrderTaxes(merchantId: String): List<OrderTaxRule> =
    withContext(Dispatchers.IO) {
        MerchantFirestore.init(merchantId)
        val snap = MerchantFirestore.col("Taxes").get().await()
        snap.documents.mapNotNull { doc ->
            val name = doc.getString("name") ?: return@mapNotNull null
            val type = doc.getString("type") ?: return@mapNotNull null
            val amount = doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble() ?: return@mapNotNull null
            val enabled = doc.getBoolean("enabled") ?: true
            OrderTaxRule(doc.id, name, type, amount, enabled)
        }
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
    existingOrderId: String? = null,
    tableLayoutId: String? = null,
): String {
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
                    val oid = existingOrderId?.trim().orEmpty()
                    if (oid.isNotEmpty()) {
                        ToGoCheckout.appendLinesToExistingOrder(
                            orderId = oid,
                            lines = lines,
                            onSuccess = { cents -> cont.resume(oid to cents) },
                            onFailure = { cont.resumeWithException(it) },
                        )
                    } else {
                        ToGoCheckout.createToGoOrderWithLines(
                            employeeName = employeeName,
                            lines = lines,
                            orderType = "DINE_IN",
                            tableId = tid,
                            tableLayoutId = tableLayoutId,
                            tableName = tn,
                            onSuccess = { newOid, cents -> cont.resume(newOid to cents) },
                            onFailure = { cont.resumeWithException(it) },
                        )
                    }
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
    return orderId
}

/** Creates the Firestore order and records a cash payment (no SPIn terminal call). */
private suspend fun checkoutOrderCash(
    employeeName: String,
    lines: List<CartLine>,
    channel: OrderChannel,
    tableId: String?,
    tableName: String?,
    existingOrderId: String? = null,
    tableLayoutId: String? = null,
): String {
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
                    val oid = existingOrderId?.trim().orEmpty()
                    if (oid.isNotEmpty()) {
                        ToGoCheckout.appendLinesToExistingOrder(
                            orderId = oid,
                            lines = lines,
                            onSuccess = { cents -> cont.resume(oid to cents) },
                            onFailure = { cont.resumeWithException(it) },
                        )
                    } else {
                        ToGoCheckout.createToGoOrderWithLines(
                            employeeName = employeeName,
                            lines = lines,
                            orderType = "DINE_IN",
                            tableId = tid,
                            tableLayoutId = tableLayoutId,
                            tableName = tn,
                            onSuccess = { newOid, cents -> cont.resume(newOid to cents) },
                            onFailure = { cont.resumeWithException(it) },
                        )
                    }
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
            paymentType = "Cash",
            amountInCents = totalCents,
            authCode = "",
            cardBrand = "",
            last4 = "",
            entryType = "Cash",
            referenceId = "",
            clientReferenceId = "",
            batchNumber = "",
            transactionNumber = "",
            invoiceNumber = "",
            pnReferenceId = "",
            onSuccess = { cont.resume(Unit) },
            onFailure = { cont.resumeWithException(it) },
        )
    }
    return orderId
}
