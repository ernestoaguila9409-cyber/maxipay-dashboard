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
import java.util.Locale

data class CartItem(
    val itemId: String,
    val name: String,
    var quantity: Int,
    val price: Double,
    val stock: Long
)

class MenuActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val cartMap = mutableMapOf<String, CartItem>()

    private lateinit var categoryContainer: LinearLayout
    private lateinit var itemContainer: GridLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var txtTotal: TextView
    private lateinit var btnCheckout: Button

    private var totalAmount = 0.0

    private val paymentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                deductStockTransaction(
                    onSuccess = {
                        clearCart()
                        Toast.makeText(this, "Stock updated successfully", Toast.LENGTH_SHORT).show()
                        loadCategories()
                    },
                    onFailure = {
                        Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        categoryContainer = findViewById(R.id.categoryContainer)
        itemContainer = findViewById(R.id.itemContainer)
        cartContainer = findViewById(R.id.cartContainer)
        txtTotal = findViewById(R.id.txtTotal)
        btnCheckout = findViewById(R.id.btnCheckout)

        loadCategories()

        btnCheckout.setOnClickListener {
            if (cartMap.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, PaymentActivity::class.java)
            intent.putExtra("TOTAL_AMOUNT", totalAmount)
            paymentLauncher.launch(intent)
        }
    }

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
                    button.text =
                        "$name\n$${String.format(Locale.US, "%.2f", price)}\nStock: $stock"
                    button.setTextColor(Color.WHITE)

                    when {
                        stock <= 0 -> {
                            button.setBackgroundColor(Color.RED)
                            button.isEnabled = false
                        }
                        stock <= 5 -> {
                            button.setBackgroundColor(Color.parseColor("#F57C00"))
                        }
                        else -> {
                            button.setBackgroundColor(Color.parseColor("#6A4FB3"))
                        }
                    }

                    button.setOnClickListener {
                        checkAndShowModifiers(itemId, name, price, stock)
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

    private fun checkAndShowModifiers(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long
    ) {
        db.collection("ItemModifierGroups")
            .whereEqualTo("itemId", itemId)
            .get()
            .addOnSuccessListener { documents ->

                if (documents.isEmpty) {
                    addToCart(itemId, name, basePrice, stock)
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

        val selectedPricePerGroup = mutableMapOf<String, Double>()
        val selectedCountPerGroup = mutableMapOf<String, Int>()
        val requiredGroups = mutableSetOf<String>()

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Options")
            .setView(layout)
            .setPositiveButton("Add to Cart", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            addButton.setOnClickListener {

                for (requiredGroup in requiredGroups) {
                    if ((selectedCountPerGroup[requiredGroup] ?: 0) == 0) {
                        Toast.makeText(
                            this,
                            "Please select required options.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                }

                val totalModifierPrice =
                    selectedPricePerGroup.values.sum()

                val finalPrice = basePrice + totalModifierPrice

                addToCart(itemId, name, finalPrice, stock)
                dialog.dismiss()
            }
        }

        for (groupId in groupIds) {

            db.collection("ModifierGroups")
                .document(groupId)
                .get()
                .addOnSuccessListener { groupDoc ->

                    val groupName = groupDoc.getString("name") ?: ""
                    val isRequired = groupDoc.getBoolean("required") ?: false
                    val maxSelection =
                        groupDoc.getLong("maxSelection")?.toInt() ?: 1

                    if (isRequired) requiredGroups.add(groupId)

                    val title = TextView(this)
                    title.text =
                        if (isRequired) "$groupName (Required)" else groupName
                    title.textSize = 18f
                    title.setPadding(0, 20, 0, 10)

                    layout.addView(title)

                    db.collection("ModifierOptions")
                        .whereEqualTo("groupId", groupId)
                        .get()
                        .addOnSuccessListener { options ->

                            if (maxSelection == 1) {

                                val radioGroup = RadioGroup(this)
                                radioGroup.orientation = RadioGroup.VERTICAL
                                layout.addView(radioGroup)

                                val optionPriceMap = mutableMapOf<Int, Double>()

                                for (doc in options) {

                                    val optionName =
                                        doc.getString("name") ?: continue
                                    val optionPrice =
                                        doc.getDouble("price") ?: 0.0

                                    val radioButton =
                                        RadioButton(this)

                                    radioButton.text =
                                        "$optionName +$${String.format("%.2f", optionPrice)}"

                                    val id = View.generateViewId()
                                    radioButton.id = id
                                    optionPriceMap[id] = optionPrice

                                    radioGroup.addView(radioButton)
                                }

                                radioGroup.setOnCheckedChangeListener { _, checkedId ->

                                    val newPrice =
                                        optionPriceMap[checkedId] ?: 0.0

                                    selectedPricePerGroup[groupId] = newPrice
                                    selectedCountPerGroup[groupId] = 1
                                }

                            } else {

                                selectedPricePerGroup[groupId] = 0.0
                                selectedCountPerGroup[groupId] = 0

                                for (doc in options) {

                                    val optionName =
                                        doc.getString("name") ?: continue
                                    val optionPrice =
                                        doc.getDouble("price") ?: 0.0

                                    val checkBox = CheckBox(this)

                                    checkBox.text =
                                        "$optionName +$${String.format("%.2f", optionPrice)}"

                                    layout.addView(checkBox)

                                    checkBox.setOnCheckedChangeListener { _, isChecked ->

                                        var groupTotal =
                                            selectedPricePerGroup[groupId] ?: 0.0

                                        var count =
                                            selectedCountPerGroup[groupId] ?: 0

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

                                            groupTotal += optionPrice
                                            count++

                                        } else {

                                            groupTotal -= optionPrice
                                            count--
                                        }

                                        selectedPricePerGroup[groupId] = groupTotal
                                        selectedCountPerGroup[groupId] = count
                                    }
                                }
                            }
                        }
                }
        }

        dialog.show()
    }

    private fun addToCart(
        itemId: String,
        name: String,
        price: Double,
        stock: Long
    ) {

        val existingItem = cartMap[itemId]

        if (existingItem != null) {

            if (existingItem.quantity >= stock) {
                Toast.makeText(this, "Only $stock in stock.", Toast.LENGTH_SHORT).show()
                return
            }

            existingItem.quantity += 1

        } else {

            if (stock <= 0) {
                Toast.makeText(this, "Out of stock.", Toast.LENGTH_SHORT).show()
                return
            }

            cartMap[itemId] = CartItem(
                itemId = itemId,
                name = name,
                quantity = 1,
                price = price,
                stock = stock
            )
        }

        refreshCart()
    }

    private fun refreshCart() {

        cartContainer.removeAllViews()
        totalAmount = 0.0

        for ((_, item) in cartMap) {

            val itemTotal = item.quantity * item.price
            totalAmount += itemTotal

            val textView = TextView(this)
            textView.text =
                "${item.name} x${item.quantity} - $${String.format(Locale.US, "%.2f", itemTotal)}"

            cartContainer.addView(textView)
        }

        txtTotal.text =
            "Total: $${String.format(Locale.US, "%.2f", totalAmount)}"
    }

    private fun deductStockTransaction(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {

        db.runTransaction { transaction ->

            for ((_, cartItem) in cartMap) {

                val itemRef =
                    db.collection("MenuItems").document(cartItem.itemId)

                val snapshot = transaction.get(itemRef)
                val currentStock = snapshot.getLong("stock") ?: 0L

                if (currentStock < cartItem.quantity) {
                    throw Exception("Stock changed. Not enough inventory.")
                }

                val newStock = currentStock - cartItem.quantity
                transaction.update(itemRef, "stock", newStock)
            }

            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener {
                onFailure(it.message ?: "Transaction failed")
            }
    }

    private fun clearCart() {
        cartMap.clear()
        totalAmount = 0.0
        cartContainer.removeAllViews()
        txtTotal.text = "Total: $0.00"
    }
}