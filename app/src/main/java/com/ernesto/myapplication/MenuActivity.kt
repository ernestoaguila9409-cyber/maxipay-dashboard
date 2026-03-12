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
import com.ernesto.myapplication.engine.CaptureResult
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.OrderEngine
import com.ernesto.myapplication.engine.PaymentService
data class CartItem(
    val itemId: String,
    val name: String,
    var quantity: Int,
    val basePrice: Double,
    val stock: Long,
    val modifiers: List<Pair<String, Double>>,
    val guestNumber: Int = 0
)

class MenuActivity : AppCompatActivity() {

    private var currentCategoryId: String? = null
    private val db = FirebaseFirestore.getInstance()

    // key = lineKey (doc id in Orders/{orderId}/items)
    private val cartMap = mutableMapOf<String, CartItem>()
    private lateinit var orderEngine: OrderEngine
    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: LinearLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var cartTaxSummary: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnCheckout: Button
    private var selectedGuest: Int = 0

    private var totalAmount = 0.0
    private var enabledTaxes = mutableListOf<Triple<String, String, Double>>()
    private var employeeName: String = ""
    private var orderType: String = ""
    private var currentOrderId: String? = null
    private var tableId: String? = null
    private var tableName: String? = null
    private var guestCount: Int = 0
    private var guestNames: MutableList<String> = mutableListOf()

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

    private val paymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                deductStockTransaction(
                    onSuccess = {
                        clearCart()
                        // Start a brand‑new order after a successful payment
                        currentOrderId = null
                        Toast.makeText(this, "Stock updated successfully", Toast.LENGTH_SHORT).show()

                        currentCategoryId?.let {
                            loadItems(it)   // 🔥 reload only current category
                        }
                    },
                    onFailure = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        orderEngine = OrderEngine(db)
        paymentService = PaymentService(this)
        employeeName = intent.getStringExtra("employeeName") ?: ""
        orderType = intent.getStringExtra("orderType") ?: ""
        tableId = intent.getStringExtra("tableId")
        tableName = intent.getStringExtra("tableName")
        guestCount = intent.getIntExtra("guestCount", 0)
        guestNames = intent.getStringArrayListExtra("guestNames")?.toMutableList() ?: mutableListOf()
        currentBatchId = intent.getStringExtra("batchId")

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)
        cartContainer = findViewById(R.id.cartContainer)
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

        loadCategories()
        loadEnabledTaxes()
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
                            val intent = Intent(this, PaymentActivity::class.java)
                            intent.putExtra("ORDER_ID", oid)
                            intent.putExtra("BATCH_ID", currentBatchId ?: "")
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
        loadEnabledTaxes()
    }

    private fun loadEnabledTaxes() {
        db.collection("Taxes")
            .get()
            .addOnSuccessListener { snap ->
                enabledTaxes.clear()
                for (doc in snap.documents) {
                    val enabled = doc.getBoolean("enabled") ?: true
                    if (!enabled) continue
                    val name = doc.getString("name") ?: continue
                    val type = doc.getString("type") ?: continue
                    val amount = doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble() ?: continue
                    enabledTaxes.add(Triple(name, type, amount))
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

                            cartMap[lineKey] = CartItem(
                                itemId = itemId,
                                name = name,
                                quantity = qty,
                                basePrice = basePrice,
                                stock = stock,
                                modifiers = modifiers,
                                guestNumber = guest
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
                customerName = info.name
                customerPhone = info.phone.takeIf { it.isNotBlank() }
                customerEmail = info.email.takeIf { it.isNotBlank() }
                updateCustomerDisplay()

                val oid = currentOrderId
                if (!oid.isNullOrBlank()) {
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

                // Save to Customers collection only if not a duplicate (same name + email)
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
                .addOnFailureListener { e ->
                    android.util.Log.e("MenuActivity", "Failed to save customer to Customers collection", e)
                }
        }
    }

    private fun parseModifiers(raw: Any?): List<Pair<String, Double>> {
        // Your Firestore stores modifiers as:
        // modifiers: [ {first:"Medium", second:0.02}, {first:"grande", second:0.02} ]
        val list = raw as? List<*> ?: return emptyList()

        val out = mutableListOf<Pair<String, Double>>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val name = map["first"]?.toString() ?: continue
            val price = (map["second"] as? Number)?.toDouble() ?: 0.0
            out.add(name to price)
        }
        return out
    }

    // ----------------------------
    // LOAD CATEGORIES / ITEMS
    // ----------------------------

    private fun loadCategories() {
        categoryContainer.removeAllViews()

        db.collection("Categories")
            .get()
            .addOnSuccessListener { documents ->
                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val categoryId = doc.id

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

        db.collection("MenuItems")
            .whereEqualTo("categoryId", categoryId)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {
                    val itemId = doc.id
                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0
                    val stock = doc.getLong("stock") ?: 0L

                    val button = Button(this)

                    if (stock <= 0) {
                        // 🔴 OUT OF STOCK STYLE
                        button.text = "$name\n$${String.format(Locale.US, "%.2f", price)}\nOUT OF STOCK"
                        button.setBackgroundColor(Color.LTGRAY)
                        button.setTextColor(Color.DKGRAY)
                        button.isEnabled = false
                    } else {
                        // 🟣 NORMAL STYLE
                        button.text = "$name\n$${String.format(Locale.US, "%.2f", price)}\nStock: $stock"
                        button.setTextColor(Color.WHITE)
                        button.setBackgroundColor(Color.parseColor("#6A4FB3"))

                        button.setOnClickListener {
                            checkAndShowModifiers(itemId, name, price, stock)
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
    // MODIFIERS
    // ----------------------------

    private fun checkAndShowModifiers(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long
    ) {
        db.collection("ItemModifierGroups")
            .whereEqualTo("itemId", itemId)
            .orderBy("displayOrder")
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    addToCart(itemId, name, basePrice, stock, emptyList())
                } else {
                    val groupIds = documents.mapNotNull { it.getString("groupId") }
                    showModifierDialog(itemId, name, basePrice, stock, groupIds)
                }
            }
    }

    private fun showModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        groupIds: List<String>
    ) {
        val selectedModifiers = mutableListOf<Pair<String, Double>>()
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
                    if (groupName.isNotEmpty()) {
                        synchronized(fetchedGroups) {
                            fetchedGroups.add(GroupInfo(groupId, groupName, isRequired, maxSelection))
                        }
                        if (isRequired) requiredGroups.add(groupId)
                    }
                    pending--
                    if (pending == 0) {
                        // Required first, then optional; preserve original order within each
                        val sorted = fetchedGroups.sortedWith(
                            compareBy<GroupInfo> { !it.isRequired }.thenBy { orderIndex[it.groupId] ?: 0 }
                        )
                        buildAndShowModifierDialog(
                            itemId, name, basePrice, stock,
                            sorted, requiredGroups, selectedModifiers, selectedCountPerGroup
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
        selectedModifiers: MutableList<Pair<String, Double>>,
        selectedCountPerGroup: MutableMap<String, Int>
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
                addToCart(itemId, name, basePrice, stock, selectedModifiers)
                dialog.dismiss()
            }
        }

        for (group in sortedGroups) {
            val (groupId, groupName, isRequired, maxSelection) = group

            val groupContainer = LinearLayout(this)
            groupContainer.orientation = LinearLayout.VERTICAL
            groupContainer.setPadding(0, 40, 0, 40)

            val title = TextView(this)
            title.text = groupName
            title.textSize = 18f
            title.setTypeface(null, Typeface.BOLD)

            val subtitle = TextView(this)
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
            subtitle.setPadding(0, 8, 0, 16)

            groupContainer.addView(title)
            groupContainer.addView(subtitle)

            val divider = View(this)
            divider.setBackgroundColor(Color.LTGRAY)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            )
            groupContainer.addView(divider)

            mainLayout.addView(groupContainer)

            db.collection("ModifierOptions")
                .whereEqualTo("groupId", groupId)
                .get()
                .addOnSuccessListener { options ->
                    if (maxSelection == 1) {
                        val radioGroup = RadioGroup(this)
                        radioGroup.orientation = RadioGroup.VERTICAL
                        groupContainer.addView(radioGroup)

                        for (doc in options) {
                            val optionName = doc.getString("name") ?: continue
                            val optionPrice = doc.getDouble("price") ?: 0.0

                            val radioButton = RadioButton(this)
                            radioButton.text = "$optionName +$${String.format(Locale.US, "%.2f", optionPrice)}"
                            radioGroup.addView(radioButton)

                            radioButton.setOnClickListener {
                                selectedModifiers.removeAll { it.first == optionName }
                                selectedModifiers.add(optionName to optionPrice)
                                selectedCountPerGroup[groupId] = 1
                            }
                        }
                    } else {
                        selectedCountPerGroup[groupId] = 0

                        for (doc in options) {
                            val optionName = doc.getString("name") ?: continue
                            val optionPrice = doc.getDouble("price") ?: 0.0

                            val checkBox = CheckBox(this)
                            checkBox.text = "$optionName +$${String.format(Locale.US, "%.2f", optionPrice)}"
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
                                    selectedModifiers.add(optionName to optionPrice)
                                    count++
                                } else {
                                    selectedModifiers.remove(optionName to optionPrice)
                                    count--
                                }

                                selectedCountPerGroup[groupId] = count
                            }
                        }
                    }
                }
        }

        dialog.show()
    }

    private data class GroupInfo(
        val groupId: String,
        val groupName: String,
        val isRequired: Boolean,
        val maxSelection: Int
    )

    // ----------------------------
    // CART LOGIC (separate lines by modifiers)
    // ----------------------------

    private fun addToCart(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        modifiers: List<Pair<String, Double>>
    ) {

        orderEngine.ensureOrder(
            currentOrderId = currentOrderId,
            employeeName = employeeName,
            orderType = orderType,
            tableId = tableId,
            tableName = tableName,
            guestCount = if (guestCount > 0) guestCount else null,
            guestNames = if (guestNames.isNotEmpty()) guestNames else null,
            customerName = customerName,
            customerPhone = customerPhone,
            customerEmail = customerEmail,
            onSuccess = { oid ->

                currentOrderId = oid

                val guest = selectedGuest
                val lineKey = cartKey(itemId, modifiers, guest)
                val existingItem = cartMap[lineKey]
                val currentQtyInCart = existingItem?.quantity ?: 0

                if (stock <= 0) {
                    Toast.makeText(this, "Out of stock", Toast.LENGTH_SHORT).show()
                    return@ensureOrder
                }

                if (currentQtyInCart + 1 > stock) {
                    Toast.makeText(this, "Only $stock in stock", Toast.LENGTH_SHORT).show()
                    return@ensureOrder
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
                        guestNumber = guest
                    )
                }

                refreshCart()

                val cartItem = cartMap[lineKey] ?: return@ensureOrder

                orderEngine.upsertLineItem(
                    orderId = oid,
                    lineKey = lineKey,
                    input = OrderEngine.LineItemInput(
                        itemId = cartItem.itemId,
                        name = cartItem.name,
                        quantity = cartItem.quantity,
                        basePrice = cartItem.basePrice,
                        modifiers = cartItem.modifiers,
                        guestNumber = cartItem.guestNumber
                    ),
                    onSuccess = {},
                    onFailure = {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
                )
            },
            onFailure = {
                Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun refreshCart() {
        cartContainer.removeAllViews()
        totalAmount = 0.0

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

                        val modifiersTotal = item.modifiers.sumOf { it.second }
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

                val modifiersTotal = item.modifiers.sumOf { it.second }
                val unitPrice = item.basePrice + modifiersTotal
                totalAmount += unitPrice * item.quantity
            }
        }

        val subtotal = totalAmount
        cartTaxSummary.removeAllViews()

        val subtotalLabel = TextView(this)
        subtotalLabel.text = "Subtotal: ${MoneyUtils.centsToDisplay((subtotal * 100).toLong())}"
        subtotalLabel.textSize = 14f
        subtotalLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        cartTaxSummary.addView(subtotalLabel)

        for ((name, type, amount) in enabledTaxes) {
            val taxAmount = if (type == "PERCENTAGE") subtotal * amount / 100.0 else amount
            totalAmount += taxAmount
            val taxLine = TextView(this)
            val label = if (type == "PERCENTAGE") "$name (${String.format(Locale.US, "%.1f", amount)}%)" else name
            taxLine.text = "$label: ${MoneyUtils.centsToDisplay((taxAmount * 100).toLong())}"
            taxLine.textSize = 13f
            taxLine.setTextColor(Color.parseColor("#5D4E7B"))
            cartTaxSummary.addView(taxLine)
        }

        txtTotal.text = "Total: ${MoneyUtils.centsToDisplay((totalAmount * 100).toLong())}"
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

        val modifiersTotal = item.modifiers.sumOf { it.second }

        for (modifier in item.modifiers) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val nameView = TextView(this)
            nameView.text = "   • ${modifier.first}"
            nameView.setTextColor(Color.parseColor("#2E7D32"))
            nameView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val priceView = TextView(this)
            priceView.text = "+${MoneyUtils.centsToDisplay((modifier.second * 100).toLong())}"
            priceView.setTextColor(Color.parseColor("#2E7D32"))

            row.addView(nameView)
            row.addView(priceView)
            itemLayout.addView(row)
        }

        val unitPrice = item.basePrice + modifiersTotal
        val lineTotal = unitPrice * item.quantity

        val subtotalText = TextView(this)
        subtotalText.text = "Line Total: ${MoneyUtils.centsToDisplay((lineTotal * 100).toLong())}"
        subtotalText.setTypeface(null, Typeface.BOLD)
        subtotalText.textSize = 13f
        itemLayout.addView(subtotalText)

        itemLayout.setOnClickListener {
            val currentStock = item.stock
            val currentQty = item.quantity

            if (currentStock > 0 && currentQty + 1 > currentStock) {
                Toast.makeText(this, "Only $currentStock in stock", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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
                    guestNumber = item.guestNumber
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
    private fun cartKey(itemId: String, modifiers: List<Pair<String, Double>>, guest: Int = 0): String {
        val sorted = modifiers.sortedBy { it.first + "|" + it.second }
        val modsKey = sorted.joinToString("|") { "${it.first}:${it.second}" }
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
                            // Stay on Take Payment screen with empty cart (no navigation)
                        }
                }
            }
    }
    private fun clearCart() {
        cartMap.clear()
        totalAmount = 0.0
        customerName = null
        customerPhone = null
        customerEmail = null
        cartContainer.removeAllViews()
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
                deductStockTransaction(
                    onSuccess = {
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
                    },
                    onFailure = { msg ->
                        Toast.makeText(this, "Tab closed but stock error: $msg", Toast.LENGTH_LONG).show()
                        finish()
                    }
                )
            }.addOnFailureListener { e ->
                hideCaptureLoading()
                btnCheckout.isEnabled = true
                Toast.makeText(this, "Capture approved but save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // Fallback for older orders without preAuthFirestoreDocId: create new CAPTURE transaction
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
                deductStockTransaction(
                    onSuccess = {
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
                    },
                    onFailure = { msg ->
                        Toast.makeText(this, "Tab closed but stock error: $msg", Toast.LENGTH_LONG).show()
                        finish()
                    }
                )
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