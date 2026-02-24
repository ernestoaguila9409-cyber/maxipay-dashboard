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
    val basePrice: Double,
    val stock: Long,
    val modifiers: List<Pair<String, Double>>
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

                    button.setBackgroundColor(Color.parseColor("#6A4FB3"))

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
                        Toast.makeText(
                            this,
                            "Please select required options.",
                            Toast.LENGTH_SHORT
                        ).show()
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

                    // 🔥 CREATE GROUP CONTAINER
                    val groupContainer = LinearLayout(this)
                    groupContainer.orientation = LinearLayout.VERTICAL
                    groupContainer.setPadding(0, 40, 0, 40)

                    // TITLE
                    val title = TextView(this)
                    title.text = groupName
                    title.textSize = 18f
                    title.setTypeface(null, android.graphics.Typeface.BOLD)

                    // SUBTITLE
                    val subtitle = TextView(this)
                    subtitle.text =
                        if (isRequired)
                            "Required • Select up to $maxSelection"
                        else
                            "Optional • Select up to $maxSelection"

                    subtitle.setTextColor(Color.GRAY)
                    subtitle.setPadding(0, 8, 0, 16)

                    groupContainer.addView(title)
                    groupContainer.addView(subtitle)

                    // DIVIDER
                    val divider = View(this)
                    divider.setBackgroundColor(Color.LTGRAY)
                    divider.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        2
                    )
                    groupContainer.addView(divider)

                    // 🔥 ADD GROUP CONTAINER TO MAIN
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
                                    radioButton.text =
                                        "$optionName +$${String.format("%.2f", optionPrice)}"

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
                                    checkBox.text =
                                        "$optionName +$${String.format("%.2f", optionPrice)}"

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

    private fun addToCart(
        itemId: String,
        name: String,
        basePrice: Double,
        stock: Long,
        modifiers: List<Pair<String, Double>>
    ) {

        val existingItem = cartMap[itemId]

        if (existingItem != null) {
            existingItem.quantity += 1
        } else {
            cartMap[itemId] = CartItem(
                itemId,
                name,
                1,
                basePrice,
                stock,
                modifiers
            )
        }

        refreshCart()
    }

    private fun refreshCart() {

        cartContainer.removeAllViews()
        totalAmount = 0.0

        for ((_, item) in cartMap) {

            val itemLayout = LinearLayout(this)
            itemLayout.orientation = LinearLayout.VERTICAL
            itemLayout.setPadding(0, 0, 0, 24)

            val nameText = TextView(this)
            nameText.text = item.name
            nameText.textSize = 16f
            nameText.setTypeface(null, android.graphics.Typeface.BOLD)
            itemLayout.addView(nameText)

            var modifiersTotal = 0.0

            for (modifier in item.modifiers) {

                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

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

                modifiersTotal += modifier.second
            }

            val qtyText = TextView(this)
            qtyText.text = "Qty: ${item.quantity}"
            itemLayout.addView(qtyText)

            val singleItemTotal = item.basePrice + modifiersTotal
            val itemTotal = singleItemTotal * item.quantity

            val subtotalText = TextView(this)
            subtotalText.text =
                "Subtotal: $${String.format(Locale.US, "%.2f", itemTotal)}"
            subtotalText.setTypeface(null, android.graphics.Typeface.BOLD)
            itemLayout.addView(subtotalText)

            val divider = View(this)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply { setMargins(0, 16, 0, 16) }
            divider.setBackgroundColor(Color.LTGRAY)

            itemLayout.addView(divider)

            itemLayout.setOnLongClickListener {

                AlertDialog.Builder(this)
                    .setTitle("Remove Item")
                    .setMessage("Remove ${item.name} from cart?")
                    .setPositiveButton("Yes") { _, _ ->
                        cartMap.remove(item.itemId)
                        refreshCart()
                    }
                    .setNegativeButton("No", null)
                    .show()

                true
            }

            cartContainer.addView(itemLayout)

            totalAmount += itemTotal
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