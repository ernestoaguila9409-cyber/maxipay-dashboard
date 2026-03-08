package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.ernesto.myapplication.engine.MoneyUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

class SplitPaymentActivity : AppCompatActivity() {

    private var orderId: String? = null
    private var batchId: String? = null
    private var remainingBalance = 0.0

    private val db = FirebaseFirestore.getInstance()

    // Split-by-items state
    private data class OrderLineItem(val lineKey: String, val name: String, val quantity: Int, val lineTotalInCents: Long)
    private var orderItems = emptyList<OrderLineItem>()
    private var totalRemainingInCents = 0L
    private var currentPerson = 1
    private var assignedLineKeys = mutableSetOf<String>()
    private var personAmountsInCents = mutableListOf<Long>()
    private var byItemsMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_payment)

        supportActionBar?.title = "Split Payments"

        orderId = intent.getStringExtra("ORDER_ID")
        batchId = intent.getStringExtra("BATCH_ID")
        remainingBalance = intent.getDoubleExtra("REMAINING", 0.0)

        findViewById<Button>(R.id.btnSplitEvenly).setOnClickListener {
            showSplitNumberDialog()
        }

        findViewById<Button>(R.id.btnByItems).setOnClickListener {
            startSplitByItems()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }

    private fun startSplitByItems() {
        val oid = orderId
        if (oid == null || oid.isBlank()) {
            Toast.makeText(this, "No order", Toast.LENGTH_SHORT).show()
            return
        }
        if (remainingBalance <= 0) {
            Toast.makeText(this, "No remaining balance to split", Toast.LENGTH_SHORT).show()
            return
        }
        db.collection("Orders").document(oid).collection("items")
            .get()
            .addOnSuccessListener { snap ->
                val items = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val qty = (doc.getLong("quantity") ?: 1L).toInt()
                    val lineTotalInCents = doc.getLong("lineTotalInCents") ?: 0L
                    if (lineTotalInCents <= 0L) return@mapNotNull null
                    OrderLineItem(doc.id, name, qty, lineTotalInCents)
                }
                if (items.isEmpty()) {
                    Toast.makeText(this, "Order has no items", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                orderItems = items
                totalRemainingInCents = (remainingBalance * 100).toLong()
                currentPerson = 1
                assignedLineKeys.clear()
                personAmountsInCents.clear()
                byItemsMode = true
                setContentView(R.layout.activity_split_by_items)
                supportActionBar?.title = "Split by items"
                setupSplitByItemsListeners()
                buildPersonStep()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order items", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupSplitByItemsListeners() {
        findViewById<Button>(R.id.btnDonePerson).setOnClickListener { onDoneWithPerson() }
        findViewById<Button>(R.id.btnSplitByItemsCancel).setOnClickListener {
            byItemsMode = false
            setContentView(R.layout.activity_split_payment)
            supportActionBar?.title = "Split Payments"
            findViewById<Button>(R.id.btnSplitEvenly).setOnClickListener { showSplitNumberDialog() }
            findViewById<Button>(R.id.btnByItems).setOnClickListener { startSplitByItems() }
            findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        }
    }

    private fun buildPersonStep() {
        val txtPersonStep = findViewById<TextView>(R.id.txtPersonStep)
        val txtRemaining = findViewById<TextView>(R.id.txtRemaining)
        val itemsContainer = findViewById<LinearLayout>(R.id.itemsContainer)
        val btnDone = findViewById<Button>(R.id.btnDonePerson)

        txtPersonStep.text = "Person $currentPerson – Select items to pay"
        txtRemaining.text = "Remaining: ${MoneyUtils.centsToDisplay(totalRemainingInCents)}"

        itemsContainer.removeAllViews()
        val unassigned = orderItems.filter { it.lineKey !in assignedLineKeys }
        for (item in unassigned) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(this).apply {
                tag = item.lineKey
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(this).apply {
                text = "${item.name} (Qty: ${item.quantity})"
                setPadding(16, 0, 16, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val price = TextView(this).apply {
                text = MoneyUtils.centsToDisplay(item.lineTotalInCents)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(cb)
            row.addView(label)
            row.addView(price)
            itemsContainer.addView(row)
        }
        btnDone.isEnabled = true
        btnDone.visibility = android.view.View.VISIBLE
    }

    private fun onDoneWithPerson() {
        val itemsContainer = findViewById<LinearLayout>(R.id.itemsContainer)
        var selectedCents = 0L
        val selectedKeys = mutableListOf<String>()
        for (i in 0 until itemsContainer.childCount) {
            val row = itemsContainer.getChildAt(i) as? LinearLayout ?: continue
            val cb = row.getChildAt(0) as? CheckBox ?: continue
            if (cb.isChecked) {
                val lineKey = cb.tag as? String ?: continue
                val item = orderItems.find { it.lineKey == lineKey } ?: continue
                selectedCents += item.lineTotalInCents
                selectedKeys.add(lineKey)
            }
        }
        if (selectedKeys.isEmpty()) {
            Toast.makeText(this, "Select at least one item", Toast.LENGTH_SHORT).show()
            return
        }
        assignedLineKeys.addAll(selectedKeys)
        personAmountsInCents.add(selectedCents)
        totalRemainingInCents -= selectedCents
        if (totalRemainingInCents <= 0L) {
            showAllAssignedAndPay()
            return
        }
        currentPerson++
        buildPersonStep()
    }

    private fun showAllAssignedAndPay() {
        val txtPersonStep = findViewById<TextView>(R.id.txtPersonStep)
        val txtRemaining = findViewById<TextView>(R.id.txtRemaining)
        val itemsContainer = findViewById<LinearLayout>(R.id.itemsContainer)
        val btnDone = findViewById<Button>(R.id.btnDonePerson)

        txtPersonStep.text = "All assigned"
        txtRemaining.text = "Remaining: $0.00"
        itemsContainer.removeAllViews()
        btnDone.visibility = android.view.View.GONE

        val firstShareCents = personAmountsInCents.firstOrNull() ?: 0L
        val firstShareDollars = firstShareCents / 100.0
        val msg = buildString {
            personAmountsInCents.forEachIndexed { index, cents ->
                append("Person ${index + 1} pays ${MoneyUtils.centsToDisplay(cents)}\n")
            }
            append("\nPay first share now?")
        }
        AlertDialog.Builder(this)
            .setTitle("Split complete")
            .setMessage(msg)
            .setPositiveButton("Pay Person 1's share") { _, _ ->
                goToPayOneShare(firstShareDollars, personAmountsInCents.size)
            }
            .setNegativeButton("Cancel") { _, _ ->
                byItemsMode = false
                setContentView(R.layout.activity_split_payment)
                supportActionBar?.title = "Split Payments"
                findViewById<Button>(R.id.btnSplitEvenly).setOnClickListener { showSplitNumberDialog() }
                findViewById<Button>(R.id.btnByItems).setOnClickListener { startSplitByItems() }
                findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
            }
            .show()
    }

    private fun showSplitNumberDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 2, 3, 4..."
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Split evenly")
            .setMessage("How many ways do you want to split the bill?")
            .setView(input)
            .setPositiveButton("Split") { _, _ ->
                val text = input.text.toString()
                val count = text.toIntOrNull() ?: 0
                if (count < 2) {
                    Toast.makeText(this, "Enter at least 2", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (remainingBalance <= 0) {
                    Toast.makeText(this, "No remaining balance to split", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val perPerson = roundMoney(remainingBalance / count)
                if (perPerson <= 0) {
                    Toast.makeText(this, "Amount per person is too small", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showSplitResultDialog(count, perPerson)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSplitResultDialog(count: Int, perPerson: Double) {
        val formatted = String.format(Locale.US, "%.2f", perPerson)
        AlertDialog.Builder(this)
            .setTitle("Split into $count")
            .setMessage("Each person pays: $$formatted")
            .setPositiveButton("Pay one share now") { _, _ ->
                goToPayOneShare(perPerson, count)
            }
            .setNegativeButton("Done") { _, _ -> }
            .show()
    }

    private fun goToPayOneShare(amount: Double, totalCount: Int) {
        val oid = orderId ?: return
        val bid = batchId
        val intent = Intent(this, PaymentActivity::class.java).apply {
            putExtra("ORDER_ID", oid)
            putExtra("BATCH_ID", bid)
            putExtra("SPLIT_PAY_AMOUNT", amount)
            putExtra("SPLIT_TOTAL_COUNT", totalCount)
        }
        startActivity(intent)
        finish()
    }

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}
