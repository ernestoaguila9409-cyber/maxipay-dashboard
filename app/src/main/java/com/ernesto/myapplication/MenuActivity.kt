package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.ernesto.myapplication.engine.AppliedDiscount
import com.ernesto.myapplication.engine.CaptureResult
import com.ernesto.myapplication.engine.DiscountEngine
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.OrderEngine
import com.ernesto.myapplication.engine.PaymentService
data class CartItem(
    val itemId: String,
    val name: String,
    var quantity: Int,
    val basePrice: Double,
    val stock: Long,
    val modifiers: List<OrderModifier>,
    val guestNumber: Int = 0,
    val taxMode: String = "INHERIT",
    val taxIds: List<String> = emptyList(),
    val printerLabel: String? = null,
)

class MenuActivity : AppCompatActivity() {

    private var currentCategoryId: String? = null
    private val db = FirebaseFirestore.getInstance()

    // key = lineKey (doc id in Orders/{orderId}/items)
    private val cartMap = mutableMapOf<String, CartItem>()
    private lateinit var orderEngine: OrderEngine
    private lateinit var discountEngine: DiscountEngine
    private lateinit var categoryContainer: LinearLayout
    private lateinit var editItemSearch: EditText
    private lateinit var itemGrid: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var txtEmptyMessage: TextView
    private lateinit var btnClearSearch: ImageView
    private lateinit var subcategoryChipHost: FrameLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var keyboardContainer: LinearLayout

    /** All items loaded for the current category (before search filter). */
    private var cachedCategoryItems = listOf<MenuGridItem>()
    /** ALL menu items across every category, loaded once for global search. */
    private var allMenuItemsCache = listOf<MenuGridItem>()
    private var allMenuItemsLoaded = false
    private lateinit var gridAdapter: MenuGridAdapter
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private lateinit var cartTaxDetails: LinearLayout
    private lateinit var cartTaxSummary: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnSendKitchen: MaterialButton
    private lateinit var btnCheckout: MaterialButton

    /** True if this order has had at least one explicit kitchen send (Firestore `lastKitchenSentAt`). */
    private var kitchenSentForOrder = false

    /**
     * Any line [kdsStatus] **PREPARING** (KDS START) blocks line deletes from the cart.
     */
    private var orderKitchenInProcess = false
    private var orderKitchenStatusListener: ListenerRegistration? = null
    private var isSendingToKitchen = false
    private var isCheckoutPending = false
    private var isCaptureFlowActive = false
    private var selectedGuest: Int = 0
    private var suppressModifierCallbacks = false

    private var totalAmount = 0.0
    private var allTaxes = mutableListOf<TaxItem>()
    private var appliedDiscounts = listOf<AppliedDiscount>()
    private var discountTotalCents = 0L
    private var employeeName: String = ""
    private var orderType: String = ""
    private var currentOrderId: String? = null
    private var isCreatingOrder = false
    private var tableId: String? = null
    private var tableLayoutId: String? = null
    private var tableName: String? = null
    private var sectionId: String? = null
    private var sectionName: String? = null
    private var guestCount: Int = 0
    private var guestNames: MutableList<String> = mutableListOf()

    private var customerId: String? = null
    private var customerName: String? = null
    private var customerPhone: String? = null
    private var customerEmail: String? = null

    private lateinit var paymentService: PaymentService
    private var preAuthReferenceId: String? = null
    private var preAuthAuthCode: String? = null
    private var preAuthCardLast4: String? = null
    private var preAuthCardBrand: String? = null
    private var preAuthFirestoreDocId: String? = null
    private var currentBatchId: String? = null
    private var stockCountingEnabled: Boolean = true
    private var activeScheduleIds: Set<String> = emptySet()
    private var allSubcategories: List<SubcategoryModel> = emptyList()
    private var currentSubcategoryId: String? = null
    /** Category ID → kitchen label (empty string when unset). */
    private val categoryKitchenLabels = mutableMapOf<String, String>()

    /** Routing label (normalized) → note text.  Saved to Firestore as `kitchenNotesByLabel`. */
    private val kitchenNotesByLabel = mutableMapOf<String, String>()

    /** Ignores stale Firestore callbacks when loadItems is triggered repeatedly (e.g. search typing). */
    private var loadItemsGeneration: Int = 0

    private val paymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (stockCountingEnabled) {
                    deductStockTransaction(
                        onSuccess = {
                            clearCart()
                            detachOrderKitchenStatusListener()
                            currentOrderId = null
                            Toast.makeText(this, "Stock updated successfully", Toast.LENGTH_SHORT).show()

                            currentCategoryId?.let {
                                loadItems(it)
                            }
                        },
                        onFailure = { msg ->
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                } else {
                    clearCart()
                    detachOrderKitchenStatusListener()
                    currentOrderId = null
                    currentCategoryId?.let { loadItems(it) }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        orderEngine = OrderEngine(db)
        discountEngine = DiscountEngine(db)
        paymentService = PaymentService(this)
        employeeName = intent.getStringExtra("employeeName") ?: ""
        orderType = intent.getStringExtra("orderType") ?: ""
        tableId = intent.getStringExtra("tableId")
        tableLayoutId = intent.getStringExtra("tableLayoutId")?.takeIf { it.isNotBlank() }
        tableName = intent.getStringExtra("tableName")
        sectionId = intent.getStringExtra("sectionId")
        sectionName = intent.getStringExtra("sectionName")
        guestCount = intent.getIntExtra("guestCount", 0)
        guestNames = intent.getStringArrayListExtra("guestNames")?.toMutableList() ?: mutableListOf()
        currentBatchId = intent.getStringExtra("batchId")

        categoryContainer = findViewById(R.id.categoryContainer)
        editItemSearch = findViewById(R.id.editItemSearch)
        itemGrid = findViewById(R.id.itemGrid)
        emptyState = findViewById(R.id.emptyState)
        txtEmptyMessage = findViewById(R.id.txtEmptyMessage)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        subcategoryChipHost = findViewById(R.id.subcategoryChipHost)

        val columns = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 3
        gridAdapter = MenuGridAdapter { item -> onGridItemClicked(item) }
        itemGrid.layoutManager = GridLayoutManager(this, columns)
        itemGrid.adapter = gridAdapter

        btnClearSearch.setOnClickListener {
            editItemSearch.text.clear()
            currentCategoryId?.let { loadItems(it) }
        }

        editItemSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrEmpty()
                btnClearSearch.visibility = if (hasText) View.VISIBLE else View.GONE
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable { applySearchFilter() }
                searchHandler.postDelayed(searchRunnable!!, 200)
            }
        })

        keyboardContainer = findViewById(R.id.keyboardContainer)
        editItemSearch.showSoftInputOnFocus = false
        buildEmbeddedKeyboard()
        editItemSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideSystemKeyboard()
                keyboardContainer.visibility = View.VISIBLE
            } else {
                keyboardContainer.visibility = View.GONE
            }
        }
        editItemSearch.setOnClickListener {
            hideSystemKeyboard()
            keyboardContainer.visibility = View.VISIBLE
        }

        cartContainer = findViewById(R.id.cartContainer)
        cartTaxDetails = findViewById(R.id.cartTaxDetails)
        cartTaxSummary = findViewById(R.id.cartTaxSummary)
        txtTotal = findViewById(R.id.txtTotal)
        btnSendKitchen = findViewById(R.id.btnSendKitchen)
        btnCheckout = findViewById(R.id.btnCheckout)
        updateSendKitchenButtonLabel()
        btnSendKitchen.setOnClickListener { sendToKitchen() }

        findViewById<View>(R.id.btnKitchenNotes).setOnClickListener { showKitchenNotesDialog() }

        val txtCustomerValue = findViewById<TextView>(R.id.txtCustomerValue)
        txtCustomerValue.setOnClickListener { showAddCustomerDialog() }

        // ✅ IMPORTANT: if we came from OrderDetail "Checkout", we are editing an existing order
        currentOrderId = intent.getStringExtra("ORDER_ID")

        val txtTableHeader = findViewById<TextView>(R.id.txtTableHeader)
        if (!tableName.isNullOrBlank()) {
            val guestLabel = if (guestCount > 0) " • $guestCount guest${if (guestCount > 1) "s" else ""}" else ""
            txtTableHeader.text = "$tableName$guestLabel"
            txtTableHeader.visibility = View.VISIBLE
        }

        if (guestCount > 0) {
            selectedGuest = 1
        }

        db.collection("Settings").document("inventory").get()
            .addOnSuccessListener { doc ->
                stockCountingEnabled = doc.getBoolean("stockCountingEnabled") ?: true
                loadActiveSchedules {
                    loadCategories()
                    loadAllMenuItems()
                }
            }
            .addOnFailureListener {
                stockCountingEnabled = true
                loadActiveSchedules {
                    loadCategories()
                    loadAllMenuItems()
                }
            }

        txtEmptyMessage.text = "Select a category or search for items"
        emptyState.visibility = View.VISIBLE
        itemGrid.visibility = View.GONE

        loadAllTaxes()
        discountEngine.loadDiscounts { refreshCart() }
        updateCustomerDisplay()

        val bizName = ReceiptSettings.load(this).businessName
        CustomerDisplayManager.setIdle(this, bizName)

        // ✅ Load existing order items into cart if ORDER_ID was provided
        currentOrderId?.let { existingOrderId ->
            loadExistingOrderIntoCart(existingOrderId)
            loadKitchenNotesFromFirestore(existingOrderId)
        }

        btnCheckout.setOnClickListener {

            if (cartMap.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val oid = currentOrderId ?: return@setOnClickListener

            isCheckoutPending = true
            syncCartButtonStates()

            orderEngine.waitForPendingWrites {
                if (!preAuthReferenceId.isNullOrBlank() && !preAuthAuthCode.isNullOrBlank()) {
                    isCheckoutPending = false
                    syncCartButtonStates()
                    startCaptureFlow(oid)
                } else {
                    orderEngine.recomputeOrderTotals(
                        orderId = oid,
                        onSuccess = {
                            isCheckoutPending = false
                            syncCartButtonStates()
                            val targetActivity = if (
                                TipConfig.isTipsEnabled(this) && TipConfig.isTipOnCustomerScreen(this)
                            ) TipActivity::class.java else PaymentActivity::class.java
                            val intent = Intent(this, targetActivity).apply {
                                putExtra("ORDER_ID", oid)
                                putExtra("BATCH_ID", currentBatchId ?: "")
                            }
                            paymentLauncher.launch(intent)
                        },
                        onFailure = {
                            isCheckoutPending = false
                            syncCartButtonStates()
                            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }

        syncCartButtonStates()
    }

    override fun onResume() {
        super.onResume()
        CustomerDisplayManager.attach(this)
        loadAllTaxes()
        discountEngine.loadDiscounts { refreshCart() }
    }

    override fun onDestroy() {
        detachOrderKitchenStatusListener()
        super.onDestroy()
    }

    private fun applyKitchenInProcessFromItemsSnapshot(snap: QuerySnapshot?) {
        val anyPreparing = snap?.documents?.any { doc ->
            doc.getString(OrderLineKdsStatus.FIELD)?.trim()?.equals(OrderLineKdsStatus.PREPARING, ignoreCase = true) == true
        } == true
        orderKitchenInProcess = anyPreparing
    }

    /** Subscribes to line [kdsStatus] so POS reacts when any KDS taps START (split stations). */
    private fun attachOrderKitchenStatusListener(orderId: String) {
        detachOrderKitchenStatusListener()
        orderKitchenStatusListener = db.collection("Orders").document(orderId)
            .collection("items")
            .addSnapshotListener { snap, _ ->
                applyKitchenInProcessFromItemsSnapshot(snap)
            }
    }

    private fun detachOrderKitchenStatusListener() {
        orderKitchenStatusListener?.remove()
        orderKitchenStatusListener = null
        orderKitchenInProcess = false
    }

    private fun loadAllTaxes() {
        db.collection("Taxes")
            .get()
            .addOnSuccessListener { snap ->
                allTaxes.clear()
                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: continue
                    val type = doc.getString("type") ?: continue
                    val amount = doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble() ?: continue
                    val enabled = doc.getBoolean("enabled") ?: true
                    allTaxes.add(TaxItem(id = doc.id, type = type, name = name, amount = amount, enabled = enabled))
                }
                refreshCart()
            }
    }

    // ----------------------------
    // LOAD EXISTING ORDER -> CART
    // ----------------------------

    private fun loadExistingOrderIntoCart(orderId: String) {
        attachOrderKitchenStatusListener(orderId)

        db.collection("Orders").document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->

                val nameFromOrder = orderDoc.getString("employeeName")
                if (!nameFromOrder.isNullOrBlank()) {
                    employeeName = nameFromOrder
                }

                val docOrderType = orderDoc.getString("orderType") ?: ""
                if (orderType.isBlank() && docOrderType.isNotBlank()) {
                    orderType = docOrderType
                }

                currentBatchId = orderDoc.getString("batchId")

                val refId = orderDoc.getString("preAuthReferenceId")
                val authCode = orderDoc.getString("preAuthAuthCode")
                if (!refId.isNullOrBlank() && !authCode.isNullOrBlank()) {
                    preAuthReferenceId = refId
                    preAuthAuthCode = authCode
                    preAuthCardLast4 = orderDoc.getString("cardLast4") ?: ""
                    preAuthCardBrand = orderDoc.getString("cardBrand") ?: ""
                    preAuthFirestoreDocId = orderDoc.getString("preAuthFirestoreDocId")
                    btnCheckout.text = "Capture"
                }

                kitchenSentForOrder = orderDoc.getTimestamp("lastKitchenSentAt") != null
                updateSendKitchenButtonLabel()
                if (tableId.isNullOrBlank()) {
                    tableId = orderDoc.getString("tableId")
                }
                if (tableLayoutId.isNullOrBlank()) {
                    tableLayoutId = orderDoc.getString("tableLayoutId")?.takeIf { it.isNotBlank() }
                }
                if (tableName.isNullOrBlank()) {
                    tableName = orderDoc.getString("tableName")
                }
                if (sectionId.isNullOrBlank()) {
                    sectionId = orderDoc.getString("sectionId")
                }
                if (sectionName.isNullOrBlank()) {
                    sectionName = orderDoc.getString("sectionName")
                }
                val docGuestCount = (orderDoc.getLong("guestCount") ?: 0L).toInt()
                if (guestCount == 0 && docGuestCount > 0) {
                    guestCount = docGuestCount
                    selectedGuest = 1
                }
                @Suppress("UNCHECKED_CAST")
                val docGuestNames = orderDoc.get("guestNames") as? List<String>
                if (!docGuestNames.isNullOrEmpty()) {
                    guestNames = docGuestNames.toMutableList()
                }

                val txtTableHeader = findViewById<TextView>(R.id.txtTableHeader)
                if (!tableName.isNullOrBlank()) {
                    val guestLabel = " • $guestCount guest${if (guestCount > 1) "s" else ""}"
                    txtTableHeader.text = "$tableName$guestLabel"
                    txtTableHeader.visibility = View.VISIBLE
                }

                customerId = orderDoc.getString("customerId")?.takeIf { it.isNotBlank() }
                customerName = orderDoc.getString("customerName")?.takeIf { it.isNotBlank() }
                customerPhone = orderDoc.getString("customerPhone")?.takeIf { it.isNotBlank() }
                customerEmail = orderDoc.getString("customerEmail")?.takeIf { it.isNotBlank() }
                updateCustomerDisplay()

                db.collection("Orders")
                    .document(orderId)
                    .collection("items")
                    .get()
                    .addOnSuccessListener { docs ->

                        cartMap.clear()

                        for (doc in docs.documents) {

                            val lineKey = doc.id

                            val itemId = doc.getString("itemId") ?: continue
                            val name = doc.getString("name") ?: "Item"
                            val qty = (doc.getLong("quantity") ?: 1L).toInt()

                            val basePriceInCents =
                                doc.getLong("basePriceInCents")
                                    ?: doc.getLong("unitPriceInCents")
                                    ?: 0L

                            val basePrice = basePriceInCents / 100.0

                            val modifiers = parseModifiers(doc.get("modifiers"))
                            val stock = 0L
                            val guest = (doc.getLong("guestNumber") ?: 0L).toInt()
                            val lineTaxMode = doc.getString("taxMode") ?: "INHERIT"
                            @Suppress("UNCHECKED_CAST")
                            val lineTaxIds = (doc.get("taxIds") as? List<String>) ?: emptyList()
                            val linePrinterLabel = doc.getString("printerLabel")?.trim()?.takeIf { it.isNotEmpty() }

                            cartMap[lineKey] = CartItem(
                                itemId = itemId,
                                name = name,
                                quantity = qty,
                                basePrice = basePrice,
                                stock = stock,
                                modifiers = modifiers,
                                guestNumber = guest,
                                taxMode = lineTaxMode,
                                taxIds = lineTaxIds,
                                printerLabel = linePrinterLabel,
                            )
                        }

                        refreshCart()
                    }
                    .addOnFailureListener { }
            }
    }

    private fun showAddCustomerDialog() {
        CustomerDialogHelper.showCustomerDialog(
            activity = this,
            initialName = customerName ?: "",
            initialPhone = customerPhone ?: "",
            initialEmail = customerEmail ?: "",
            onSave = { info ->
                customerId = info.id
                customerName = info.name
                customerPhone = info.phone.takeIf { it.isNotBlank() }
                customerEmail = info.email.takeIf { it.isNotBlank() }
                updateCustomerDisplay()

                val oid = currentOrderId
                if (!oid.isNullOrBlank()) {
                    if (info.id != null) {
                        orderEngine.updateOrderCustomer(
                            orderId = oid,
                            customerId = info.id,
                            customerName = info.name,
                            customerPhone = info.phone,
                            customerEmail = info.email,
                            onSuccess = { /* already updated UI */ },
                            onFailure = { e ->
                                Toast.makeText(this, "Failed to save customer: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        orderEngine.updateOrderCustomer(
                            orderId = oid,
                            customerName = info.name,
                            customerPhone = info.phone,
                            customerEmail = info.email,
                            onSuccess = { /* already updated UI */ },
                            onFailure = { e ->
                                Toast.makeText(this, "Failed to save customer: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                saveCustomerToFirestoreIfNew(info.name, info.phone, info.email)
            }
        )
    }

    private fun updateCustomerDisplay() {
        val txtCustomerValue = findViewById<TextView>(R.id.txtCustomerValue)
        txtCustomerValue.text = if (!customerName.isNullOrBlank()) customerName else "+ Add Customer"
    }

    private fun saveCustomerToFirestoreIfNew(name: String, phone: String, email: String) {
        CustomerDuplicateChecker.checkExists(db, name, email) { exists ->
            if (exists) {
                if (customerId == null) {
                    resolveCustomerId(name, email)
                }
                Toast.makeText(
                    this,
                    "Customer already in list. Attached to order.",
                    Toast.LENGTH_SHORT
                ).show()
                return@checkExists
            }
            val customer = hashMapOf<String, Any>(
                "name" to name,
                "nameSearch" to CustomerFirestoreHelper.nameSearchKey(name),
                "phone" to phone,
                "email" to email,
                "createdAt" to Timestamp.now(),
            )
            db.collection("Customers")
                .add(customer)
                .addOnSuccessListener { docRef ->
                    customerId = docRef.id
                    val oid = currentOrderId
                    if (!oid.isNullOrBlank()) {
                        db.collection("Orders").document(oid)
                            .update("customerId", docRef.id)
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("MenuActivity", "Failed to save customer to Customers collection", e)
                }
        }
    }

    private fun resolveCustomerId(name: String, email: String) {
        db.collection("Customers")
            .whereEqualTo("name", name)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val doc = snap.documents.firstOrNull()
                if (doc != null) {
                    customerId = doc.id
                    val oid = currentOrderId
                    if (!oid.isNullOrBlank()) {
                        db.collection("Orders").document(oid)
                            .update("customerId", doc.id)
                    }
                }
            }
    }

    private fun parseModifiers(raw: Any?): List<OrderModifier> {
        val list = raw as? List<*> ?: return emptyList()

        val out = mutableListOf<OrderModifier>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val name = map["name"]?.toString()
                ?: map["first"]?.toString()
                ?: continue
            val action = map["action"]?.toString() ?: "ADD"
            val price = if (action == "REMOVE") 0.0
                else (map["price"] as? Number)?.toDouble()
                    ?: (map["second"] as? Number)?.toDouble()
                    ?: 0.0
            val groupId = map["groupId"]?.toString() ?: ""
            val groupName = map["groupName"]?.toString() ?: ""
            val children = parseModifiers(map["children"])
            out.add(OrderModifier(name, action, price, groupId, groupName, children))
        }
        return out
    }

    private fun sumModifierPrices(modifiers: List<OrderModifier>): Double {
        return modifiers.sumOf { mod ->
            val selfPrice = if (mod.action == "ADD") mod.price else 0.0
            selfPrice + sumModifierPrices(mod.children)
        }
    }

    // ----------------------------
    // LOAD ACTIVE SCHEDULES
    // ----------------------------

    private fun loadActiveSchedules(onComplete: () -> Unit) {
        db.collection("menuSchedules").get()
            .addOnSuccessListener { snap ->
                val now = java.util.Calendar.getInstance()
                val dayOfWeek = when (now.get(java.util.Calendar.DAY_OF_WEEK)) {
                    java.util.Calendar.MONDAY -> "MON"
                    java.util.Calendar.TUESDAY -> "TUE"
                    java.util.Calendar.WEDNESDAY -> "WED"
                    java.util.Calendar.THURSDAY -> "THU"
                    java.util.Calendar.FRIDAY -> "FRI"
                    java.util.Calendar.SATURDAY -> "SAT"
                    java.util.Calendar.SUNDAY -> "SUN"
                    else -> ""
                }
                val currentTime = String.format(
                    Locale.US, "%02d:%02d",
                    now.get(java.util.Calendar.HOUR_OF_DAY),
                    now.get(java.util.Calendar.MINUTE)
                )

                val active = mutableSetOf<String>()
                for (doc in snap.documents) {
                    @Suppress("UNCHECKED_CAST")
                    val days = doc.get("days") as? List<String> ?: continue
                    val startTime = doc.getString("startTime") ?: continue
                    val endTime = doc.getString("endTime") ?: continue
                    if (days.contains(dayOfWeek) && currentTime >= startTime && currentTime <= endTime) {
                        active.add(doc.id)
                    }
                }
                activeScheduleIds = active
                onComplete()
            }
            .addOnFailureListener {
                activeScheduleIds = emptySet()
                onComplete()
            }
    }

    // ----------------------------
    // LOAD CATEGORIES / ITEMS
    // ----------------------------

    private val categoryAvailabilityMap = mutableMapOf<String, List<String>>()

    private fun loadSubcategories(onComplete: () -> Unit) {
        db.collection("subcategories")
            .get()
            .addOnSuccessListener { snap ->
                allSubcategories = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    SubcategoryModel(
                        id = doc.id,
                        name = name,
                        categoryId = doc.getString("categoryId") ?: "",
                        order = (doc.getLong("order") ?: 0L).toInt(),
                        kitchenLabel = doc.getString("kitchenLabel")?.trim().orEmpty(),
                    )
                }.sortedBy { it.order }
                onComplete()
            }
            .addOnFailureListener {
                allSubcategories = emptyList()
                onComplete()
            }
    }

    private val categoryButtons = mutableListOf<TextView>()

    private fun highlightSelectedCategory(selectedId: String) {
        for (btn in categoryButtons) {
            val catId = btn.tag as? String ?: continue
            if (catId == selectedId) {
                btn.setBackgroundResource(R.drawable.bg_category_button_selected)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.setBackgroundResource(R.drawable.bg_category_button)
                btn.setTextColor(Color.parseColor("#444444"))
            }
        }
    }

    private fun loadCategories() {
        categoryContainer.removeAllViews()
        categoryButtons.clear()

        val query = if (orderType.isNotBlank()) {
            db.collection("Categories")
                .whereArrayContains("availableOrderTypes", orderType)
        } else {
            db.collection("Categories")
        }

        query.get()
            .addOnSuccessListener { documents ->
                categoryAvailabilityMap.clear()
                categoryKitchenLabels.clear()

                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val categoryId = doc.id

                    @Suppress("UNCHECKED_CAST")
                    val catScheduleIds = (doc.get("scheduleIds") as? List<String>) ?: emptyList()
                    if (catScheduleIds.isNotEmpty()) {
                        if (activeScheduleIds.isEmpty() || catScheduleIds.none { it in activeScheduleIds }) {
                            continue
                        }
                    }

                    @Suppress("UNCHECKED_CAST")
                    val availableOrderTypes =
                        (doc.get("availableOrderTypes") as? List<String>) ?: emptyList()
                    categoryAvailabilityMap[categoryId] = availableOrderTypes
                    val catKitchenLabel = doc.getString("kitchenLabel")?.trim().orEmpty()
                    if (catKitchenLabel.isNotEmpty()) categoryKitchenLabels[categoryId] = catKitchenLabel

                    val btn = TextView(this).apply {
                        text = name
                        tag = categoryId
                        textSize = 13f
                        setTextColor(Color.parseColor("#444444"))
                        setBackgroundResource(R.drawable.bg_category_button)
                        gravity = Gravity.CENTER
                        setPadding(8, 20, 8, 20)
                        isAllCaps = true
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(2, 3, 2, 3) }
                        setOnClickListener {
                            currentSubcategoryId = null
                            editItemSearch.text.clear()
                            loadItems(categoryId)
                        }
                    }
                    categoryButtons.add(btn)
                    categoryContainer.addView(btn)
                }

                loadSubcategories {}
            }
    }

    private fun buildSubcategoryChips(categoryId: String) {
        subcategoryChipHost.removeAllViews()
        val subs = allSubcategories.filter { it.categoryId == categoryId }
        if (subs.isEmpty()) return

        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 6)
        }

        val allBtn = TextView(this).apply {
            text = "All"
            textSize = 12f
            setPadding(24, 10, 24, 10)
            setBackgroundResource(
                if (currentSubcategoryId == null) R.drawable.bg_category_button_selected else R.drawable.bg_category_button
            )
            setTextColor(if (currentSubcategoryId == null) Color.WHITE else Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 6, 0) }
            setOnClickListener {
                currentSubcategoryId = null
                loadItems(categoryId)
            }
        }
        chipRow.addView(allBtn)

        for (sub in subs) {
            val isActive = currentSubcategoryId == sub.id
            val btn = TextView(this).apply {
                text = sub.name
                textSize = 12f
                setPadding(24, 10, 24, 10)
                setBackgroundResource(
                    if (isActive) R.drawable.bg_category_button_selected else R.drawable.bg_category_button
                )
                setTextColor(if (isActive) Color.WHITE else Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    currentSubcategoryId = sub.id
                    loadItems(categoryId)
                }
            }
            chipRow.addView(btn)
        }

        scrollView.addView(chipRow)
        subcategoryChipHost.addView(scrollView)
    }

    private fun subcategoryIdForCategory(doc: com.google.firebase.firestore.DocumentSnapshot, categoryId: String): String {
        @Suppress("UNCHECKED_CAST")
        val byCat = doc.get("subcategoryByCategoryId") as? Map<*, *>
        if (byCat != null) {
            val v = byCat[categoryId] as? String
            if (v != null) return v
        }
        return doc.getString("subcategoryId") ?: ""
    }

    data class MenuGridItem(
        val itemId: String,
        val name: String,
        val price: Double,
        val stock: Long,
        val isScheduled: Boolean,
        val isOutOfStock: Boolean,
        val taxMode: String,
        val taxIds: List<String>,
        val printerLabel: String?,
        val subcategoryId: String
    )

    private fun onGridItemClicked(item: MenuGridItem) {
        if (item.isOutOfStock) return
        val effectiveStock = if (stockCountingEnabled) item.stock else Long.MAX_VALUE
        checkAndShowModifiers(item.itemId, item.name, item.price, effectiveStock, item.taxMode, item.taxIds)
    }

    private fun applySearchFilter() {
        val query = editItemSearch.text.toString().trim().lowercase(Locale.getDefault())

        if (query.isNotEmpty()) {
            val hasCategorySelected = currentCategoryId != null && cachedCategoryItems.isNotEmpty()

            if (hasCategorySelected) {
                // Search within the selected category only
                val subFilter = currentSubcategoryId
                val filtered = cachedCategoryItems.filter { item ->
                    val matchesSearch = item.name.lowercase(Locale.getDefault()).contains(query)
                    val matchesSub = subFilter == null || item.subcategoryId == subFilter
                    matchesSearch && matchesSub
                }
                gridAdapter.submitList(filtered)
                if (filtered.isEmpty()) {
                    txtEmptyMessage.text = "No items match \"$query\" in this category"
                    emptyState.visibility = View.VISIBLE
                    itemGrid.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    itemGrid.visibility = View.VISIBLE
                }
            } else {
                // No category selected — global search across ALL items
                subcategoryChipHost.removeAllViews()
                val source = if (allMenuItemsLoaded) allMenuItemsCache else cachedCategoryItems
                val filtered = source.filter { item ->
                    item.name.lowercase(Locale.getDefault()).contains(query)
                }
                gridAdapter.submitList(filtered)
                if (filtered.isEmpty()) {
                    txtEmptyMessage.text = "No items match \"$query\""
                    emptyState.visibility = View.VISIBLE
                    itemGrid.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    itemGrid.visibility = View.VISIBLE
                }
            }
        } else {
            // No search text — show current category items
            val subFilter = currentSubcategoryId
            val filtered = cachedCategoryItems.filter { item ->
                subFilter == null || item.subcategoryId == subFilter
            }
            gridAdapter.submitList(filtered)
            if (filtered.isEmpty() && currentCategoryId != null) {
                txtEmptyMessage.text = "No items in this category"
                emptyState.visibility = View.VISIBLE
                itemGrid.visibility = View.GONE
            } else if (filtered.isEmpty()) {
                txtEmptyMessage.text = "Select a category to see items"
                emptyState.visibility = View.VISIBLE
                itemGrid.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                itemGrid.visibility = View.VISIBLE
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadAllMenuItems() {
        db.collection("MenuItems").get()
            .addOnSuccessListener { documents ->
                val seen = mutableSetOf<String>()
                val items = mutableListOf<MenuGridItem>()

                for (doc in documents) {
                    val itemId = doc.id
                    if (seen.contains(itemId)) continue

                    val isScheduled = doc.getBoolean("isScheduled") ?: false
                    if (isScheduled) {
                        val scheduleIds = (doc.get("scheduleIds") as? List<String>) ?: emptyList()
                        if (activeScheduleIds.isEmpty() || scheduleIds.none { it in activeScheduleIds }) {
                            continue
                        }
                    }
                    seen.add(itemId)

                    val name = doc.getString("name") ?: continue

                    val channelsRaw = doc.get("channels") as? Map<String, Any>
                    val channelPos = (channelsRaw?.get("pos") as? Boolean) ?: true
                    if (!channelPos) continue

                    val pricingRaw = doc.get("pricing") as? Map<String, Any>
                    val pricingPos = (pricingRaw?.get("pos") as? Number)?.toDouble()

                    val pricesRaw = doc.get("prices") as? Map<String, Any>
                    val pricesMap = pricesRaw?.mapValues {
                        (it.value as? Number)?.toDouble() ?: 0.0
                    } ?: emptyMap()
                    val price = pricingPos
                        ?: if (pricesMap.isNotEmpty()) pricesMap.values.first()
                        else doc.getDouble("price") ?: 0.0
                    val stock = doc.getLong("stock") ?: 0L
                    val itemTaxMode = doc.getString("taxMode") ?: "INHERIT"
                    val itemTaxIds = (doc.get("taxIds") as? List<String>) ?: emptyList()

                    val itemAvailability = doc.get("availableOrderTypes") as? List<String>
                    if (orderType.isNotBlank() && itemAvailability != null &&
                        itemAvailability.isNotEmpty() && !itemAvailability.contains(orderType)
                    ) {
                        continue
                    }

                    val itemCatId = doc.getString("categoryId").orEmpty()
                    val itemSubId = doc.getString("subcategoryId").orEmpty()
                    val itemLabel = MenuItemRoutingLabel.fromMenuItemDoc(doc)
                    val subLabel = allSubcategories.firstOrNull { it.id == itemSubId }?.kitchenLabel
                    val catLabel = categoryKitchenLabels[itemCatId]
                    val printerLabel = MenuItemRoutingLabel.resolve(itemLabel, subLabel, catLabel)

                    items.add(
                        MenuGridItem(
                            itemId = itemId,
                            name = name,
                            price = price,
                            stock = stock,
                            isScheduled = isScheduled,
                            isOutOfStock = stockCountingEnabled && stock <= 0,
                            taxMode = itemTaxMode,
                            taxIds = itemTaxIds,
                            printerLabel = printerLabel,
                            subcategoryId = itemSubId
                        )
                    )
                }

                allMenuItemsCache = items
                allMenuItemsLoaded = true
            }
    }

    private fun loadItems(categoryId: String) {
        currentCategoryId = categoryId
        highlightSelectedCategory(categoryId)
        val generation = ++loadItemsGeneration

        val catAvailability = categoryAvailabilityMap[categoryId] ?: emptyList()

        buildSubcategoryChips(categoryId)

        db.collection("MenuItems")
            .where(
                Filter.or(
                    Filter.equalTo("categoryId", categoryId),
                    Filter.arrayContains("categoryIds", categoryId),
                ),
            )
            .get()
            .addOnSuccessListener { documents ->
                if (generation != loadItemsGeneration) return@addOnSuccessListener

                val seen = mutableSetOf<String>()
                val items = mutableListOf<MenuGridItem>()

                for (doc in documents) {
                    val itemId = doc.id
                    if (seen.contains(itemId)) continue

                    val isScheduled = doc.getBoolean("isScheduled") ?: false

                    if (isScheduled) {
                        @Suppress("UNCHECKED_CAST")
                        val scheduleIds = (doc.get("scheduleIds") as? List<String>) ?: emptyList()
                        if (activeScheduleIds.isEmpty() || scheduleIds.none { it in activeScheduleIds }) {
                            continue
                        }
                    }

                    seen.add(itemId)

                    val name = doc.getString("name") ?: continue

                    @Suppress("UNCHECKED_CAST")
                    val channelsRaw = doc.get("channels") as? Map<String, Any>
                    val channelPos = (channelsRaw?.get("pos") as? Boolean) ?: true
                    if (!channelPos) continue

                    @Suppress("UNCHECKED_CAST")
                    val pricingRaw = doc.get("pricing") as? Map<String, Any>
                    val pricingPos = (pricingRaw?.get("pos") as? Number)?.toDouble()

                    @Suppress("UNCHECKED_CAST")
                    val pricesRaw = doc.get("prices") as? Map<String, Any>
                    val pricesMap = pricesRaw?.mapValues {
                        (it.value as? Number)?.toDouble() ?: 0.0
                    } ?: emptyMap()
                    val price = pricingPos
                        ?: if (pricesMap.isNotEmpty()) pricesMap.values.first()
                        else doc.getDouble("price") ?: 0.0
                    val stock = doc.getLong("stock") ?: 0L
                    val itemTaxMode = doc.getString("taxMode") ?: "INHERIT"
                    @Suppress("UNCHECKED_CAST")
                    val itemTaxIds = (doc.get("taxIds") as? List<String>) ?: emptyList()

                    @Suppress("UNCHECKED_CAST")
                    val itemAvailability =
                        doc.get("availableOrderTypes") as? List<String>

                    if (orderType.isNotBlank()) {
                        val effectiveTypes = itemAvailability ?: catAvailability
                        if (effectiveTypes.isNotEmpty() && !effectiveTypes.contains(orderType)) {
                            continue
                        }
                    }

                    val subcategoryId = subcategoryIdForCategory(doc, categoryId)
                    val itemLabel = MenuItemRoutingLabel.fromMenuItemDoc(doc)
                    val subLabel = allSubcategories.firstOrNull { it.id == subcategoryId }?.kitchenLabel
                    val catLabel = categoryKitchenLabels[categoryId]
                    val printerLabel = MenuItemRoutingLabel.resolve(itemLabel, subLabel, catLabel)

                    items.add(
                        MenuGridItem(
                            itemId = itemId,
                            name = name,
                            price = price,
                            stock = stock,
                            isScheduled = isScheduled,
                            isOutOfStock = stockCountingEnabled && stock <= 0,
                            taxMode = itemTaxMode,
                            taxIds = itemTaxIds,
                            printerLabel = printerLabel,
                            subcategoryId = subcategoryId
                        )
                    )
                }

                cachedCategoryItems = items
                applySearchFilter()
            }
    }

    // ── Grid adapter ────────────────────────────────────────────

    inner class MenuGridAdapter(
        private val onClick: (MenuGridItem) -> Unit
    ) : RecyclerView.Adapter<MenuGridAdapter.VH>() {

        private var list = listOf<MenuGridItem>()

        fun submitList(newList: List<MenuGridItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_grid_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(list[position])
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val root: View = v.findViewById(R.id.cardRoot)
            private val txtName: TextView = v.findViewById(R.id.txtItemName)
            private val txtPrice: TextView = v.findViewById(R.id.txtItemPrice)
            private val txtStock: TextView = v.findViewById(R.id.txtItemStock)

            fun bind(item: MenuGridItem) {
                txtName.text = item.name
                txtPrice.text = "$${String.format(Locale.US, "%.2f", item.price)}"

                if (item.isOutOfStock) {
                    root.setBackgroundResource(R.drawable.bg_menu_item_card_disabled)
                    txtName.setTextColor(Color.parseColor("#888888"))
                    txtPrice.setTextColor(Color.parseColor("#AAAAAA"))
                    txtStock.visibility = View.VISIBLE
                    txtStock.text = "OUT OF STOCK"
                    txtStock.setTextColor(Color.parseColor("#CC0000"))
                    root.isEnabled = false
                    root.alpha = 0.6f
                } else {
                    root.setBackgroundResource(
                        if (item.isScheduled) R.drawable.bg_menu_item_card_scheduled
                        else R.drawable.bg_menu_item_card
                    )
                    txtName.setTextColor(Color.WHITE)
                    txtPrice.setTextColor(Color.parseColor("#E0D6F5"))
                    root.isEnabled = true
                    root.alpha = 1f

                    if (stockCountingEnabled) {
                        txtStock.visibility = View.VISIBLE
                        txtStock.text = "Stock: ${item.stock}"
                        txtStock.setTextColor(Color.parseColor("#D4C8F0"))
                    } else {
                        txtStock.visibility = View.GONE
                    }
                }

                root.setOnClickListener { if (!item.isOutOfStock) onClick(item) }
            }
        }
    }

    // ----------------------------
    // MODIFIERS (relational model with backward compatibility)
    // ----------------------------

    private fun checkAndShowModifiers(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList()
    ) {
        db.collection("MenuItems").document(itemId).get()
            .addOnSuccessListener { itemDoc ->
                val itemLabel = MenuItemRoutingLabel.fromMenuItemDoc(itemDoc)
                val itemCatId = itemDoc.getString("categoryId").orEmpty()
                val itemSubId = itemDoc.getString("subcategoryId").orEmpty()
                val subLabel = allSubcategories.firstOrNull { it.id == itemSubId }?.kitchenLabel
                val catLabel = categoryKitchenLabels[itemCatId]
                val printerLabel = MenuItemRoutingLabel.resolve(itemLabel, subLabel, catLabel)
                @Suppress("UNCHECKED_CAST")
                val embedded = (itemDoc.get("modifierGroupIds") as? List<String>)
                    ?.filter { it.isNotBlank() } ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val assigned = (itemDoc.get("assignedModifierGroupIds") as? List<String>)
                    ?.filter { it.isNotBlank() } ?: emptyList()
                val merged = (embedded + assigned).distinct()

                if (merged.isNotEmpty()) {
                    showModifierDialog(itemId, name, basePrice, stock, merged, taxMode, taxIds, printerLabel)
                } else {
                    db.collection("ItemModifierGroups")
                        .whereEqualTo("itemId", itemId)
                        .orderBy("displayOrder")
                        .get()
                        .addOnSuccessListener { documents ->
                            if (documents.isEmpty) {
                                addToCart(itemId, name, basePrice, stock, emptyList(), taxMode, taxIds, printerLabel)
                            } else {
                                val groupIds = documents.mapNotNull { it.getString("groupId") }
                                showModifierDialog(itemId, name, basePrice, stock, groupIds, taxMode, taxIds, printerLabel)
                            }
                        }
                }
            }
    }

    private data class SelectedOption(
        val optionId: String,
        val optionName: String,
        val price: Double,
        val action: String,
        val triggersModifierGroupIds: List<String> = emptyList()
    )

    /** Firestore `action` on an option, or forced REMOVE when the whole group is remove-style. */
    private fun normalizedModifierOptionAction(raw: String?, groupType: String): String {
        if (groupType.trim().uppercase(Locale.US) == "REMOVE") return "REMOVE"
        return if (raw?.trim()?.uppercase(Locale.US) == "REMOVE") "REMOVE" else "ADD"
    }

    private fun effectiveOptionAction(opt: ModifierOptionEntry, group: GroupInfo): String {
        if (group.groupType.trim().uppercase(Locale.US) == "REMOVE") return "REMOVE"
        return if (opt.action.trim().uppercase(Locale.US) == "REMOVE") "REMOVE" else "ADD"
    }

    private fun showModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        groupIds: List<String>,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList(),
        printerLabel: String? = null,
    ) {
        if (groupIds.isEmpty()) {
            addToCart(itemId, name, basePrice, stock, emptyList(), taxMode, taxIds, printerLabel)
            return
        }

        val orderIndex = groupIds.withIndex().associate { it.value to it.index }
        val allGroupInfos = mutableMapOf<String, GroupInfo>()
        val triggeredGroupIds = mutableSetOf<String>()
        var pending = groupIds.size

        fun parseOptions(raw: List<Map<String, Any>>?, groupType: String): List<ModifierOptionEntry> {
            return raw?.mapNotNull { map ->
                val oN = map["name"]?.toString() ?: return@mapNotNull null
                val oP = (map["price"] as? Number)?.toDouble() ?: 0.0
                val oId = map["id"]?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val triggers = (map["triggersModifierGroupIds"] as? List<String>) ?: emptyList()
                val act = normalizedModifierOptionAction(map["action"] as? String, groupType)
                ModifierOptionEntry(oId, oN, oP, triggers, act)
            } ?: emptyList()
        }

        fun fetchTriggeredGroups(callback: () -> Unit) {
            val additional = triggeredGroupIds.filter { it !in allGroupInfos }.toSet()
            if (additional.isEmpty()) { callback(); return }
            var p = additional.size
            for (id in additional) {
                db.collection("ModifierGroups").document(id).get()
                    .addOnSuccessListener { doc ->
                        val gName = doc.getString("name") ?: ""
                        val isReq = doc.getBoolean("required") ?: false
                        val maxSel = doc.getLong("maxSelection")?.toInt() ?: 1
                        val gType = doc.getString("groupType") ?: "ADD"
                        @Suppress("UNCHECKED_CAST")
                        val opts = parseOptions(doc.get("options") as? List<Map<String, Any>>, gType)
                        if (gName.isNotEmpty()) {
                            allGroupInfos[id] = GroupInfo(id, gName, isReq, maxSel, gType, opts)
                            opts.forEach { triggeredGroupIds.addAll(it.triggersModifierGroupIds) }
                        }
                        p--
                        if (p == 0) fetchTriggeredGroups(callback)
                    }
                    .addOnFailureListener { p--; if (p == 0) fetchTriggeredGroups(callback) }
            }
        }

        fun mergeLegacyOptions(callback: () -> Unit) {
            val ids = allGroupInfos.keys.toList()
            if (ids.isEmpty()) { callback(); return }
            val chunks = ids.chunked(30)
            var remaining = chunks.size
            for (chunk in chunks) {
                db.collection("ModifierOptions")
                    .whereIn("groupId", chunk)
                    .get()
                    .addOnSuccessListener { snap ->
                        for (doc in snap) {
                            val gId = doc.getString("groupId") ?: continue
                            val info = allGroupInfos[gId] ?: continue
                            if (info.options.any { it.id == doc.id }) continue
                            val oN = doc.getString("name") ?: continue
                            val oP = doc.getDouble("price") ?: 0.0
                            @Suppress("UNCHECKED_CAST")
                            val tr = (doc.get("triggersModifierGroupIds") as? List<String>) ?: emptyList()
                            val act = normalizedModifierOptionAction(doc.getString("action"), info.groupType)
                            val entry = ModifierOptionEntry(doc.id, oN, oP, tr, act)
                            allGroupInfos[gId] = info.copy(options = info.options + entry)
                            tr.forEach { triggeredGroupIds.add(it) }
                        }
                        remaining--
                        if (remaining == 0) callback()
                    }
                    .addOnFailureListener { remaining--; if (remaining == 0) callback() }
            }
        }

        fun onAllGroupsReady() {
            fetchTriggeredGroups {
                mergeLegacyOptions {
                    buildNestedModifierDialog(
                        itemId, name, basePrice, stock,
                        allGroupInfos, orderIndex, triggeredGroupIds,
                        taxMode, taxIds, printerLabel,
                    )
                }
            }
        }

        for (groupId in groupIds) {
            db.collection("ModifierGroups").document(groupId).get()
                .addOnSuccessListener { groupDoc ->
                    val gName = groupDoc.getString("name") ?: ""
                    val isReq = groupDoc.getBoolean("required") ?: false
                    val maxSel = groupDoc.getLong("maxSelection")?.toInt() ?: 1
                    val gType = groupDoc.getString("groupType") ?: "ADD"
                    @Suppress("UNCHECKED_CAST")
                    val opts = parseOptions(groupDoc.get("options") as? List<Map<String, Any>>, gType)
                    if (gName.isNotEmpty()) {
                        allGroupInfos[groupId] = GroupInfo(groupId, gName, isReq, maxSel, gType, opts)
                        opts.forEach { triggeredGroupIds.addAll(it.triggersModifierGroupIds) }
                    }
                    pending--
                    if (pending == 0) onAllGroupsReady()
                }
                .addOnFailureListener {
                    pending--
                    if (pending == 0) onAllGroupsReady()
                }
        }
    }

    private fun buildNestedModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        allGroupInfos: Map<String, GroupInfo>,
        orderIndex: Map<String, Int>,
        triggeredGroupIds: Set<String>,
        taxMode: String,
        taxIds: List<String>,
        printerLabel: String? = null,
    ) {
        val selectedOptionsPerGroup = mutableMapOf<String, MutableList<SelectedOption>>()
        val groupContainers = mutableMapOf<String, LinearLayout>()
        val visibleGroupIds = mutableSetOf<String>()
        val radioGroupPreviousTriggers = mutableMapOf<String, List<String>>()

        val topLevelGroupIds = orderIndex.keys.filter { it !in triggeredGroupIds }
        val topLevelGroups = topLevelGroupIds
            .mapNotNull { allGroupInfos[it] }
            .sortedWith(
                compareBy<GroupInfo> { !it.isRequired }
                    .thenBy { orderIndex[it.groupId] ?: 0 }
            )
        topLevelGroups.forEach { visibleGroupIds.add(it.groupId) }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        fun hideTriggeredGroups(triggerIds: List<String>) {
            for (tid in triggerIds) {
                if (tid !in visibleGroupIds) continue
                val sels = selectedOptionsPerGroup[tid]?.toList() ?: emptyList()
                for (sel in sels) {
                    if (sel.triggersModifierGroupIds.isNotEmpty()) {
                        hideTriggeredGroups(sel.triggersModifierGroupIds)
                    }
                }
                visibleGroupIds.remove(tid)
                selectedOptionsPerGroup.remove(tid)
                radioGroupPreviousTriggers.remove(tid)
                suppressModifierCallbacks = true
                resetGroupSelectionUI(groupContainers[tid])
                suppressModifierCallbacks = false
                groupContainers[tid]?.visibility = View.GONE
            }
        }

        fun showTriggeredGroups(triggerIds: List<String>) {
            for (tid in triggerIds) {
                if (tid in visibleGroupIds) continue
                visibleGroupIds.add(tid)
                groupContainers[tid]?.visibility = View.VISIBLE
            }
        }

        fun createGroupSection(group: GroupInfo, isTriggered: Boolean): LinearLayout {
            val isRemoveGroup = group.groupType == "REMOVE"

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    if (isTriggered) 60 else 0,
                    if (isTriggered) 16 else 40,
                    0,
                    if (isTriggered) 16 else 40
                )
                if (isTriggered) visibility = View.GONE
            }

            val title = TextView(this).apply {
                text = when {
                    isTriggered -> "\u2192 ${group.groupName}"
                    isRemoveGroup -> "REMOVE INGREDIENTS"
                    else -> group.groupName
                }
                textSize = if (isTriggered) 16f else 18f
                setTypeface(null, Typeface.BOLD)
                when {
                    isRemoveGroup -> setTextColor(Color.parseColor("#D32F2F"))
                    isTriggered -> setTextColor(Color.parseColor("#6366F1"))
                }
            }

            val subtitle = TextView(this)
            if (isRemoveGroup) {
                subtitle.text = "Tap to remove ingredients"
                subtitle.setTextColor(Color.GRAY)
            } else {
                val subtitleFull =
                    if (group.isRequired) "Required \u2022 Select up to ${group.maxSelection}"
                    else "Optional \u2022 Select up to ${group.maxSelection}"
                val spannable = SpannableString(subtitleFull)
                if (group.isRequired) {
                    spannable.setSpan(StyleSpan(Typeface.BOLD), 0, 8, 0)
                    spannable.setSpan(RelativeSizeSpan(1.25f), 0, 8, 0)
                    spannable.setSpan(ForegroundColorSpan(Color.parseColor("#1A1A1A")), 0, 8, 0)
                    spannable.setSpan(ForegroundColorSpan(Color.GRAY), 8, subtitleFull.length, 0)
                } else {
                    spannable.setSpan(ForegroundColorSpan(Color.GRAY), 0, subtitleFull.length, 0)
                }
                subtitle.text = spannable
            }
            subtitle.setPadding(0, 8, 0, 16)

            val divider = View(this).apply {
                setBackgroundColor(if (isRemoveGroup) Color.parseColor("#D32F2F") else Color.LTGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 2
                )
            }

            container.addView(title)
            container.addView(subtitle)
            container.addView(divider)

            renderNestedOptions(
                group.options, group, container, selectedOptionsPerGroup,
                radioGroupPreviousTriggers,
                { showTriggeredGroups(it) },
                { hideTriggeredGroups(it) }
            )

            return container
        }

        for (group in topLevelGroups) {
            val container = createGroupSection(group, false)
            groupContainers[group.groupId] = container
            mainLayout.addView(container)

            for (opt in group.options) {
                for (tid in opt.triggersModifierGroupIds) {
                    if (tid !in groupContainers) {
                        val tGroup = allGroupInfos[tid] ?: continue
                        val tContainer = createGroupSection(tGroup, true)
                        groupContainers[tid] = tContainer
                        mainLayout.addView(tContainer)
                        for (tOpt in tGroup.options) {
                            for (tid2 in tOpt.triggersModifierGroupIds) {
                                if (tid2 !in groupContainers) {
                                    val tGroup2 = allGroupInfos[tid2] ?: continue
                                    val tContainer2 = createGroupSection(tGroup2, true)
                                    groupContainers[tid2] = tContainer2
                                    mainLayout.addView(tContainer2)
                                }
                            }
                        }
                    }
                }
            }
        }

        for ((gId, gInfo) in allGroupInfos) {
            if (gId !in groupContainers) {
                val container = createGroupSection(gInfo, true)
                groupContainers[gId] = container
                mainLayout.addView(container)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Options")
            .setView(ScrollView(this).apply { addView(mainLayout) })
            .setPositiveButton("Add to Cart", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.setOnClickListener {
                for ((gId, gInfo) in allGroupInfos) {
                    if (gId !in visibleGroupIds) continue
                    if (!gInfo.isRequired) continue
                    val count = selectedOptionsPerGroup[gId]?.size ?: 0
                    if (count == 0) {
                        Toast.makeText(
                            this,
                            "Please select required option for ${gInfo.groupName}.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                }

                val modifiers = buildOrderModifiers(
                    topLevelGroupIds, allGroupInfos,
                    selectedOptionsPerGroup, visibleGroupIds
                )
                addToCart(itemId, name, basePrice, stock, modifiers, taxMode, taxIds, printerLabel)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun resetGroupSelectionUI(container: LinearLayout?) {
        container ?: return
        for (i in 0 until container.childCount) {
            when (val child = container.getChildAt(i)) {
                is CheckBox -> child.isChecked = false
                is RadioGroup -> child.clearCheck()
                is LinearLayout -> resetGroupSelectionUI(child)
            }
        }
    }

    private fun renderNestedOptions(
        options: List<ModifierOptionEntry>,
        group: GroupInfo,
        groupContainer: LinearLayout,
        selectedOptionsPerGroup: MutableMap<String, MutableList<SelectedOption>>,
        radioGroupPreviousTriggers: MutableMap<String, List<String>>,
        onTriggersActivated: (List<String>) -> Unit,
        onTriggersDeactivated: (List<String>) -> Unit
    ) {
        val groupId = group.groupId
        val isRemoveGroup = group.groupType == "REMOVE"
        val maxSelection = group.maxSelection

        if (isRemoveGroup) {
            for (opt in options) {
                val checkBox = CheckBox(this).apply {
                    text = opt.name
                    setTextColor(Color.parseColor("#D32F2F"))
                }
                groupContainer.addView(checkBox)

                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (suppressModifierCallbacks) return@setOnCheckedChangeListener
                    val sels = selectedOptionsPerGroup.getOrPut(groupId) { mutableListOf() }
                    if (isChecked) {
                        sels.add(SelectedOption(opt.id, opt.name, 0.0, "REMOVE", opt.triggersModifierGroupIds))
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersActivated(opt.triggersModifierGroupIds)
                    } else {
                        sels.removeAll { it.optionId == opt.id }
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersDeactivated(opt.triggersModifierGroupIds)
                    }
                }
            }
        } else if (maxSelection == 1) {
            val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            groupContainer.addView(radioGroup)

            for (opt in options) {
                val lineAction = effectiveOptionAction(opt, group)
                val isLineRemove = lineAction == "REMOVE"
                val radioButton = RadioButton(this).apply {
                    text = if (isLineRemove) {
                        opt.name
                    } else {
                        "${opt.name} +$${String.format(Locale.US, "%.2f", opt.price)}"
                    }
                    if (isLineRemove) setTextColor(Color.parseColor("#D32F2F"))
                }
                radioGroup.addView(radioButton)

                radioButton.setOnClickListener {
                    if (suppressModifierCallbacks) return@setOnClickListener
                    val prev = radioGroupPreviousTriggers[groupId] ?: emptyList()
                    if (prev.isNotEmpty()) onTriggersDeactivated(prev)

                    val sels = selectedOptionsPerGroup.getOrPut(groupId) { mutableListOf() }
                    sels.clear()
                    val priceLine = if (isLineRemove) 0.0 else opt.price
                    sels.add(SelectedOption(opt.id, opt.name, priceLine, lineAction, opt.triggersModifierGroupIds))

                    radioGroupPreviousTriggers[groupId] = opt.triggersModifierGroupIds
                    if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersActivated(opt.triggersModifierGroupIds)
                }
            }
        } else {
            for (opt in options) {
                val lineAction = effectiveOptionAction(opt, group)
                val isLineRemove = lineAction == "REMOVE"
                val checkBox = CheckBox(this).apply {
                    text = if (isLineRemove) {
                        opt.name
                    } else {
                        "${opt.name} +$${String.format(Locale.US, "%.2f", opt.price)}"
                    }
                    if (isLineRemove) setTextColor(Color.parseColor("#D32F2F"))
                }
                groupContainer.addView(checkBox)

                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (suppressModifierCallbacks) return@setOnCheckedChangeListener
                    val sels = selectedOptionsPerGroup.getOrPut(groupId) { mutableListOf() }
                    if (isChecked) {
                        if (sels.size >= maxSelection) {
                            checkBox.isChecked = false
                            Toast.makeText(
                                this,
                                "Maximum $maxSelection selections allowed",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnCheckedChangeListener
                        }
                        val priceLine = if (isLineRemove) 0.0 else opt.price
                        sels.add(SelectedOption(opt.id, opt.name, priceLine, lineAction, opt.triggersModifierGroupIds))
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersActivated(opt.triggersModifierGroupIds)
                    } else {
                        sels.removeAll { it.optionId == opt.id }
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersDeactivated(opt.triggersModifierGroupIds)
                    }
                }
            }
        }
    }

    private fun buildOrderModifiers(
        topLevelGroupIds: List<String>,
        allGroupInfos: Map<String, GroupInfo>,
        selectedOptionsPerGroup: Map<String, List<SelectedOption>>,
        visibleGroupIds: Set<String>
    ): List<OrderModifier> {
        val result = mutableListOf<OrderModifier>()
        for (gId in topLevelGroupIds) {
            val group = allGroupInfos[gId] ?: continue
            val selections = selectedOptionsPerGroup[gId] ?: continue
            for (sel in selections) {
                val children = buildChildModifiers(
                    sel.triggersModifierGroupIds, allGroupInfos,
                    selectedOptionsPerGroup, visibleGroupIds
                )
                result.add(
                    OrderModifier(
                        name = sel.optionName,
                        action = sel.action,
                        price = sel.price,
                        groupId = gId,
                        groupName = group.groupName,
                        children = children
                    )
                )
            }
        }
        return result
    }

    private fun buildChildModifiers(
        triggerIds: List<String>,
        allGroupInfos: Map<String, GroupInfo>,
        selectedOptionsPerGroup: Map<String, List<SelectedOption>>,
        visibleGroupIds: Set<String>
    ): List<OrderModifier> {
        val children = mutableListOf<OrderModifier>()
        for (tid in triggerIds) {
            if (tid !in visibleGroupIds) continue
            val tGroup = allGroupInfos[tid] ?: continue
            val tSels = selectedOptionsPerGroup[tid] ?: continue
            for (tSel in tSels) {
                val grandchildren = buildChildModifiers(
                    tSel.triggersModifierGroupIds, allGroupInfos,
                    selectedOptionsPerGroup, visibleGroupIds
                )
                children.add(
                    OrderModifier(
                        name = tSel.optionName,
                        action = tSel.action,
                        price = tSel.price,
                        groupId = tid,
                        groupName = tGroup.groupName,
                        children = grandchildren
                    )
                )
            }
        }
        return children
    }

    private data class GroupInfo(
        val groupId: String,
        val groupName: String,
        val isRequired: Boolean,
        val maxSelection: Int,
        val groupType: String = "ADD",
        val options: List<ModifierOptionEntry> = emptyList()
    )

    // ----------------------------
    // CART LOGIC (separate lines by modifiers)
    // ----------------------------

    private fun syncLineToFirestore(
        lineKey: String,
        cartItem: CartItem,
        isNewLine: Boolean,
        kitchenQuantityDelta: Int,
        onFailure: (Exception) -> Unit = {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
        },
    ) {
        val oid = currentOrderId ?: return
        orderEngine.upsertLineItem(
            orderId = oid,
            lineKey = lineKey,
            input = OrderEngine.LineItemInput(
                itemId = cartItem.itemId,
                name = cartItem.name,
                quantity = cartItem.quantity,
                basePrice = cartItem.basePrice,
                modifiers = cartItem.modifiers,
                guestNumber = cartItem.guestNumber,
                taxMode = cartItem.taxMode,
                taxIds = cartItem.taxIds,
                printerLabel = cartItem.printerLabel,
            ),
            isNewLine = isNewLine,
            onSuccess = { },
            onFailure = onFailure,
        )
    }

    private fun syncCartButtonStates() {
        if (!::btnCheckout.isInitialized) return
        val hasItems = cartMap.isNotEmpty()
        val busy = isSendingToKitchen || isCheckoutPending || isCaptureFlowActive
        btnSendKitchen.isEnabled = hasItems && !busy
        btnCheckout.isEnabled = hasItems && !busy
    }

    private fun updateSendKitchenButtonLabel() {
        if (!::btnSendKitchen.isInitialized) return
        if (isSendingToKitchen) {
            btnSendKitchen.text = getString(R.string.cart_sending_kitchen)
            return
        }
        btnSendKitchen.text = if (kitchenSentForOrder) {
            getString(R.string.cart_send_update)
        } else {
            getString(R.string.cart_send_to_kitchen)
        }
    }

    // ── Kitchen Notes per routing label ─────────────────────────────

    private fun showKitchenNotesDialog() {
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()

        val til = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding(padH, padV, padH, 0)
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "Notes"
        }
        val edit = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 6
        }
        til.addView(edit)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Kitchen Notes")
            .setView(til)
            .setPositiveButton("Save") { _, _ ->
                val noteText = edit.text?.toString()?.trim().orEmpty()
                if (noteText.isEmpty()) {
                    Toast.makeText(this, "Note is empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showKitchenNoteLabelPicker(noteText)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showKitchenNoteLabelPicker(noteText: String) {
        val available = SelectedPrinterPrefs.allRoutingLabelsFromSavedPrinters(this)
            .sortedBy { it.lowercase(Locale.ROOT) }

        if (available.isEmpty()) {
            Toast.makeText(this, "No kitchen labels configured on any printer", Toast.LENGTH_SHORT).show()
            return
        }

        val names = available.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which ticket is this note for?")
            .setItems(names) { _, which ->
                val label = available[which]
                val key = PrinterLabelKey.normalize(label)
                val existing = kitchenNotesByLabel[key]
                kitchenNotesByLabel[key] = if (existing.isNullOrBlank()) noteText
                    else "$existing\n$noteText"
                saveKitchenNotesToFirestore()
                Toast.makeText(this, "Note saved for \"$label\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveKitchenNotesToFirestore() {
        val orderId = currentOrderId ?: return
        val firestoreMap = kitchenNotesByLabel.filter { it.value.isNotBlank() }
        val updates = if (firestoreMap.isEmpty()) {
            mapOf<String, Any>("kitchenNotesByLabel" to FieldValue.delete())
        } else {
            mapOf<String, Any>("kitchenNotesByLabel" to firestoreMap)
        }
        db.collection("Orders").document(orderId).update(updates)
    }

    private fun loadKitchenNotesFromFirestore(orderId: String) {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                kitchenNotesByLabel.clear()
                @Suppress("UNCHECKED_CAST")
                val map = doc.get("kitchenNotesByLabel") as? Map<String, String>
                if (map != null) kitchenNotesByLabel.putAll(map)
            }
    }

    private fun sendToKitchen() {
        if (cartMap.isEmpty()) return
        if (isCreatingOrder) {
            Toast.makeText(this, "Creating order, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        isSendingToKitchen = true
        updateSendKitchenButtonLabel()
        syncCartButtonStates()

        orderEngine.waitForPendingWrites {
            val existingId = currentOrderId
            if (existingId != null) {
                saveKitchenNotesToFirestore()
                runOnUiThread { runKitchenSendPipeline(existingId) }
            } else {
                orderEngine.ensureOrder(
                    currentOrderId = null,
                    employeeName = employeeName,
                    orderType = orderType,
                    tableId = tableId,
                    tableLayoutId = tableLayoutId,
                    tableName = tableName,
                    sectionId = sectionId,
                    sectionName = sectionName,
                    guestCount = if (guestCount > 0) guestCount else null,
                    guestNames = if (guestNames.isNotEmpty()) guestNames else null,
                    customerId = customerId,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    customerEmail = customerEmail,
                        onSuccess = { oid ->
                        currentOrderId = oid
                        attachOrderKitchenStatusListener(oid)
                        saveKitchenNotesToFirestore()
                        syncAllCartLinesToFirestore(oid) {
                            orderEngine.waitForPendingWrites {
                                runOnUiThread { runKitchenSendPipeline(oid) }
                            }
                        }
                    },
                    onFailure = { e ->
                        runOnUiThread {
                            isSendingToKitchen = false
                            updateSendKitchenButtonLabel()
                            syncCartButtonStates()
                            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    private fun syncAllCartLinesToFirestore(orderId: String, onDone: () -> Unit) {
        val entries = cartMap.entries.toList()
        if (entries.isEmpty()) {
            onDone()
            return
        }
        var pending = entries.size
        var failMessage: String? = null
        fun doneOne() {
            pending--
            if (pending == 0) {
                if (failMessage == null) onDone()
                else {
                    runOnUiThread {
                        isSendingToKitchen = false
                        updateSendKitchenButtonLabel()
                        syncCartButtonStates()
                        Toast.makeText(this, failMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        for ((lineKey, cartItem) in entries) {
            orderEngine.upsertLineItem(
                orderId = orderId,
                lineKey = lineKey,
                input = OrderEngine.LineItemInput(
                    itemId = cartItem.itemId,
                    name = cartItem.name,
                    quantity = cartItem.quantity,
                    basePrice = cartItem.basePrice,
                    modifiers = cartItem.modifiers,
                    guestNumber = cartItem.guestNumber,
                    taxMode = cartItem.taxMode,
                    taxIds = cartItem.taxIds,
                    printerLabel = cartItem.printerLabel,
                ),
                isNewLine = true,
                onSuccess = { doneOne() },
                onFailure = { e ->
                    if (failMessage == null) failMessage = e.message
                    doneOne()
                },
            )
        }
    }

    private fun runKitchenSendPipeline(orderId: String) {
        orderEngine.recomputeOrderTotals(
            orderId = orderId,
            onSuccess = {
                runOnUiThread {
                    val orderRef = db.collection("Orders").document(orderId)
                    fun commitKitchenSendWithMap(
                        orderSnap: DocumentSnapshot,
                        shouldPrintKitchen: Boolean,
                        trigger: String,
                        qtyUpdates: Map<String, Int>?,
                        notesPrintedByPrinterIp: Map<String, String>?,
                        itemsSnapForEffectiveSent: QuerySnapshot? = null,
                    ) {
                        val updates = hashMapOf<String, Any>("lastKitchenSentAt" to Date())
                        if (shouldPrintKitchen && trigger == PrintingSettingsFirestore.FIRST_EVENT) {
                            updates[PrintingSettingsFirestore.FIELD_KITCHEN_CHITS_PRINTED_AT] =
                                FieldValue.serverTimestamp()
                        }
                        if (!qtyUpdates.isNullOrEmpty()) {
                            val merged = KitchenPrintHelper.kitchenSentByLineFromOrder(orderSnap).toMutableMap()
                            qtyUpdates.forEach { (k, v) -> merged[k] = v }
                            updates[KitchenPrintHelper.KITCHEN_SENT_BY_LINE_MAP_FIELD] = merged
                        }
                        if (notesPrintedByPrinterIp != null) {
                            updates[KitchenPrintHelper.KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP] =
                                notesPrintedByPrinterIp
                        }
                        val wb = db.batch()
                        wb.update(orderRef, updates)
                        if (!qtyUpdates.isNullOrEmpty()) {
                            val prevSent = if (itemsSnapForEffectiveSent != null) {
                                KitchenPrintHelper.effectiveSentWithLegacyOrderMap(
                                    orderSnap,
                                    itemsSnapForEffectiveSent,
                                )
                            } else {
                                KitchenPrintHelper.kitchenSentByLineFromOrder(orderSnap)
                            }
                            val kitchenSendGroupId = UUID.randomUUID().toString()
                            for ((lineKey, newHigh) in qtyUpdates) {
                                val prev = prevSent[lineKey] ?: 0
                                val delta = newHigh - prev
                                if (delta > 0) {
                                    val entry = hashMapOf<String, Any>(
                                        OrderLineKdsStatus.BATCH_SUBFIELD_ID to UUID.randomUUID().toString(),
                                        OrderLineKdsStatus.BATCH_SUBFIELD_SEND_GROUP_ID to kitchenSendGroupId,
                                        "sentAt" to Timestamp.now(),
                                        "quantity" to delta.toLong(),
                                        OrderLineKdsStatus.BATCH_SUBFIELD_KDS_STATUS to OrderLineKdsStatus.SENT,
                                    )
                                    val lineRef = orderRef.collection("items").document(lineKey)
                                    wb.update(
                                        lineRef,
                                        mapOf(
                                            OrderLineKdsStatus.FIELD_KDS_SEND_BATCHES to FieldValue.arrayUnion(entry),
                                        ),
                                    )
                                }
                            }
                        }
                        wb.commit()
                            .addOnSuccessListener {
                                OrderLineKdsStatus.markSentOnKitchenAfterSend(db, orderId)
                                runOnUiThread {
                                    kitchenSentForOrder = true
                                    isSendingToKitchen = false
                                    updateSendKitchenButtonLabel()
                                    syncCartButtonStates()
                                    Toast.makeText(this, R.string.cart_sent_to_kitchen, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnFailureListener { e ->
                                runOnUiThread {
                                    isSendingToKitchen = false
                                    updateSendKitchenButtonLabel()
                                    syncCartButtonStates()
                                    Toast.makeText(
                                        this,
                                        "Kitchen may have printed; could not save: ${e.message}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                    }

                    orderRef.get()
                        .addOnSuccessListener { orderDoc ->
                            val trigger = PrintingSettingsCache.printTriggerMode
                            val alreadyChits =
                                orderDoc.getTimestamp(PrintingSettingsFirestore.FIELD_KITCHEN_CHITS_PRINTED_AT) != null
                            val shouldPrintKitchen = when (trigger) {
                                PrintingSettingsFirestore.ON_PAYMENT -> false
                                PrintingSettingsFirestore.FIRST_EVENT -> !alreadyChits
                                else -> true
                            }
                            if (shouldPrintKitchen) {
                                orderRef.collection("items")
                                    .get()
                                    .addOnSuccessListener { itemsSnap ->
                                        val sent =
                                            KitchenPrintHelper.effectiveSentWithLegacyOrderMap(orderDoc, itemsSnap)
                                        val (lineItems, qtyUpdates) =
                                            kitchenDeltaFromCartAndSentMap(orderDoc, sent)
                                        if (lineItems.isNotEmpty()) {
                                            KitchenPrintHelper.printKitchenTickets(
                                                this,
                                                orderId,
                                                lineItems,
                                            ) { notesMap ->
                                                commitKitchenSendWithMap(
                                                    orderDoc,
                                                    shouldPrintKitchen,
                                                    trigger,
                                                    qtyUpdates,
                                                    notesMap,
                                                    itemsSnap,
                                                )
                                            }
                                        } else {
                                            Toast.makeText(
                                                this,
                                                R.string.cart_nothing_new_kitchen,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            commitKitchenSendWithMap(
                                                orderDoc,
                                                shouldPrintKitchen,
                                                trigger,
                                                null,
                                                null,
                                            )
                                        }
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(
                                            this,
                                            R.string.cart_kitchen_items_load_failed,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        commitKitchenSendWithMap(
                                            orderDoc,
                                            shouldPrintKitchen,
                                            trigger,
                                            null,
                                            null,
                                        )
                                    }
                            } else {
                                commitKitchenSendWithMap(orderDoc, shouldPrintKitchen, trigger, null, null)
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(
                                this,
                                R.string.cart_kitchen_items_load_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                            val trigger = PrintingSettingsCache.printTriggerMode
                            val shouldPrintKitchen = trigger != PrintingSettingsFirestore.ON_PAYMENT
                            if (shouldPrintKitchen) {
                                val (lineItems, qtyUpdates) = kitchenDeltaFromCartAndSentMap(null, emptyMap())
                                fun saveSendMeta(
                                    od: DocumentSnapshot,
                                    notesMap: Map<String, String>?,
                                ) {
                                    val updatesToApply =
                                        if (qtyUpdates.isEmpty()) null else qtyUpdates
                                    commitKitchenSendWithMap(
                                        od,
                                        shouldPrintKitchen,
                                        trigger,
                                        updatesToApply,
                                        notesMap,
                                    )
                                }
                                if (lineItems.isNotEmpty()) {
                                    KitchenPrintHelper.printKitchenTickets(
                                        this,
                                        orderId,
                                        lineItems,
                                    ) { notesMap ->
                                        orderRef.get()
                                            .addOnSuccessListener { od -> saveSendMeta(od, notesMap) }
                                            .addOnFailureListener {
                                                orderRef
                                                    .update("lastKitchenSentAt", Date())
                                                    .addOnSuccessListener {
                                                        OrderLineKdsStatus.markSentOnKitchenAfterSend(db, orderId)
                                                        runOnUiThread {
                                                            kitchenSentForOrder = true
                                                            isSendingToKitchen = false
                                                            updateSendKitchenButtonLabel()
                                                            syncCartButtonStates()
                                                            Toast.makeText(
                                                                this,
                                                                R.string.cart_sent_to_kitchen,
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    }
                                                    .addOnFailureListener { e ->
                                                        runOnUiThread {
                                                            isSendingToKitchen = false
                                                            updateSendKitchenButtonLabel()
                                                            syncCartButtonStates()
                                                            Toast.makeText(
                                                                this,
                                                                "Could not save kitchen send: ${e.message}",
                                                                Toast.LENGTH_LONG,
                                                            ).show()
                                                        }
                                                    }
                                            }
                                    }
                                } else {
                                    orderRef.get()
                                        .addOnSuccessListener { od -> saveSendMeta(od, null) }
                                        .addOnFailureListener {
                                            orderRef
                                                .update("lastKitchenSentAt", Date())
                                                .addOnSuccessListener {
                                                    OrderLineKdsStatus.markSentOnKitchenAfterSend(db, orderId)
                                                    runOnUiThread {
                                                        kitchenSentForOrder = true
                                                        isSendingToKitchen = false
                                                        updateSendKitchenButtonLabel()
                                                        syncCartButtonStates()
                                                        Toast.makeText(
                                                            this,
                                                            R.string.cart_sent_to_kitchen,
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                }
                                                .addOnFailureListener { e ->
                                                    runOnUiThread {
                                                        isSendingToKitchen = false
                                                        updateSendKitchenButtonLabel()
                                                        syncCartButtonStates()
                                                        Toast.makeText(
                                                            this,
                                                            "Could not save kitchen send: ${e.message}",
                                                            Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                                }
                                        }
                                }
                            } else {
                                orderRef
                                    .update("lastKitchenSentAt", Date())
                                    .addOnSuccessListener {
                                        OrderLineKdsStatus.markSentOnKitchenAfterSend(db, orderId)
                                        runOnUiThread {
                                            kitchenSentForOrder = true
                                            isSendingToKitchen = false
                                            updateSendKitchenButtonLabel()
                                            syncCartButtonStates()
                                            Toast.makeText(
                                                this,
                                                R.string.cart_sent_to_kitchen,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        runOnUiThread {
                                            isSendingToKitchen = false
                                            updateSendKitchenButtonLabel()
                                            syncCartButtonStates()
                                            Toast.makeText(
                                                this,
                                                "Could not save kitchen send: ${e.message}",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                            }
                        }
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    isSendingToKitchen = false
                    updateSendKitchenButtonLabel()
                    syncCartButtonStates()
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    /**
     * Kitchen tickets only for cart lines where `quantity` exceeds stored sent qty for that line key
     * (from [KitchenPrintHelper.KITCHEN_SENT_BY_LINE_MAP_FIELD] on the order).
     *
     * [orderDoc] supplies [guestNames] when present so dine-in **updates** keep correct seat names
     * after reload or after adding a customer (in-memory list may lag Firestore).
     */
    private fun kitchenDeltaFromCartAndSentMap(
        orderDoc: DocumentSnapshot?,
        sentByLine: Map<String, Int>,
    ): Pair<List<KitchenTicketLineInput>, Map<String, Int>> {
        val lineItems = mutableListOf<KitchenTicketLineInput>()
        val qtyUpdates = mutableMapOf<String, Int>()
        val guestNamesSnapshot = guestNamesListForKitchenPrint(orderDoc)
        for ((lineKey, item) in cartMap) {
            if (item.quantity <= 0) continue
            val sent = sentByLine[lineKey] ?: 0
            val delta = item.quantity - sent
            if (delta <= 0) continue
            val guestLabel = KitchenTicketBuilder.guestKitchenLabelForLine(
                orderType,
                item.guestNumber,
                guestNamesSnapshot,
            )
            lineItems.add(
                KitchenTicketLineInput(
                    quantity = delta,
                    itemName = item.name,
                    modifiers = item.modifiers,
                    routingLabel = item.printerLabel?.trim()?.takeIf { it.isNotEmpty() },
                    guestKitchenLabel = guestLabel,
                ),
            )
            qtyUpdates[lineKey] = item.quantity
        }
        return lineItems to qtyUpdates
    }

    /** Prefer Firestore `guestNames` (order of seats) so kitchen updates match table setup. */
    private fun guestNamesListForKitchenPrint(orderDoc: DocumentSnapshot?): List<String> {
        if (orderDoc != null && orderDoc.exists()) {
            @Suppress("UNCHECKED_CAST")
            val fromOrder = orderDoc.get("guestNames") as? List<*>
            if (fromOrder != null && fromOrder.isNotEmpty()) {
                return fromOrder.map { it?.toString().orEmpty() }
            }
        }
        return guestNames.toList()
    }

    private fun addToCart(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        modifiers: List<OrderModifier>,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList(),
        printerLabel: String? = null,
    ) {

        if (currentOrderId == null && isCreatingOrder) {
            Toast.makeText(this, "Creating order, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentOrderId == null) {
            isCreatingOrder = true
        }

        orderEngine.ensureOrder(
            currentOrderId = currentOrderId,
            employeeName = employeeName,
            orderType = orderType,
            tableId = tableId,
            tableLayoutId = tableLayoutId,
            tableName = tableName,
            sectionId = sectionId,
            sectionName = sectionName,
            guestCount = if (guestCount > 0) guestCount else null,
            guestNames = if (guestNames.isNotEmpty()) guestNames else null,
            customerId = customerId,
            customerName = customerName,
            customerPhone = customerPhone,
            customerEmail = customerEmail,
            onSuccess = { oid ->

                isCreatingOrder = false
                currentOrderId = oid
                attachOrderKitchenStatusListener(oid)

                val guest = selectedGuest
                val lineKey = cartKey(itemId, modifiers, guest)
                val existingItem = cartMap[lineKey]
                val currentQtyInCart = existingItem?.quantity ?: 0

                if (stockCountingEnabled) {
                    if (stock <= 0) {
                        Toast.makeText(this, "Out of stock", Toast.LENGTH_SHORT).show()
                        return@ensureOrder
                    }

                    if (currentQtyInCart + 1 > stock) {
                        Toast.makeText(this, "Only $stock in stock", Toast.LENGTH_SHORT).show()
                        return@ensureOrder
                    }
                }

                if (existingItem != null) {
                    existingItem.quantity += 1
                    cartMap[lineKey] = existingItem
                } else {
                    cartMap[lineKey] = CartItem(
                        itemId = itemId,
                        name = name,
                        quantity = 1,
                        basePrice = basePrice,
                        stock = stock,
                        modifiers = modifiers,
                        guestNumber = guest,
                        taxMode = taxMode,
                        taxIds = taxIds,
                        printerLabel = printerLabel,
                    )
                }

                refreshCart()

                val cartItem = cartMap[lineKey] ?: return@ensureOrder
                val isNew = existingItem == null

                syncLineToFirestore(lineKey, cartItem, isNew, kitchenQuantityDelta = 1)
            },
            onFailure = {
                isCreatingOrder = false
                Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun refreshCart() {
        cartContainer.removeAllViews()
        totalAmount = 0.0

        // Pre-compute discounts so buildCartItemView can reference them
        appliedDiscounts = discountEngine.computeAutoDiscounts(cartMap)
        discountTotalCents = appliedDiscounts.sumOf { it.amountInCents }

        val entries = cartMap.entries.toList()

        if (guestCount > 0) {
            val grouped = entries.groupBy { it.value.guestNumber }

            for (g in 1..guestCount) {
                val isActive = g == selectedGuest
                val guestItems = grouped[g] ?: emptyList()

                val sectionLayout = LinearLayout(this)
                sectionLayout.orientation = LinearLayout.VERTICAL
                val sectionBg = if (isActive) Color.parseColor("#EDE7F6") else Color.TRANSPARENT
                sectionLayout.setBackgroundColor(sectionBg)
                sectionLayout.setPadding(12, 12, 12, 12)
                val sectionParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                sectionParams.setMargins(0, 0, 0, 4)
                sectionLayout.layoutParams = sectionParams

                val headerRow = LinearLayout(this)
                headerRow.orientation = LinearLayout.HORIZONTAL
                headerRow.gravity = android.view.Gravity.CENTER_VERTICAL

                val headerText = TextView(this)
                val displayName = guestNames.getOrNull(g - 1)?.takeIf { it.isNotBlank() } ?: "Guest $g"
                headerText.text = displayName
                headerText.textSize = 15f
                headerText.setTypeface(null, Typeface.BOLD)
                headerText.setTextColor(Color.parseColor("#6A4FB3"))
                headerText.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                if (isActive) {
                    val indicator = TextView(this)
                    indicator.text = "ACTIVE"
                    indicator.textSize = 10f
                    indicator.setTypeface(null, Typeface.BOLD)
                    indicator.setTextColor(Color.WHITE)
                    indicator.setPadding(16, 4, 16, 4)
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(Color.parseColor("#6A4FB3"))
                    bg.cornerRadius = 12f
                    indicator.background = bg
                    headerRow.addView(headerText)
                    headerRow.addView(indicator)
                } else {
                    headerRow.addView(headerText)
                }

                sectionLayout.addView(headerRow)

                val divider = View(this)
                divider.setBackgroundColor(Color.parseColor("#6A4FB3"))
                divider.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 2
                ).apply { topMargin = 8; bottomMargin = 8 }
                sectionLayout.addView(divider)

                if (guestItems.isEmpty()) {
                    val emptyText = TextView(this)
                    emptyText.text = "(no items)"
                    emptyText.textSize = 13f
                    emptyText.setTextColor(Color.parseColor("#AAAAAA"))
                    emptyText.setPadding(0, 4, 0, 4)
                    sectionLayout.addView(emptyText)
                } else {
                    for ((lineKey, item) in guestItems) {
                        val itemView = buildCartItemView(lineKey, item)
                        sectionLayout.addView(itemView)

                        val modifiersTotal = sumModifierPrices(item.modifiers)
                        val unitPrice = item.basePrice + modifiersTotal
                        totalAmount += unitPrice * item.quantity
                    }
                }

                sectionLayout.setOnClickListener {
                    selectedGuest = g
                    refreshCart()
                }

                cartContainer.addView(sectionLayout)
            }
        } else {
            for ((lineKey, item) in entries) {
                val itemView = buildCartItemView(lineKey, item)
                cartContainer.addView(itemView)

                val modifiersTotal = sumModifierPrices(item.modifiers)
                val unitPrice = item.basePrice + modifiersTotal
                totalAmount += unitPrice * item.quantity
            }
        }

        val subtotal = totalAmount
        val subtotalCents = Math.round(subtotal * 100)
        cartTaxDetails.removeAllViews()
        cartTaxSummary.removeAllViews()

        // Discount details + tax lines go in scrollable area
        if (discountTotalCents > 0L) {
            val d = resources.displayMetrics.density
            val taxPurple = Color.parseColor("#5D4E7B")
            cartTaxDetails.addView(TextView(this).apply {
                text = "Discounts"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(taxPurple)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * d).toInt() }
            })
            cartTaxDetails.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                ).apply {
                    topMargin = (4 * d).toInt()
                    bottomMargin = (8 * d).toInt()
                }
            })
            val withAmounts = appliedDiscounts.filter { it.amountInCents > 0L }
            if (withAmounts.isNotEmpty()) {
                for (ad in withAmounts) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (4 * d).toInt() }
                    }
                    row.addView(TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        text = DiscountDisplay.formatCartSummaryLabel(ad.discountName, ad.type, ad.value)
                        textSize = 13f
                        setTextColor(taxPurple)
                    })
                    row.addView(TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = "-${MoneyUtils.centsToDisplay(ad.amountInCents)}"
                        textSize = 13f
                        setTextColor(taxPurple)
                    })
                    cartTaxDetails.addView(row)
                }
            } else {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * d).toInt() }
                }
                row.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    text = "• Discount"
                    textSize = 13f
                    setTextColor(taxPurple)
                })
                row.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = "-${MoneyUtils.centsToDisplay(discountTotalCents)}"
                    textSize = 13f
                    setTextColor(taxPurple)
                })
                cartTaxDetails.addView(row)
            }

            totalAmount -= discountTotalCents / 100.0
        }

        for (tax in allTaxes) {
            var taxableBase = 0.0
            for ((lineKey, item) in cartMap.entries) {
                val modTotal = sumModifierPrices(item.modifiers)
                var lineTotal = (item.basePrice + modTotal) * item.quantity

                val lineDiscounts = appliedDiscounts.filter { it.lineKey == lineKey }
                if (lineDiscounts.isNotEmpty()) {
                    val lineDiscountAmount = lineDiscounts.sumOf { it.amountInCents } / 100.0
                    lineTotal = (lineTotal - lineDiscountAmount).coerceAtLeast(0.0)
                }

                val orderDiscount = appliedDiscounts.find {
                    (it.applyScope == "order" || it.applyScope == "manual") && it.lineKey == null
                }
                if (orderDiscount != null && subtotalCents > 0) {
                    val proportion = lineTotal / (subtotal.coerceAtLeast(0.01))
                    val orderDiscountForLine = (orderDiscount.amountInCents / 100.0) * proportion
                    lineTotal = (lineTotal - orderDiscountForLine).coerceAtLeast(0.0)
                }

                if (item.taxMode == "FORCE_APPLY") {
                    if (item.taxIds.contains(tax.id)) taxableBase += lineTotal
                } else if (tax.enabled) {
                    taxableBase += lineTotal
                }
            }
            if (taxableBase <= 0.0) continue
            val taxAmount = if (tax.type == "PERCENTAGE") taxableBase * tax.amount / 100.0 else tax.amount
            totalAmount += taxAmount
            val taxLine = TextView(this)
            val label = if (tax.type == "PERCENTAGE") "${tax.name} (${String.format(Locale.US, "%.1f", tax.amount)}%)" else tax.name
            taxLine.text = "$label: ${MoneyUtils.centsToDisplay(Math.round(taxAmount * 100))}"
            taxLine.textSize = 13f
            taxLine.setTextColor(Color.parseColor("#5D4E7B"))
            cartTaxSummary.addView(taxLine)
        }

        // Fixed bottom: subtotal + total
        val subtotalLabel = TextView(this)
        subtotalLabel.text = "Subtotal: ${MoneyUtils.centsToDisplay(subtotalCents)}"
        subtotalLabel.textSize = 14f
        subtotalLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        cartTaxSummary.addView(subtotalLabel)

        txtTotal.text = "Total: ${MoneyUtils.centsToDisplay(Math.round(totalAmount * 100))}"

        syncOrderTotal()
        pushOrderToCustomerDisplay()
        syncCartButtonStates()
    }

    private fun syncOrderTotal() {
        val oid = currentOrderId ?: return
        val totalInCents = Math.round(totalAmount * 100)

        val discountedSubtotal = (cartMap.entries.sumOf { (_, item) ->
            val modTotal = sumModifierPrices(item.modifiers)
            (item.basePrice + modTotal) * item.quantity
        }) - (discountTotalCents / 100.0)

        val taxBreakdownList = mutableListOf<Map<String, Any>>()
        for (tax in allTaxes) {
            var taxableBase = 0.0
            for ((lineKey, item) in cartMap.entries) {
                val modTotal = sumModifierPrices(item.modifiers)
                var lineTotal = (item.basePrice + modTotal) * item.quantity
                val lineDiscounts = appliedDiscounts.filter { it.lineKey == lineKey }
                if (lineDiscounts.isNotEmpty()) {
                    lineTotal = (lineTotal - lineDiscounts.sumOf { it.amountInCents } / 100.0).coerceAtLeast(0.0)
                }
                val subtotal = cartMap.entries.sumOf { (_, i) ->
                    val mt = sumModifierPrices(i.modifiers)
                    (i.basePrice + mt) * i.quantity
                }
                val orderDiscount = appliedDiscounts.find {
                    (it.applyScope == "order" || it.applyScope == "manual") && it.lineKey == null
                }
                if (orderDiscount != null && subtotal > 0) {
                    val proportion = lineTotal / subtotal.coerceAtLeast(0.01)
                    lineTotal = (lineTotal - (orderDiscount.amountInCents / 100.0) * proportion).coerceAtLeast(0.0)
                }
                if (item.taxMode == "FORCE_APPLY") {
                    if (item.taxIds.contains(tax.id)) taxableBase += lineTotal
                } else if (tax.enabled) {
                    taxableBase += lineTotal
                }
            }
            if (taxableBase <= 0.0) continue
            val taxAmount = if (tax.type == "PERCENTAGE") taxableBase * tax.amount / 100.0 else tax.amount
            taxBreakdownList.add(mapOf(
                "name" to tax.name,
                "rate" to tax.amount,
                "taxType" to tax.type,
                "amountInCents" to Math.round(taxAmount * 100)
            ))
        }

        val updates = mutableMapOf<String, Any>(
            "totalInCents" to totalInCents,
            "remainingInCents" to totalInCents,
            "updatedAt" to Date(),
            "discountInCents" to discountTotalCents,
            "taxBreakdown" to taxBreakdownList
        )

        if (appliedDiscounts.isNotEmpty()) {
            updates["appliedDiscounts"] = appliedDiscounts.map { ad ->
                mapOf(
                    "discountId" to ad.discountId,
                    "discountName" to ad.discountName,
                    "type" to ad.type,
                    "value" to ad.value,
                    "applyScope" to ad.applyScope,
                    "amountInCents" to ad.amountInCents,
                    "lineKey" to (ad.lineKey ?: "")
                )
            }
        } else {
            updates["appliedDiscounts"] = emptyList<Map<String, Any>>()
        }

        db.collection("Orders").document(oid).update(updates)
    }

    private fun pushOrderToCustomerDisplay() {
        if (cartMap.isEmpty()) {
            val name = ReceiptSettings.load(this).businessName
            CustomerDisplayManager.setIdle(this, name)
            return
        }

        val lines = cartMap.entries.map { (_, item) ->
            fun flattenModStrings(mods: List<OrderModifier>, prefix: String = ""): List<String> {
                return mods.flatMap { mod ->
                    val label = if (mod.action == "REMOVE") {
                        "${prefix}• ${ModifierRemoveDisplay.cartLine(mod.name)}"
                    } else {
                        "${prefix}• ${mod.name}"
                    }
                    listOf(label) + if (mod.children.isNotEmpty()) flattenModStrings(mod.children, "$prefix    ") else emptyList()
                }
            }
            val modStrings = flattenModStrings(item.modifiers)
            val unitPrice = item.basePrice + sumModifierPrices(item.modifiers)
            val lineTotalCents = Math.round(unitPrice * item.quantity * 100)

            CustomerOrderLine(
                name = item.name,
                quantity = item.quantity,
                modifiers = modStrings,
                lineTotalCents = lineTotalCents
            )
        }

        val rawSubtotal = cartMap.values.sumOf { item ->
            val modTotal = sumModifierPrices(item.modifiers)
            (item.basePrice + modTotal) * item.quantity
        }
        val subtotalCents = Math.round(rawSubtotal * 100)

        val custDiscountLines = mutableListOf<SummaryLine>()
        if (discountTotalCents > 0L) {
            val withAmounts = appliedDiscounts.filter { it.amountInCents > 0L }
            if (withAmounts.isNotEmpty()) {
                for (ad in withAmounts) {
                    custDiscountLines.add(
                        SummaryLine(
                            DiscountDisplay.formatReceiptLabel(ad.discountName, ad.type, ad.value),
                            ad.amountInCents
                        )
                    )
                }
            } else {
                custDiscountLines.add(SummaryLine("Discount", discountTotalCents))
            }
        }

        val discountedSubtotal = rawSubtotal - (discountTotalCents / 100.0)
        val custTaxLines = mutableListOf<SummaryLine>()
        for (tax in allTaxes) {
            var taxableBase = 0.0
            for ((lineKey, item) in cartMap.entries) {
                val modTotal = sumModifierPrices(item.modifiers)
                var lineTotal = (item.basePrice + modTotal) * item.quantity

                val lineDisc = appliedDiscounts.filter { it.lineKey == lineKey }
                if (lineDisc.isNotEmpty()) {
                    lineTotal = (lineTotal - lineDisc.sumOf { it.amountInCents } / 100.0).coerceAtLeast(0.0)
                }
                val orderDiscount = appliedDiscounts.find {
                    (it.applyScope == "order" || it.applyScope == "manual") && it.lineKey == null
                }
                if (orderDiscount != null && subtotalCents > 0) {
                    val proportion = lineTotal / rawSubtotal.coerceAtLeast(0.01)
                    lineTotal = (lineTotal - (orderDiscount.amountInCents / 100.0) * proportion).coerceAtLeast(0.0)
                }
                if (item.taxMode == "FORCE_APPLY") {
                    if (item.taxIds.contains(tax.id)) taxableBase += lineTotal
                } else if (tax.enabled) {
                    taxableBase += lineTotal
                }
            }
            if (taxableBase <= 0.0) continue
            val taxAmount = if (tax.type == "PERCENTAGE") taxableBase * tax.amount / 100.0 else tax.amount
            val taxCents = Math.round(taxAmount * 100)
            val label = DiscountDisplay.formatTaxLabel(tax.name, tax.type, tax.amount)
            custTaxLines.add(SummaryLine(label, taxCents))
        }

        val summary = OrderSummaryInfo(
            subtotalCents = subtotalCents,
            discountLines = custDiscountLines,
            taxLines = custTaxLines
        )

        val totalCents = Math.round(totalAmount * 100)
        val name = ReceiptSettings.load(this).businessName
        CustomerDisplayManager.updateOrder(this, name, lines, totalCents, summary)
    }

    private fun buildCartItemView(lineKey: String, item: CartItem): View {
        val itemLayout = LinearLayout(this)
        itemLayout.orientation = LinearLayout.VERTICAL
        itemLayout.setPadding(0, 4, 0, 16)

        val nameText = TextView(this)
        nameText.text = "${item.name} (Qty: ${item.quantity})"
        nameText.textSize = 14f
        nameText.setTypeface(null, Typeface.BOLD)
        itemLayout.addView(nameText)

        val modifiersTotal = sumModifierPrices(item.modifiers)

        fun addModifierViews(modifiers: List<OrderModifier>, indent: String = "") {
            for (modifier in modifiers) {
                val line = TextView(this).apply {
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
                    if (modifier.action == "REMOVE") {
                        text = "${indent}• ${ModifierRemoveDisplay.cartLine(modifier.name)}"
                        setTextColor(Color.parseColor("#C62828"))
                    } else {
                        text = "${indent}• ${modifier.name}"
                    }
                }
                itemLayout.addView(line)
                if (modifier.children.isNotEmpty()) {
                    addModifierViews(modifier.children, "$indent    ")
                }
            }
        }
        addModifierViews(item.modifiers)

        val unitPrice = item.basePrice + modifiersTotal
        val lineTotal = unitPrice * item.quantity

        val lineDiscounts = appliedDiscounts.filter { it.lineKey == lineKey }
        for (ld in lineDiscounts) {
            val discountLine = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#666666"))
                text = DiscountDisplay.formatBullet(ld.discountName, ld.type, ld.value)
            }
            itemLayout.addView(discountLine)
        }

        val orderDiscount = appliedDiscounts.find {
            (it.applyScope == "order" || it.applyScope == "manual") && it.lineKey == null
        }
        if (orderDiscount != null && orderDiscount.amountInCents > 0L) {
            val lineCents = (lineTotal * 100).toLong()
            val subtotalAll = cartMap.entries.sumOf { (_, it) ->
                val mod = sumModifierPrices(it.modifiers)
                ((it.basePrice + mod) * it.quantity * 100).toLong()
            }
            val share = if (subtotalAll > 0L) {
                (orderDiscount.amountInCents.toDouble() * lineCents / subtotalAll).toLong()
            } else 0L
            if (share > 0L) {
                itemLayout.addView(TextView(this).apply {
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
                    text = DiscountDisplay.formatBullet(
                        orderDiscount.discountName,
                        orderDiscount.type,
                        orderDiscount.value
                    )
                })
            }
        }

        val lineDiscountCents = lineDiscounts.sumOf { it.amountInCents }
        val effectiveTotal = (lineTotal * 100).toLong() - lineDiscountCents

        val subtotalText = TextView(this)
        subtotalText.text = "Line Total: ${MoneyUtils.centsToDisplay(effectiveTotal)}"
        subtotalText.setTextColor(Color.parseColor("#1B5E20"))
        subtotalText.setTypeface(null, Typeface.BOLD)
        subtotalText.textSize = 13f
        itemLayout.addView(subtotalText)

        val onTap = {
            if (stockCountingEnabled) {
                val currentStock = item.stock
                val currentQty = item.quantity

                if (currentStock > 0 && currentQty + 1 > currentStock) {
                    Toast.makeText(this, "Only $currentStock in stock", Toast.LENGTH_SHORT).show()
                } else {
                    item.quantity += 1
                    cartMap[lineKey] = item
                    refreshCart()

                    val oid = currentOrderId
                    if (oid != null) {
                        syncLineToFirestore(lineKey, item, isNewLine = false, kitchenQuantityDelta = 1)
                    }
                }
            } else {
                item.quantity += 1
                cartMap[lineKey] = item
                refreshCart()

                val oid = currentOrderId
                if (oid != null) {
                    syncLineToFirestore(lineKey, item, isNewLine = false, kitchenQuantityDelta = 1)
                }
            }
        }

        val onMinus = {
            if (item.quantity > 1) {
                decrementCartItem(lineKey, item, 1)
            } else {
                removeCartItem(lineKey)
            }
        }

        val onTrash = {
            removeCartItem(lineKey)
        }

        return wrapWithSwipeToDelete(itemLayout, onTap, onMinus, onTrash)
    }

    private fun removeCartItem(lineKey: String) {
        if (orderKitchenInProcess) {
            Toast.makeText(this, getString(R.string.cart_order_in_process), Toast.LENGTH_SHORT).show()
            return
        }
        cartMap.remove(lineKey)
        refreshCart()

        val oid = currentOrderId
        if (oid != null) {
            orderEngine.deleteLineItem(
                orderId = oid,
                lineKey = lineKey,
                onSuccess = { if (cartMap.isEmpty()) deleteOrderIfEmpty() },
                onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            )
        }
    }

    private fun decrementCartItem(lineKey: String, item: CartItem, amount: Int) {
        item.quantity -= amount
        cartMap[lineKey] = item
        refreshCart()

        val oid = currentOrderId
        if (oid != null) {
            syncLineToFirestore(lineKey, item, isNewLine = false, kitchenQuantityDelta = 0)
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun wrapWithSwipeToDelete(
        contentView: View,
        onTap: () -> Unit,
        onMinus: () -> Unit,
        onTrash: () -> Unit
    ): View {
        val density = resources.displayMetrics.density
        val buttonWidthPx = (56 * density).toInt()
        val totalRevealPx = buttonWidthPx * 2
        val swipeMax = totalRevealPx.toFloat()

        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                totalRevealPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        }

        val minusBtn = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FF9800"))
            layoutParams = LinearLayout.LayoutParams(buttonWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onMinus()
                contentView.animate().translationX(0f).setDuration(150).start()
            }
        }
        val minusIcon = ImageView(this@MenuActivity).apply {
            setImageResource(R.drawable.ic_minus)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                (26 * density).toInt(),
                (26 * density).toInt(),
                Gravity.CENTER
            )
        }
        minusBtn.addView(minusIcon)

        val trashBtn = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E53935"))
            layoutParams = LinearLayout.LayoutParams(buttonWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onTrash()
            }
        }
        val trashIcon = ImageView(this@MenuActivity).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                (26 * density).toInt(),
                (26 * density).toInt(),
                Gravity.CENTER
            )
        }
        trashBtn.addView(trashIcon)

        buttonsContainer.addView(minusBtn)
        buttonsContainer.addView(trashBtn)

        contentView.setBackgroundColor(Color.WHITE)

        wrapper.addView(buttonsContainer)
        wrapper.addView(contentView)

        var downX = 0f
        var downY = 0f
        var prevX = 0f
        var swiping = false

        contentView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    prevX = event.rawX
                    swiping = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (!swiping && abs(dx) > 12 * density && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (swiping) {
                        val delta = event.rawX - prevX
                        val newTx = (v.translationX + delta).coerceIn(-swipeMax, 0f)
                        v.translationX = newTx
                        prevX = event.rawX
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)

                    if (!swiping && abs(event.rawX - downX) < 12 * density
                        && abs(event.rawY - downY) < 12 * density
                    ) {
                        onTap()
                    } else if (swiping) {
                        if (v.translationX < -swipeMax / 2) {
                            v.animate().translationX(-swipeMax).setDuration(120).start()
                        } else {
                            v.animate().translationX(0f).setDuration(150).start()
                        }
                    }
                    swiping = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().translationX(0f).setDuration(150).start()
                    swiping = false
                    true
                }

                else -> true
            }
        }

        return wrapper
    }

    // ----------------------------
    // FIRESTORE ORDER / ITEMS
    // ----------------------------
    private fun cartKey(itemId: String, modifiers: List<OrderModifier>, guest: Int = 0): String {
        fun modKey(mods: List<OrderModifier>): String {
            return mods.sortedBy { "${it.action}|${it.name}|${it.price}" }.joinToString("|") { mod ->
                val childPart = if (mod.children.isNotEmpty()) "[${modKey(mod.children)}]" else ""
                "${mod.action}:${mod.name}:${mod.price}$childPart"
            }
        }
        val modsKey = modKey(modifiers)
        val guestPart = if (guest > 0) "__G${guest}" else ""
        return "${itemId}__${modsKey}${guestPart}"
    }

    // ----------------------------
    // STOCK DEDUCTION (after payment)
    // ----------------------------

    private fun deductStockTransaction(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {

        db.runTransaction { transaction ->

            // 1️⃣ READ ALL FIRST
            val stockMap = mutableMapOf<String, Long>()

            for ((_, cartItem) in cartMap) {
                val itemRef = db.collection("MenuItems").document(cartItem.itemId)
                val snapshot = transaction.get(itemRef)
                val currentStock = snapshot.getLong("stock") ?: 0L

                if (currentStock < cartItem.quantity) {
                    throw Exception("Stock changed. Not enough inventory.")
                }

                stockMap[cartItem.itemId] = currentStock
            }

            // 2️⃣ THEN WRITE
            for ((_, cartItem) in cartMap) {
                val itemRef = db.collection("MenuItems").document(cartItem.itemId)
                val currentStock = stockMap[cartItem.itemId] ?: 0L
                val newStock = currentStock - cartItem.quantity
                transaction.update(itemRef, "stock", newStock)
            }

            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Transaction failed") }
    }
    private fun deleteOrderIfEmpty() {
        val orderId = currentOrderId ?: return

        if (orderType == "DINE_IN") return

        db.collection("Orders")
            .document(orderId)
            .collection("items")
            .limit(1)
            .get()
            .addOnSuccessListener { docs ->
                if (docs.isEmpty) {
                    db.collection("Orders")
                        .document(orderId)
                        .delete()
                        .addOnSuccessListener {
                            detachOrderKitchenStatusListener()
                            currentOrderId = null
                        }
                }
            }
    }
    private fun clearCart() {
        cartMap.clear()
        kitchenNotesByLabel.clear()
        totalAmount = 0.0
        discountTotalCents = 0L
        appliedDiscounts = emptyList()
        isCreatingOrder = false
        customerId = null
        customerName = null
        customerPhone = null
        customerEmail = null
        cartContainer.removeAllViews()
        cartTaxDetails.removeAllViews()
        cartTaxSummary.removeAllViews()
        txtTotal.text = "Total: $0.00"
        updateCustomerDisplay()
        CustomerDisplayManager.setIdle(this, ReceiptSettings.load(this).businessName)
        kitchenSentForOrder = false
        updateSendKitchenButtonLabel()
        syncCartButtonStates()
    }

    // ── Bar Tab Capture Flow ────────────────────────────────────────

    private var captureDialog: AlertDialog? = null

    private fun startCaptureFlow(orderId: String) {
        isCaptureFlowActive = true
        syncCartButtonStates()
        showCaptureLoading("Capturing payment…")

        orderEngine.recomputeOrderTotals(
            orderId = orderId,
            onSuccess = { fetchTotalAndCapture(orderId) },
            onFailure = { e ->
                hideCaptureLoading()
                Toast.makeText(this, "Failed to compute total: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun fetchTotalAndCapture(orderId: String) {
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                val totalInCents = doc.getLong("totalInCents") ?: 0L
                if (totalInCents <= 0L) {
                    hideCaptureLoading()
                    Toast.makeText(this, "Order total is $0. Nothing to capture.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val captureAmount = totalInCents / 100.0

                paymentService.capture(
                    amount = captureAmount,
                    authCode = preAuthAuthCode!!,
                    referenceId = preAuthReferenceId!!,
                    onSuccess = { result ->
                        runOnUiThread { finalizeCaptureOrder(orderId, totalInCents, result) }
                    },
                    onFailure = { msg ->
                        runOnUiThread {
                            hideCaptureLoading()
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            .addOnFailureListener { e ->
                hideCaptureLoading()
                Toast.makeText(this, "Error reading order: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun finalizeCaptureOrder(orderId: String, totalInCents: Long, capture: CaptureResult) {
        val batchId = currentBatchId ?: ""

        val orderRef = db.collection("Orders").document(orderId)

        val paymentEntry = hashMapOf<String, Any>(
            "paymentId" to UUID.randomUUID().toString(),
            "paymentType" to "Credit",
            "amountInCents" to totalInCents,
            "timestamp" to Date(),
            "authCode" to capture.authCode,
            "cardBrand" to capture.cardBrand,
            "last4" to capture.cardLast4,
            "entryType" to capture.entryType,
            "referenceId" to capture.referenceId,
            "batchNumber" to capture.batchNumber,
            "transactionNumber" to capture.transactionNumber
        )

        val txUpdateData = hashMapOf<String, Any>(
            "type" to "CAPTURE",
            "totalPaidInCents" to totalInCents,
            "payments" to listOf(paymentEntry),
            "status" to "COMPLETED"
        )
        if (batchId.isNotBlank()) txUpdateData["batchId"] = batchId

        val orderUpdates = hashMapOf<String, Any>(
            "status" to "CLOSED",
            "totalPaidInCents" to totalInCents,
            "remainingInCents" to 0L,
            "updatedAt" to Date()
        )

        val txDocId = preAuthFirestoreDocId
        if (!txDocId.isNullOrBlank()) {
            // Update the original PRE_AUTH transaction in place → Post Auth
            val txRef = db.collection("Transactions").document(txDocId)
            orderUpdates["saleTransactionId"] = txDocId

            db.runBatch { batch ->
                batch.update(txRef, txUpdateData)
                batch.update(orderRef, orderUpdates)
            }.addOnSuccessListener {
                hideCaptureLoading()
                val afterCapture = {
                    clearCart()
                    detachOrderKitchenStatusListener()
                    currentOrderId = null
                    Toast.makeText(this, "Payment captured. Tab closed.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, ReceiptOptionsActivity::class.java).apply {
                        putExtra("ORDER_ID", orderId)
                        if (!customerEmail.isNullOrBlank()) putExtra("CUSTOMER_EMAIL", customerEmail)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                    finish()
                }
                if (stockCountingEnabled) {
                    deductStockTransaction(
                        onSuccess = { afterCapture() },
                        onFailure = { msg ->
                            Toast.makeText(this, "Tab closed but stock error: $msg", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    )
                } else {
                    afterCapture()
                }
            }.addOnFailureListener { e ->
                hideCaptureLoading()
                Toast.makeText(this, "Capture approved but save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            val txRef = db.collection("Transactions").document()
            orderUpdates["saleTransactionId"] = txRef.id

            val txData = hashMapOf<String, Any>(
                "orderId" to orderId,
                "type" to "CAPTURE",
                "totalPaidInCents" to totalInCents,
                "payments" to listOf(paymentEntry),
                "status" to "COMPLETED",
                "createdAt" to Date(),
                "voided" to false,
                "settled" to false
            )
            if (batchId.isNotBlank()) txData["batchId"] = batchId

            db.runTransaction { firestoreTxn ->
                if (batchId.isNotBlank()) {
                    val batchRef = db.collection("Batches").document(batchId)
                    val batchSnap = firestoreTxn.get(batchRef)
                    val counter = batchSnap.getLong("transactionCounter") ?: 0L
                    val next = counter + 1
                    firestoreTxn.update(batchRef, "transactionCounter", next)
                    txData["appTransactionNumber"] = next
                }
                firestoreTxn.set(txRef, txData)
                firestoreTxn.update(orderRef, orderUpdates)
            }.addOnSuccessListener {
                hideCaptureLoading()
                val afterCapture = {
                    clearCart()
                    detachOrderKitchenStatusListener()
                    currentOrderId = null
                    Toast.makeText(this, "Payment captured. Tab closed.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, ReceiptOptionsActivity::class.java).apply {
                        putExtra("ORDER_ID", orderId)
                        if (!customerEmail.isNullOrBlank()) putExtra("CUSTOMER_EMAIL", customerEmail)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                    finish()
                }
                if (stockCountingEnabled) {
                    deductStockTransaction(
                        onSuccess = { afterCapture() },
                        onFailure = { msg ->
                            Toast.makeText(this, "Tab closed but stock error: $msg", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    )
                } else {
                    afterCapture()
                }
            }.addOnFailureListener { e ->
                hideCaptureLoading()
                Toast.makeText(this, "Capture approved but save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCaptureLoading(message: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val progress = android.widget.ProgressBar(this)
        val text = TextView(this).apply {
            this.text = message
            textSize = 16f
            setPadding(32, 0, 0, 0)
        }
        layout.addView(progress)
        layout.addView(text)

        captureDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
        captureDialog?.show()
    }

    private fun hideCaptureLoading() {
        captureDialog?.dismiss()
        captureDialog = null
        isCaptureFlowActive = false
        syncCartButtonStates()
    }

    // ── Embedded POS Keyboard ─────────────────────────────────────────

    private fun hideSystemKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editItemSearch.windowToken, 0)
    }

    private fun buildEmbeddedKeyboard() {
        keyboardContainer.removeAllViews()

        val rows = listOf(
            listOf("Q","W","E","R","T","Y","U","I","O","P"),
            listOf("A","S","D","F","G","H","J","K","L"),
            listOf("Z","X","C","V","B","N","M"),
        )

        for (chars in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            }
            for (ch in chars) {
                rowLayout.addView(makeKey(ch, weight = 1f, bg = R.drawable.bg_keyboard_key, textColor = "#1C1B1F"))
            }
            if (chars.size == 7) {
                rowLayout.addView(makeKey("⌫", weight = 1.6f, bg = R.drawable.bg_keyboard_key_dark, textColor = "#1C1B1F"))
            }
            keyboardContainer.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        bottomRow.addView(makeKey("123", weight = 1.5f, bg = R.drawable.bg_keyboard_key_dark, textColor = "#1C1B1F"))
        bottomRow.addView(makeKey(" ", weight = 5f, bg = R.drawable.bg_keyboard_key, textColor = "#1C1B1F", label = "SPACE"))
        bottomRow.addView(makeKey("DONE", weight = 2f, bg = R.drawable.bg_keyboard_key_accent, textColor = "#FFFFFF"))
        keyboardContainer.addView(bottomRow)
    }

    private fun buildNumberKeyboard() {
        keyboardContainer.removeAllViews()

        val rows = listOf(
            listOf("1","2","3","4","5","6","7","8","9","0"),
            listOf("-","/",":",";","(",")","$","&","@","\""),
            listOf(".",",","?","!","'"),
        )

        for (chars in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            }
            for (ch in chars) {
                rowLayout.addView(makeKey(ch, weight = 1f, bg = R.drawable.bg_keyboard_key, textColor = "#1C1B1F"))
            }
            if (chars.size == 5) {
                rowLayout.addView(makeKey("⌫", weight = 1.6f, bg = R.drawable.bg_keyboard_key_dark, textColor = "#1C1B1F"))
            }
            keyboardContainer.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        bottomRow.addView(makeKey("ABC", weight = 1.5f, bg = R.drawable.bg_keyboard_key_dark, textColor = "#1C1B1F"))
        bottomRow.addView(makeKey(" ", weight = 5f, bg = R.drawable.bg_keyboard_key, textColor = "#1C1B1F", label = "SPACE"))
        bottomRow.addView(makeKey("DONE", weight = 2f, bg = R.drawable.bg_keyboard_key_accent, textColor = "#FFFFFF"))
        keyboardContainer.addView(bottomRow)
    }

    private fun makeKey(
        value: String,
        weight: Float,
        bg: Int,
        textColor: String,
        label: String? = null,
    ): TextView {
        val keyHeight = dp(42)
        return TextView(this).apply {
            text = label ?: value
            textSize = if (value.length > 1 && value != "⌫") 13f else 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textColor))
            setBackgroundResource(bg)
            isClickable = true
            isFocusable = true
            minHeight = keyHeight
            layoutParams = LinearLayout.LayoutParams(0, keyHeight, weight).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener { onKeyPress(value) }
        }
    }

    private fun onKeyPress(value: String) {
        when (value) {
            "⌫" -> {
                val sel = editItemSearch.selectionStart
                if (sel > 0) editItemSearch.text.delete(sel - 1, sel)
            }
            "DONE" -> {
                keyboardContainer.visibility = View.GONE
                editItemSearch.clearFocus()
            }
            "123" -> buildNumberKeyboard()
            "ABC" -> buildEmbeddedKeyboard()
            " " -> editItemSearch.text.insert(editItemSearch.selectionStart, " ")
            else -> editItemSearch.text.insert(editItemSearch.selectionStart, value.lowercase())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}