package com.ernesto.myapplication

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MTR
import com.google.firebase.firestore.FirebaseFirestore
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.SplitReceiptCalculator
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

class SplitPaymentActivity : AppCompatActivity() {

    private var orderId: String? = null
    private var batchId: String? = null
    private var remainingBalance = 0.0

    private val db = FirebaseFirestore.getInstance()

    private data class OrderLineItem(val lineKey: String, val name: String, val quantity: Int, val lineTotalInCents: Long, val guestNumber: Int)

    private val orderLineItemDiff = object : DiffUtil.ItemCallback<OrderLineItem>() {
        override fun areItemsTheSame(oldItem: OrderLineItem, newItem: OrderLineItem) =
            oldItem.lineKey == newItem.lineKey

        override fun areContentsTheSame(oldItem: OrderLineItem, newItem: OrderLineItem) =
            oldItem == newItem
    }
    private var orderItems = emptyList<OrderLineItem>()
    private var totalRemainingInCents = 0L
    private var currentPerson = 1
    private var assignedLineKeys = mutableSetOf<String>()
    private var personAmountsInCents = mutableListOf<Long>()
    private var byItemsMode = false
    private var guestNames: List<String> = emptyList()
    private val itemGuestAssignment = mutableMapOf<String, Int>()

    private val guestColumnAdapters = mutableListOf<GuestColumnItemsAdapter>()
    private val guestTotalViews = mutableListOf<TextView>()
    private val guestEmptyHints = mutableListOf<TextView>()
    private lateinit var columnBgNormal: Drawable
    private lateinit var columnBgHighlight: Drawable

    private inner class GuestColumnDropHighlight(private val columnOuter: View) {
        private var enterDepth = 0

        fun dragEnter() {
            if (enterDepth++ == 0) {
                columnOuter.background =
                    columnBgHighlight.constantState?.newDrawable()?.mutate() ?: columnBgHighlight
            }
        }

        fun dragExit() {
            enterDepth = (enterDepth - 1).coerceAtLeast(0)
            if (enterDepth == 0) {
                columnOuter.background =
                    columnBgNormal.constantState?.newDrawable()?.mutate() ?: columnBgNormal
            }
        }

        fun reset() {
            enterDepth = 0
            columnOuter.background =
                columnBgNormal.constantState?.newDrawable()?.mutate() ?: columnBgNormal
        }
    }

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

    private fun getPersonLabel(personIndex: Int): String {
        val name = guestNames.getOrNull(personIndex)?.takeIf { it.isNotBlank() }
        return name ?: "Person ${personIndex + 1}"
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
        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { orderDoc ->
                @Suppress("UNCHECKED_CAST")
                guestNames = (orderDoc.get("guestNames") as? List<String>) ?: emptyList()
                val guestCount = (orderDoc.getLong("guestCount") ?: 0L).toInt()

                db.collection("Orders").document(oid).collection("items")
                    .get()
                    .addOnSuccessListener { snap ->
                        val items = snap.documents.mapNotNull { doc ->
                            val name = doc.getString("name") ?: return@mapNotNull null
                            val qty = (doc.getLong("quantity") ?: 1L).toInt()
                            val lineTotalInCents = doc.getLong("lineTotalInCents") ?: 0L
                            val guestNum = (doc.getLong("guestNumber") ?: 0L).toInt()
                            if (lineTotalInCents <= 0L) return@mapNotNull null
                            OrderLineItem(doc.id, name, qty, lineTotalInCents, guestNum)
                        }
                        if (items.isEmpty()) {
                            Toast.makeText(this, "Order has no items", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }
                        orderItems = items
                        totalRemainingInCents = (remainingBalance * 100).toLong()

                        itemGuestAssignment.clear()
                        val hasGuests = items.any { it.guestNumber > 0 }
                        if (hasGuests) {
                            for (item in items) {
                                itemGuestAssignment[item.lineKey] = (item.guestNumber - 1).coerceAtLeast(0)
                            }
                        } else {
                            for (item in items) {
                                itemGuestAssignment[item.lineKey] = 0
                            }
                        }

                        val maxGuestFromItems = items.maxOf { it.guestNumber }
                        val totalGuests = maxOf(maxGuestFromItems, guestCount, guestNames.size, 1)
                        if (guestNames.size < totalGuests) {
                            val padded = guestNames.toMutableList()
                            while (padded.size < totalGuests) padded.add("")
                            guestNames = padded
                        }

                        byItemsMode = true
                        setContentView(R.layout.activity_split_by_items)
                        supportActionBar?.title = "Split by Guest"
                        columnBgNormal = ContextCompat.getDrawable(this, R.drawable.split_guest_column_bg)!!
                        columnBgHighlight = ContextCompat.getDrawable(this, R.drawable.split_guest_column_bg_highlight)!!
                        setupSplitByItemsListeners()
                        buildSplitView()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load order items", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupSplitByItemsListeners() {
        findViewById<Button>(R.id.btnDonePerson).setOnClickListener { onDoneWithSplitting() }
        findViewById<Button>(R.id.btnSplitByItemsCancel).setOnClickListener {
            byItemsMode = false
            setContentView(R.layout.activity_split_payment)
            supportActionBar?.title = "Split Payments"
            findViewById<Button>(R.id.btnSplitEvenly).setOnClickListener { showSplitNumberDialog() }
            findViewById<Button>(R.id.btnByItems).setOnClickListener { startSplitByItems() }
            findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v,
        resources.displayMetrics
    ).toInt()

    private fun itemsForGuest(guestIdx: Int): List<OrderLineItem> =
        orderItems.filter { (itemGuestAssignment[it.lineKey] ?: 0) == guestIdx }

    private fun startLineDrag(item: OrderLineItem, view: View) {
        val clipData = ClipData.newPlainText("lineKey", item.lineKey)
        ViewCompat.startDragAndDrop(view, clipData, View.DragShadowBuilder(view), null, 0)
    }

    private fun refreshGuestColumns(vararg guestIndices: Int) {
        for (idx in guestIndices.distinct()) {
            if (idx !in guestColumnAdapters.indices) continue
            val list = itemsForGuest(idx)
            guestColumnAdapters[idx].submitGuestLines(list)
            guestTotalViews[idx].text = MoneyUtils.centsToDisplay(list.sumOf { it.lineTotalInCents })
            guestEmptyHints[idx].visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun guestColumnDropListener(guestIdx: Int, highlight: GuestColumnDropHighlight): View.OnDragListener {
        return View.OnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> {
                    highlight.dragEnter()
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    highlight.dragExit()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    highlight.reset()
                    val lineKey = event.clipData?.getItemAt(0)?.text?.toString()
                        ?: return@OnDragListener false
                    val currentGuest = itemGuestAssignment[lineKey] ?: 0
                    if (currentGuest != guestIdx) {
                        itemGuestAssignment[lineKey] = guestIdx
                        refreshGuestColumns(currentGuest, guestIdx)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    highlight.reset()
                    true
                }
                else -> true
            }
        }
    }

    private fun buildSplitView() {
        val itemsContainer = findViewById<LinearLayout>(R.id.itemsContainer)
        itemsContainer.removeAllViews()
        guestColumnAdapters.clear()
        guestTotalViews.clear()
        guestEmptyHints.clear()

        val pool = RecyclerView.RecycledViewPool()

        val guestCount = guestNames.size.coerceAtLeast(1)
        val colW = resources.getDimensionPixelSize(R.dimen.split_guest_column_width)
        val addColW = resources.getDimensionPixelSize(R.dimen.split_guest_add_column_width)
        val headerTopR = dp(12f).toFloat()

        for (guestIdx in 0 until guestCount) {
            val list = itemsForGuest(guestIdx)
            val guestTotal = list.sumOf { it.lineTotalInCents }

            val columnOuter = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = columnBgNormal.constantState?.newDrawable()?.mutate() ?: columnBgNormal
                layoutParams = LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(12f)
                }
            }

            val headerBg = GradientDrawable().apply {
                setColor(Color.parseColor("#6A4FB3"))
                cornerRadii = floatArrayOf(
                    headerTopR, headerTopR,
                    headerTopR, headerTopR,
                    0f, 0f,
                    0f, 0f
                )
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = headerBg
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val headerLabel = TextView(this).apply {
                text = getPersonLabel(guestIdx)
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val headerTotal = TextView(this).apply {
                text = MoneyUtils.centsToDisplay(guestTotal)
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
            }
            headerRow.addView(headerLabel)
            headerRow.addView(headerTotal)
            guestTotalViews.add(headerTotal)

            val body = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            }

            val emptyHint = TextView(this).apply {
                text = "Drop items here"
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
            guestEmptyHints.add(emptyHint)

            val rv = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@SplitPaymentActivity)
                setRecycledViewPool(pool)
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val adapter = GuestColumnItemsAdapter()
            adapter.submitGuestLines(list)
            rv.adapter = adapter
            guestColumnAdapters.add(adapter)

            val dropHighlight = GuestColumnDropHighlight(columnOuter)
            val dropListener = guestColumnDropListener(guestIdx, dropHighlight)
            headerRow.setOnDragListener(dropListener)
            body.setOnDragListener(dropListener)
            rv.setOnDragListener(dropListener)

            body.addView(rv)
            body.addView(emptyHint)
            emptyHint.setOnDragListener(dropListener)

            columnOuter.addView(headerRow)
            columnOuter.addView(body)

            itemsContainer.addView(columnOuter)
        }

        val addColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.split_guest_add_column_bg)
            layoutParams = LinearLayout.LayoutParams(addColW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(4f)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                guestNames = guestNames.toMutableList().apply { add("") }
                buildSplitView()
            }
        }
        addColumn.addView(
            TextView(this).apply {
                text = "+ Add Guest"
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(Color.parseColor("#6A4FB3"))
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8f), 0, dp(8f), 0)
            }
        )
        itemsContainer.addView(addColumn)
    }

    private inner class GuestColumnItemsAdapter :
        ListAdapter<OrderLineItem, GuestColumnItemsAdapter.VH>(orderLineItemDiff) {

        fun submitGuestLines(newItems: List<OrderLineItem>) {
            submitList(newItems.toList())
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_split_guest_line, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.name.text = item.name
            holder.qty.text = "×${item.quantity}"
            holder.price.text = MoneyUtils.centsToDisplay(item.lineTotalInCents)
            holder.itemView.setOnLongClickListener { v ->
                startLineDrag(item, v)
                true
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.txtItemName)
            val qty: TextView = view.findViewById(R.id.txtQty)
            val price: TextView = view.findViewById(R.id.txtPrice)
        }
    }

    private fun onDoneWithSplitting() {
        personAmountsInCents.clear()
        val guestCount = guestNames.size.coerceAtLeast(1)
        val guestsWithItems = mutableListOf<Pair<Int, Long>>()

        for (guestIdx in 0 until guestCount) {
            val items = orderItems.filter { (itemGuestAssignment[it.lineKey] ?: 0) == guestIdx }
            val total = items.sumOf { it.lineTotalInCents }
            if (total > 0L) {
                guestsWithItems.add(guestIdx to total)
                personAmountsInCents.add(total)
            }
        }

        if (guestsWithItems.isEmpty()) {
            Toast.makeText(this, "No items to pay", Toast.LENGTH_SHORT).show()
            return
        }

        val oid = orderId ?: return
        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { orderDoc ->
                db.collection("Orders").document(oid).collection("items").get()
                    .addOnSuccessListener { itemsSnap ->
                        val itemDocs = itemsSnap.documents
                        val arr = JSONArray()
                        for ((guestIdx, _) in guestsWithItems) {
                            val keys = orderItems
                                .filter { (itemGuestAssignment[it.lineKey] ?: 0) == guestIdx }
                                .map { it.lineKey }
                            if (keys.isEmpty()) continue
                            val totalCents = SplitReceiptCalculator.shareTotalCentsForLineKeys(
                                orderDoc, itemDocs, keys.toSet()
                            )
                            val o = JSONObject()
                            o.put("guestIndex", guestIdx)
                            o.put("guestLabel", getPersonLabel(guestIdx))
                            o.put("lineKeys", JSONArray(keys))
                            o.put("amountCents", totalCents)
                            arr.put(o)
                        }
                        if (arr.length() == 0) {
                            Toast.makeText(this, "No items to pay", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val firstGuestIdx = guestsWithItems.first().first
                        val firstTotalCents = (0 until arr.length())
                            .map { arr.getJSONObject(it).getLong("amountCents") }
                            .first()

                        if (arr.length() == 1) {
                            AlertDialog.Builder(this)
                                .setTitle("Pay Full Bill")
                                .setMessage("${getPersonLabel(firstGuestIdx)} pays ${MoneyUtils.centsToDisplay(firstTotalCents)}")
                                .setPositiveButton("Pay Now") { _, _ ->
                                    goToPayByItemsShares(arr.toString())
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            return@addOnSuccessListener
                        }

                        val msg = buildString {
                            for (i in 0 until arr.length()) {
                                val row = arr.getJSONObject(i)
                                append("${row.getString("guestLabel")} pays ${MoneyUtils.centsToDisplay(row.getLong("amountCents"))}\n")
                            }
                            append("\nPay first share now?")
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Split complete")
                            .setMessage(msg)
                            .setPositiveButton("Pay ${getPersonLabel(firstGuestIdx)}'s share") { _, _ ->
                                goToPayByItemsShares(arr.toString())
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load items", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showSplitNumberDialog() {
        val imm = getSystemService(InputMethodManager::class.java)
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            showSoftInputOnFocus = false
            hint = "e.g. 2, 3, 4..."
            setPadding(48, 32, 48, 32)
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener { v -> v.requestFocus() }
            setOnFocusChangeListener { v, _ ->
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }

        fun applyKey(key: String) {
            val s = input.text?.toString() ?: ""
            when (key) {
                "⌫" -> if (s.isNotEmpty()) {
                    val ns = s.dropLast(1)
                    input.setText(ns)
                    input.setSelection(ns.length)
                }
                "C" -> input.setText("")
                else -> {
                    val ns = s + key
                    input.setText(ns)
                    input.setSelection(ns.length)
                }
            }
        }

        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫")) {
            grid.addView(
                MaterialButton(this, null, MTR.attr.materialButtonOutlinedStyle).apply {
                    text = k
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    minimumHeight = dp(48f)
                    minimumWidth = 0
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                    }
                    setOnClickListener { applyKey(k) }
                }
            )
        }

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(4f))
            addView(TextView(this@SplitPaymentActivity).apply {
                text = "How many ways do you want to split the bill?"
                textSize = 15f
                setPadding(dp(4f), 0, dp(4f), dp(8f))
            })
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) })
            addView(grid)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Split evenly")
            .setView(dialogLayout)
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
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        dialog.show()
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
            putExtra("SPLIT_MODE", "evenly")
            putExtra("SPLIT_PAY_AMOUNT", amount)
            putExtra("SPLIT_TOTAL_COUNT", totalCount)
        }
        startActivity(intent)
        finish()
    }

    private fun goToPayByItemsShares(sharesJson: String) {
        val oid = orderId ?: return
        val bid = batchId
        val intent = Intent(this, PaymentActivity::class.java).apply {
            putExtra("ORDER_ID", oid)
            putExtra("BATCH_ID", bid)
            putExtra("SPLIT_MODE", "items")
            putExtra("SPLIT_SHARES_JSON", sharesJson)
        }
        startActivity(intent)
        finish()
    }

    private fun roundMoney(value: Double): Double {
        return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}
