    package com.ernesto.myapplication

    import android.content.Intent
    import android.graphics.Color
    import android.os.Bundle
    import android.view.View
    import android.widget.*
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.appcompat.app.AlertDialog
    import androidx.appcompat.app.AppCompatActivity
    import com.google.firebase.firestore.FirebaseFirestore
    import java.util.Date
    import java.util.Locale
    import com.ernesto.myapplication.engine.OrderEngine
    data class CartItem(
        val itemId: String,
        val name: String,
        var quantity: Int,
        val basePrice: Double,
        val stock: Long,
        val modifiers: List<Pair<String, Double>>
    )

    class MenuActivity : AppCompatActivity() {

        private var currentCategoryId: String? = null
        private val db = FirebaseFirestore.getInstance()

        // key = lineKey (doc id in Orders/{orderId}/items)
        private val cartMap = mutableMapOf<String, CartItem>()
        private lateinit var orderEngine: OrderEngine
        private lateinit var categoryContainer: LinearLayout
        private lateinit var itemContainer: GridLayout
        private lateinit var cartContainer: LinearLayout
        private lateinit var txtTotal: TextView
        private lateinit var btnCheckout: Button

        private var totalAmount = 0.0
        private var employeeName: String = ""
        private var currentOrderId: String? = null

        private val paymentLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    deductStockTransaction(
                        onSuccess = {
                            clearCart()
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
            employeeName = intent.getStringExtra("employeeName") ?: ""

            categoryContainer = findViewById(R.id.categoryContainer)
            itemContainer = findViewById(R.id.itemContainer)
            cartContainer = findViewById(R.id.cartContainer)
            txtTotal = findViewById(R.id.txtTotal)
            btnCheckout = findViewById(R.id.btnCheckout)

            // ✅ IMPORTANT: if we came from OrderDetail "Checkout", we are editing an existing order
            currentOrderId = intent.getStringExtra("ORDER_ID")

            loadCategories()

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

                orderEngine.recomputeOrderTotals(
                    orderId = oid,
                    onSuccess = {

                        val intent = Intent(this, PaymentActivity::class.java)
                        intent.putExtra("ORDER_ID", oid)
                        paymentLauncher.launch(intent)
                    },
                    onFailure = {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        // ----------------------------
        // LOAD EXISTING ORDER -> CART
        // ----------------------------

        private fun loadExistingOrderIntoCart(orderId: String) {
            // (Optional) load employeeName from the order if you want
            db.collection("Orders").document(orderId)
                .get()
                .addOnSuccessListener { orderDoc ->
                    val nameFromOrder = orderDoc.getString("employeeName")
                    if (!nameFromOrder.isNullOrBlank()) {
                        employeeName = nameFromOrder
                    }

                    // Load items
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
                                val basePriceInCents = doc.getLong("basePriceInCents") ?: 0L
                                val basePrice = basePriceInCents / 100.0

                                val modifiers = parseModifiers(doc.get("modifiers"))
                                val stock = 0L // not needed for display; stock is checked during deduction

                                cartMap[lineKey] = CartItem(
                                    itemId = itemId,
                                    name = name,
                                    quantity = qty,
                                    basePrice = basePrice,
                                    stock = stock,
                                    modifiers = modifiers
                                )
                            }

                            refreshCart()
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
                        button.setOnClickListener { loadItems(categoryId) }

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

                        val params = GridLayout.LayoutParams()
                        params.width = 0
                        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        params.setMargins(16, 16, 16, 16)
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

            for (groupId in groupIds) {
                db.collection("ModifierGroups")
                    .document(groupId)
                    .get()
                    .addOnSuccessListener { groupDoc ->

                        val groupName = groupDoc.getString("name") ?: return@addOnSuccessListener
                        val isRequired = groupDoc.getBoolean("required") ?: false
                        val maxSelection = groupDoc.getLong("maxSelection")?.toInt() ?: 1

                        if (isRequired) requiredGroups.add(groupId)

                        val groupContainer = LinearLayout(this)
                        groupContainer.orientation = LinearLayout.VERTICAL
                        groupContainer.setPadding(0, 40, 0, 40)

                        val title = TextView(this)
                        title.text = groupName
                        title.textSize = 18f
                        title.setTypeface(null, android.graphics.Typeface.BOLD)

                        val subtitle = TextView(this)
                        subtitle.text =
                            if (isRequired) "Required • Select up to $maxSelection"
                            else "Optional • Select up to $maxSelection"
                        subtitle.setTextColor(Color.GRAY)
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
            }

            dialog.show()
        }

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
                onSuccess = { oid ->

                    currentOrderId = oid

                    val lineKey = cartKey(itemId, modifiers)
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
                            modifiers = modifiers
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
                            modifiers = cartItem.modifiers
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

            for ((lineKey, item) in cartMap) {

                val itemLayout = LinearLayout(this)
                itemLayout.orientation = LinearLayout.VERTICAL
                itemLayout.setPadding(0, 0, 0, 24)

                val nameText = TextView(this)
                nameText.text = "${item.name} (Qty: ${item.quantity})"
                nameText.textSize = 16f
                nameText.setTypeface(null, android.graphics.Typeface.BOLD)
                itemLayout.addView(nameText)

                val modifiersTotal = item.modifiers.sumOf { it.second }

                for (modifier in item.modifiers) {
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL

                    val nameView = TextView(this)
                    nameView.text = "• ${modifier.first}"
                    nameView.layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )

                    val priceView = TextView(this)
                    priceView.text = "+$${String.format(Locale.US, "%.2f", modifier.second)}"

                    row.addView(nameView)
                    row.addView(priceView)
                    itemLayout.addView(row)
                }

                val unitPrice = item.basePrice + modifiersTotal
                val lineTotal = unitPrice * item.quantity

                val subtotalText = TextView(this)
                subtotalText.text = "Line Total: $${String.format(Locale.US, "%.2f", lineTotal)}"
                subtotalText.setTypeface(null, android.graphics.Typeface.BOLD)
                itemLayout.addView(subtotalText)

                // 🔥 TAP TO INCREASE QUANTITY
                itemLayout.setOnClickListener {

                    val currentStock = item.stock
                    val currentQty = item.quantity

                    if (currentStock > 0 && currentQty + 1 > currentStock) {
                        Toast.makeText(
                            this,
                            "Only $currentStock in stock",
                            Toast.LENGTH_SHORT
                        ).show()
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
                            modifiers = item.modifiers
                        ),
                        onSuccess = {},
                        onFailure = {
                            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                        }
                    )
                }

                // 🔥 LONG PRESS TO REMOVE
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

                                    if (cartMap.isEmpty()) {
                                        deleteOrderIfEmpty()
                                    }

                                },
                                onFailure = {
                                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        .setNegativeButton("No", null)
                        .show()
                    true
                }

                cartContainer.addView(itemLayout)
                totalAmount += lineTotal
            }

            txtTotal.text = "Total: $${String.format(Locale.US, "%.2f", totalAmount)}"
        }
        // ----------------------------
        // FIRESTORE ORDER / ITEMS
        // ----------------------------
        private fun cartKey(itemId: String, modifiers: List<Pair<String, Double>>): String {
            val sorted = modifiers.sortedBy { it.first + "|" + it.second }
            val modsKey = sorted.joinToString("|") { "${it.first}:${it.second}" }
            return "${itemId}__${modsKey}"
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

                                // 🔥 Go directly to Orders list
                                val intent = Intent(this, OrdersActivity::class.java)
                                intent.flags =
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP

                                startActivity(intent)
                                finish()
                            }
                    }
                }
        }
        private fun clearCart() {
            cartMap.clear()
            totalAmount = 0.0
            cartContainer.removeAllViews()
            txtTotal.text = "Total: $0.00"
        }
    }