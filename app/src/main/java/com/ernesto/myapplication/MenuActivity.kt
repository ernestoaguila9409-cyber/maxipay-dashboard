package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.ernesto.myapplication.engine.AppliedDiscount
import com.ernesto.myapplication.engine.CaptureResult
import com.ernesto.myapplication.engine.DiscountEngine
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
    val taxIds: List<String> = emptyList()
)

class MenuActivity : AppCompatActivity() {

    private var currentCategoryId: String? = null
    private val db = FirebaseFirestore.getInstance()

    // key = lineKey (doc id in Orders/{orderId}/items)
    private val cartMap = mutableMapOf<String, CartItem>()
    private lateinit var orderEngine: OrderEngine
    private lateinit var discountEngine: DiscountEngine
    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: LinearLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var cartTaxDetails: LinearLayout
    private lateinit var cartTaxSummary: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnCheckout: Button
    private var selectedGuest: Int = 0

    private var totalAmount = 0.0
    private var allTaxes = mutableListOf<TaxItem>()
    private var appliedDiscounts = listOf<AppliedDiscount>()
    private var discountTotalCents = 0L
    private var employeeName: String = ""
    private var orderType: String = ""
    private var currentOrderId: String? = null
    private var isCreatingOrder = false
    private var tableId: String? = null
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

    private val paymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (stockCountingEnabled) {
                    deductStockTransaction(
                        onSuccess = {
                            clearCart()
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
        tableName = intent.getStringExtra("tableName")
        sectionId = intent.getStringExtra("sectionId")
        sectionName = intent.getStringExtra("sectionName")
        guestCount = intent.getIntExtra("guestCount", 0)
        guestNames = intent.getStringArrayListExtra("guestNames")?.toMutableList() ?: mutableListOf()
        currentBatchId = intent.getStringExtra("batchId")

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)
        cartContainer = findViewById(R.id.cartContainer)
        cartTaxDetails = findViewById(R.id.cartTaxDetails)
        cartTaxSummary = findViewById(R.id.cartTaxSummary)
        txtTotal = findViewById(R.id.txtTotal)
        btnCheckout = findViewById(R.id.btnCheckout)

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
                loadActiveSchedules { loadCategories() }
            }
            .addOnFailureListener {
                stockCountingEnabled = true
                loadActiveSchedules { loadCategories() }
            }
        loadAllTaxes()
        discountEngine.loadDiscounts { refreshCart() }
        updateCustomerDisplay()

        // ✅ Load existing order items into cart if ORDER_ID was provided
        currentOrderId?.let { existingOrderId ->
            loadExistingOrderIntoCart(existingOrderId)
        }

        btnCheckout.setOnClickListener {

            if (cartMap.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val oid = currentOrderId ?: return@setOnClickListener

            btnCheckout.isEnabled = false

            orderEngine.waitForPendingWrites {
                if (!preAuthReferenceId.isNullOrBlank() && !preAuthAuthCode.isNullOrBlank()) {
                    btnCheckout.isEnabled = true
                    startCaptureFlow(oid)
                } else {
                    orderEngine.recomputeOrderTotals(
                        orderId = oid,
                        onSuccess = {
                            btnCheckout.isEnabled = true
                            val targetActivity = if (TipConfig.isTipsEnabled(this)) TipActivity::class.java else PaymentActivity::class.java
                            val intent = Intent(this, targetActivity).apply {
                                putExtra("ORDER_ID", oid)
                                putExtra("BATCH_ID", currentBatchId ?: "")
                            }
                            paymentLauncher.launch(intent)
                        },
                        onFailure = {
                            btnCheckout.isEnabled = true
                            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAllTaxes()
        discountEngine.loadDiscounts { refreshCart() }
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
                if (tableId.isNullOrBlank()) {
                    tableId = orderDoc.getString("tableId")
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

                            val unitPriceInCents =
                                doc.getLong("unitPriceInCents") ?: 0L

                            val basePrice = unitPriceInCents / 100.0

                            val modifiers = parseModifiers(doc.get("modifiers"))
                            val stock = 0L
                            val guest = (doc.getLong("guestNumber") ?: 0L).toInt()
                            val lineTaxMode = doc.getString("taxMode") ?: "INHERIT"
                            @Suppress("UNCHECKED_CAST")
                            val lineTaxIds = (doc.get("taxIds") as? List<String>) ?: emptyList()

                            cartMap[lineKey] = CartItem(
                                itemId = itemId,
                                name = name,
                                quantity = qty,
                                basePrice = basePrice,
                                stock = stock,
                                modifiers = modifiers,
                                guestNumber = guest,
                                taxMode = lineTaxMode,
                                taxIds = lineTaxIds
                            )
                        }

                        refreshCart()
                    }
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
                "phone" to phone,
                "email" to email
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
            out.add(OrderModifier(name, action, price))
        }
        return out
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

    private fun loadCategories() {
        categoryContainer.removeAllViews()

        val query = if (orderType.isNotBlank()) {
            db.collection("Categories")
                .whereArrayContains("availableOrderTypes", orderType)
        } else {
            db.collection("Categories")
        }

        query.get()
            .addOnSuccessListener { documents ->
                categoryAvailabilityMap.clear()

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

                    val button = Button(this)
                    button.text = name
                    button.setBackgroundColor(Color.parseColor("#E8E8E8"))
                    button.setTextColor(Color.DKGRAY)
                    button.setOnClickListener { loadItems(categoryId) }

                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 4, 0, 4)
                    button.layoutParams = params

                    categoryContainer.addView(button)
                }
            }
    }

    private fun loadItems(categoryId: String) {

        currentCategoryId = categoryId
        itemContainer.removeAllViews()

        val catAvailability = categoryAvailabilityMap[categoryId] ?: emptyList()

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                val seen = mutableSetOf<String>()

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
                    val pricesRaw = doc.get("prices") as? Map<String, Any>
                    val pricesMap = pricesRaw?.mapValues {
                        (it.value as? Number)?.toDouble() ?: 0.0
                    } ?: emptyMap()
                    val price = if (pricesMap.isNotEmpty())
                        pricesMap.values.first()
                    else
                        doc.getDouble("price") ?: 0.0
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

                    val button = Button(this)

                    if (stockCountingEnabled && stock <= 0) {
                        button.text = "$name\n$${String.format(Locale.US, "%.2f", price)}\nOUT OF STOCK"
                        button.setBackgroundColor(Color.LTGRAY)
                        button.setTextColor(Color.DKGRAY)
                        button.isEnabled = false
                    } else {
                        val label = if (stockCountingEnabled)
                            "$name\n$${String.format(Locale.US, "%.2f", price)}\nStock: $stock"
                        else
                            "$name\n$${String.format(Locale.US, "%.2f", price)}"
                        button.text = label
                        button.setTextColor(Color.WHITE)
                        button.setBackgroundColor(
                            if (isScheduled) Color.parseColor("#2196F3") else Color.parseColor("#6A4FB3")
                        )

                        val effectiveStock = if (stockCountingEnabled) stock else Long.MAX_VALUE
                        button.setOnClickListener {
                            checkAndShowModifiers(itemId, name, price, effectiveStock, itemTaxMode, itemTaxIds)
                        }
                    }

                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 8, 0, 8)
                    button.layoutParams = params

                    itemContainer.addView(button)
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
                @Suppress("UNCHECKED_CAST")
                val modifierGroupIds = itemDoc.get("modifierGroupIds") as? List<String>

                if (modifierGroupIds != null && modifierGroupIds.isNotEmpty()) {
                    showModifierDialog(itemId, name, basePrice, stock, modifierGroupIds, taxMode, taxIds)
                } else {
                    db.collection("ItemModifierGroups")
                        .whereEqualTo("itemId", itemId)
                        .orderBy("displayOrder")
                        .get()
                        .addOnSuccessListener { documents ->
                            if (documents.isEmpty) {
                                addToCart(itemId, name, basePrice, stock, emptyList(), taxMode, taxIds)
                            } else {
                                val groupIds = documents.mapNotNull { it.getString("groupId") }
                                showModifierDialog(itemId, name, basePrice, stock, groupIds, taxMode, taxIds)
                            }
                        }
                }
            }
    }

    private fun showModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        groupIds: List<String>,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList()
    ) {
        if (groupIds.isEmpty()) {
            addToCart(itemId, name, basePrice, stock, emptyList(), taxMode, taxIds)
            return
        }

        val selectedModifiers = mutableListOf<OrderModifier>()
        val selectedCountPerGroup = mutableMapOf<String, Int>()
        val requiredGroups = mutableSetOf<String>()

        val orderIndex = groupIds.withIndex().associate { it.value to it.index }
        val fetchedGroups = mutableListOf<GroupInfo>()
        var pending = groupIds.size

        for (groupId in groupIds) {
            db.collection("ModifierGroups")
                .document(groupId)
                .get()
                .addOnSuccessListener { groupDoc ->
                    val groupName = groupDoc.getString("name") ?: ""
                    val isRequired = groupDoc.getBoolean("required") ?: false
                    val maxSelection = groupDoc.getLong("maxSelection")?.toInt() ?: 1
                    val groupType = groupDoc.getString("groupType") ?: "ADD"

                    @Suppress("UNCHECKED_CAST")
                    val embeddedOptions = (groupDoc.get("options") as? List<Map<String, Any>>)
                        ?.mapNotNull { map ->
                            val optName = map["name"]?.toString() ?: return@mapNotNull null
                            val optPrice = (map["price"] as? Number)?.toDouble() ?: 0.0
                            val optId = map["id"]?.toString() ?: ""
                            ModifierOptionEntry(optId, optName, optPrice)
                        } ?: emptyList()

                    if (groupName.isNotEmpty()) {
                        synchronized(fetchedGroups) {
                            fetchedGroups.add(GroupInfo(groupId, groupName, isRequired, maxSelection, groupType, embeddedOptions))
                        }
                        if (isRequired) requiredGroups.add(groupId)
                    }
                    pending--
                    if (pending == 0) {
                        val sorted = fetchedGroups.sortedWith(
                            compareBy<GroupInfo> { !it.isRequired }.thenBy { orderIndex[it.groupId] ?: 0 }
                        )
                        buildAndShowModifierDialog(
                            itemId, name, basePrice, stock,
                            sorted, requiredGroups, selectedModifiers, selectedCountPerGroup,
                            taxMode, taxIds
                        )
                    }
                }
        }
    }

    private fun buildAndShowModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        sortedGroups: List<GroupInfo>,
        requiredGroups: Set<String>,
        selectedModifiers: MutableList<OrderModifier>,
        selectedCountPerGroup: MutableMap<String, Int>,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList()
    ) {
        val mainLayout = LinearLayout(this)
        mainLayout.orientation = LinearLayout.VERTICAL
        mainLayout.setPadding(40, 40, 40, 40)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Options")
            .setView(mainLayout)
            .setPositiveButton("Add to Cart", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.setOnClickListener {
                for (requiredGroup in requiredGroups) {
                    if ((selectedCountPerGroup[requiredGroup] ?: 0) == 0) {
                        Toast.makeText(this, "Please select required options.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                addToCart(itemId, name, basePrice, stock, selectedModifiers, taxMode, taxIds)
                dialog.dismiss()
            }
        }

        for (group in sortedGroups) {
            val (groupId, groupName, isRequired, maxSelection, groupType, embeddedOptions) = group
            val isRemoveGroup = groupType == "REMOVE"

            val groupContainer = LinearLayout(this)
            groupContainer.orientation = LinearLayout.VERTICAL
            groupContainer.setPadding(0, 40, 0, 40)

            val title = TextView(this)
            title.text = if (isRemoveGroup) "REMOVE INGREDIENTS" else groupName
            title.textSize = 18f
            title.setTypeface(null, Typeface.BOLD)
            if (isRemoveGroup) title.setTextColor(Color.parseColor("#D32F2F"))

            val subtitle = TextView(this)
            if (isRemoveGroup) {
                subtitle.text = "Tap to remove ingredients"
                subtitle.setTextColor(Color.GRAY)
            } else {
                val subtitleFull =
                    if (isRequired) "Required • Select up to $maxSelection"
                    else "Optional • Select up to $maxSelection"
                val spannable = SpannableString(subtitleFull)
                if (isRequired) {
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

            groupContainer.addView(title)
            groupContainer.addView(subtitle)

            val divider = View(this)
            divider.setBackgroundColor(if (isRemoveGroup) Color.parseColor("#D32F2F") else Color.LTGRAY)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            )
            groupContainer.addView(divider)

            mainLayout.addView(groupContainer)

            if (embeddedOptions.isNotEmpty()) {
                renderModifierOptions(embeddedOptions, isRemoveGroup, maxSelection, groupId,
                    groupContainer, selectedModifiers, selectedCountPerGroup)
            } else {
                db.collection("ModifierOptions")
                    .whereEqualTo("groupId", groupId)
                    .get()
                    .addOnSuccessListener { optionDocs ->
                        val legacyOptions = optionDocs.mapNotNull { doc ->
                            val optName = doc.getString("name") ?: return@mapNotNull null
                            val optPrice = doc.getDouble("price") ?: 0.0
                            ModifierOptionEntry(doc.id, optName, optPrice)
                        }
                        renderModifierOptions(legacyOptions, isRemoveGroup, maxSelection, groupId,
                            groupContainer, selectedModifiers, selectedCountPerGroup)
                    }
            }
        }

        dialog.show()
    }

    private fun renderModifierOptions(
        options: List<ModifierOptionEntry>,
        isRemoveGroup: Boolean,
        maxSelection: Int,
        groupId: String,
        groupContainer: LinearLayout,
        selectedModifiers: MutableList<OrderModifier>,
        selectedCountPerGroup: MutableMap<String, Int>
    ) {
        if (isRemoveGroup) {
            for (opt in options) {
                val checkBox = CheckBox(this)
                checkBox.text = opt.name
                checkBox.setTextColor(Color.parseColor("#D32F2F"))
                groupContainer.addView(checkBox)

                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedModifiers.add(OrderModifier(opt.name, "REMOVE", 0.0))
                    } else {
                        selectedModifiers.removeAll { it.name == opt.name && it.action == "REMOVE" }
                    }
                }
            }
        } else if (maxSelection == 1) {
            val radioGroup = RadioGroup(this)
            radioGroup.orientation = RadioGroup.VERTICAL
            groupContainer.addView(radioGroup)

            for (opt in options) {
                val radioButton = RadioButton(this)
                radioButton.text = "${opt.name} +$${String.format(Locale.US, "%.2f", opt.price)}"
                radioGroup.addView(radioButton)

                radioButton.setOnClickListener {
                    selectedModifiers.removeAll { it.name == opt.name && it.action == "ADD" }
                    selectedModifiers.add(OrderModifier(opt.name, "ADD", opt.price))
                    selectedCountPerGroup[groupId] = 1
                }
            }
        } else {
            selectedCountPerGroup[groupId] = 0

            for (opt in options) {
                val checkBox = CheckBox(this)
                checkBox.text = "${opt.name} +$${String.format(Locale.US, "%.2f", opt.price)}"
                groupContainer.addView(checkBox)

                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    var count = selectedCountPerGroup[groupId] ?: 0

                    if (isChecked) {
                        if (count >= maxSelection) {
                            checkBox.isChecked = false
                            Toast.makeText(
                                this,
                                "Maximum $maxSelection selections allowed",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnCheckedChangeListener
                        }
                        selectedModifiers.add(OrderModifier(opt.name, "ADD", opt.price))
                        count++
                    } else {
                        selectedModifiers.removeAll { it.name == opt.name && it.action == "ADD" && it.price == opt.price }
                        count--
                    }

                    selectedCountPerGroup[groupId] = count
                }
            }
        }
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

    private fun addToCart(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        modifiers: List<OrderModifier>,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList()
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
                        taxIds = taxIds
                    )
                }

                refreshCart()

                val cartItem = cartMap[lineKey] ?: return@ensureOrder
                val isNew = existingItem == null

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
                        taxIds = cartItem.taxIds
                    ),
                    isNewLine = isNew,
                    onSuccess = {},
                    onFailure = {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
                )
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

                        val modifiersTotal = item.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
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

                val modifiersTotal = item.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
                val unitPrice = item.basePrice + modifiersTotal
                totalAmount += unitPrice * item.quantity
            }
        }

        val subtotal = totalAmount
        val subtotalCents = (subtotal * 100).toLong()
        cartTaxDetails.removeAllViews()
        cartTaxSummary.removeAllViews()

        // Discount details + tax lines go in scrollable area
        if (discountTotalCents > 0L) {
            val discountSummary = TextView(this)
            discountSummary.text = "Discount: -${MoneyUtils.centsToDisplay(discountTotalCents)}"
            discountSummary.textSize = 14f
            discountSummary.setTypeface(null, android.graphics.Typeface.BOLD)
            discountSummary.setTextColor(Color.parseColor("#2E7D32"))
            cartTaxDetails.addView(discountSummary)

            for (ad in appliedDiscounts) {
                val detailLine = TextView(this)
                val valueStr = if (ad.type == "PERCENTAGE") {
                    String.format(Locale.US, "%.1f%%", ad.value)
                } else {
                    MoneyUtils.centsToDisplay((ad.value * 100).toLong())
                }
                val scopeStr = when (ad.applyScope) {
                    "item" -> "item"
                    "manual" -> "checkout"
                    else -> "order"
                }
                detailLine.text = "  ${ad.discountName} ($valueStr · $scopeStr)"
                detailLine.textSize = 12f
                detailLine.setTextColor(Color.parseColor("#388E3C"))
                cartTaxDetails.addView(detailLine)
            }

            totalAmount -= discountTotalCents / 100.0
        }

        for (tax in allTaxes) {
            var taxableBase = 0.0
            for ((lineKey, item) in cartMap.entries) {
                val modTotal = item.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
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
            taxLine.text = "$label: ${MoneyUtils.centsToDisplay((taxAmount * 100).toLong())}"
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

        txtTotal.text = "Total: ${MoneyUtils.centsToDisplay((totalAmount * 100).toLong())}"

        syncOrderTotal()
    }

    private fun syncOrderTotal() {
        val oid = currentOrderId ?: return
        val totalInCents = Math.round(totalAmount * 100)

        val discountedSubtotal = (cartMap.entries.sumOf { (_, item) ->
            val modTotal = item.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
            (item.basePrice + modTotal) * item.quantity
        }) - (discountTotalCents / 100.0)

        val taxBreakdownList = mutableListOf<Map<String, Any>>()
        for (tax in allTaxes) {
            var taxableBase = 0.0
            for ((lineKey, item) in cartMap.entries) {
                val modTotal = item.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
                var lineTotal = (item.basePrice + modTotal) * item.quantity
                val lineDiscounts = appliedDiscounts.filter { it.lineKey == lineKey }
                if (lineDiscounts.isNotEmpty()) {
                    lineTotal = (lineTotal - lineDiscounts.sumOf { it.amountInCents } / 100.0).coerceAtLeast(0.0)
                }
                val subtotal = cartMap.entries.sumOf { (_, i) ->
                    val mt = i.modifiers.filter { it.action == "ADD" }.sumOf { it.price }
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

    private fun buildCartItemView(lineKey: String, item: CartItem): View {
        val itemLayout = LinearLayout(this)
        itemLayout.orientation = LinearLayout.VERTICAL
        itemLayout.setPadding(0, 4, 0, 16)

        val nameText = TextView(this)
        nameText.text = "${item.name} (Qty: ${item.quantity})"
        nameText.textSize = 14f
        nameText.setTypeface(null, Typeface.BOLD)
        itemLayout.addView(nameText)

        val modifiersTotal = item.modifiers.filter { it.action == "ADD" }.sumOf { it.price }

        for (modifier in item.modifiers) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val nameView = TextView(this)
            nameView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            if (modifier.action == "REMOVE") {
                nameView.text = "   NO ${modifier.name}"
                nameView.setTextColor(Color.parseColor("#D32F2F"))
                nameView.setTypeface(null, Typeface.BOLD)
                row.addView(nameView)
            } else {
                nameView.text = "   • ${modifier.name}"
                nameView.setTextColor(Color.parseColor("#2E7D32"))

                val priceView = TextView(this)
                priceView.text = "+${MoneyUtils.centsToDisplay((modifier.price * 100).toLong())}"
                priceView.setTextColor(Color.parseColor("#2E7D32"))

                row.addView(nameView)
                row.addView(priceView)
            }

            itemLayout.addView(row)
        }

        val unitPrice = item.basePrice + modifiersTotal
        val lineTotal = unitPrice * item.quantity

        // Show item-level discounts
        val lineDiscounts = appliedDiscounts.filter { it.lineKey == lineKey }
        if (lineDiscounts.isNotEmpty()) {
            for (ld in lineDiscounts) {
                val discountRow = LinearLayout(this)
                discountRow.orientation = LinearLayout.HORIZONTAL
                val discountLabel = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "   🏷 ${ld.discountName}"
                    setTextColor(Color.parseColor("#2E7D32"))
                    textSize = 12f
                }
                val discountValue = TextView(this).apply {
                    text = "-${MoneyUtils.centsToDisplay(ld.amountInCents)}"
                    setTextColor(Color.parseColor("#2E7D32"))
                    textSize = 12f
                }
                discountRow.addView(discountLabel)
                discountRow.addView(discountValue)
                itemLayout.addView(discountRow)
            }
        }

        val lineDiscountCents = lineDiscounts.sumOf { it.amountInCents }
        val effectiveTotal = (lineTotal * 100).toLong() - lineDiscountCents

        val subtotalText = TextView(this)
        if (lineDiscountCents > 0) {
            subtotalText.text = "Line Total: ${MoneyUtils.centsToDisplay(effectiveTotal)} (was ${MoneyUtils.centsToDisplay((lineTotal * 100).toLong())})"
            subtotalText.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            subtotalText.text = "Line Total: ${MoneyUtils.centsToDisplay((lineTotal * 100).toLong())}"
        }
        subtotalText.setTypeface(null, Typeface.BOLD)
        subtotalText.textSize = 13f
        itemLayout.addView(subtotalText)

        itemLayout.setOnClickListener {
            if (stockCountingEnabled) {
                val currentStock = item.stock
                val currentQty = item.quantity

                if (currentStock > 0 && currentQty + 1 > currentStock) {
                    Toast.makeText(this, "Only $currentStock in stock", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            item.quantity += 1
            cartMap[lineKey] = item
            refreshCart()

            val oid = currentOrderId ?: return@setOnClickListener
            orderEngine.upsertLineItem(
                orderId = oid,
                lineKey = lineKey,
                input = OrderEngine.LineItemInput(
                    itemId = item.itemId,
                    name = item.name,
                    quantity = item.quantity,
                    basePrice = item.basePrice,
                    modifiers = item.modifiers,
                    guestNumber = item.guestNumber,
                    taxMode = item.taxMode,
                    taxIds = item.taxIds
                ),
                onSuccess = {},
                onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            )
        }

        itemLayout.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Remove Item")
                .setMessage("Remove ${item.name} from cart?")
                .setPositiveButton("Yes") { _, _ ->
                    cartMap.remove(lineKey)
                    refreshCart()

                    val oid = currentOrderId ?: return@setPositiveButton
                    orderEngine.deleteLineItem(
                        orderId = oid,
                        lineKey = lineKey,
                        onSuccess = {
                            if (cartMap.isEmpty()) deleteOrderIfEmpty()
                        },
                        onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                    )
                }
                .setNegativeButton("No", null)
                .show()
            true
        }

        return itemLayout
    }

    // ----------------------------
    // FIRESTORE ORDER / ITEMS
    // ----------------------------
    private fun cartKey(itemId: String, modifiers: List<OrderModifier>, guest: Int = 0): String {
        val sorted = modifiers.sortedBy { "${it.action}|${it.name}|${it.price}" }
        val modsKey = sorted.joinToString("|") { "${it.action}:${it.name}:${it.price}" }
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
                            currentOrderId = null
                        }
                }
            }
    }
    private fun clearCart() {
        cartMap.clear()
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
    }

    // ── Bar Tab Capture Flow ────────────────────────────────────────

    private var captureDialog: AlertDialog? = null

    private fun startCaptureFlow(orderId: String) {
        btnCheckout.isEnabled = false
        showCaptureLoading("Capturing payment…")

        orderEngine.recomputeOrderTotals(
            orderId = orderId,
            onSuccess = { fetchTotalAndCapture(orderId) },
            onFailure = { e ->
                hideCaptureLoading()
                btnCheckout.isEnabled = true
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
                    btnCheckout.isEnabled = true
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
                            btnCheckout.isEnabled = true
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            .addOnFailureListener { e ->
                hideCaptureLoading()
                btnCheckout.isEnabled = true
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
                btnCheckout.isEnabled = true
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

            db.runBatch { batch ->
                batch.set(txRef, txData)
                batch.update(orderRef, orderUpdates)
            }.addOnSuccessListener {
                hideCaptureLoading()
                val afterCapture = {
                    clearCart()
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
                btnCheckout.isEnabled = true
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
        btnCheckout.isEnabled = true
    }
}