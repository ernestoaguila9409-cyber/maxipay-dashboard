package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.engine.PaymentService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TipAdjustmentActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var paymentService: PaymentService
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private val adapter = TipTransactionAdapter { item -> showTipDialog(item) }
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tip_adjustment)

        paymentService = PaymentService(this)
        recycler = findViewById(R.id.recyclerTips)
        txtEmpty = findViewById(R.id.txtEmpty)
        progressBar = findViewById(R.id.progressBar)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        progressBar.visibility = View.VISIBLE
        listenTransactions()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }

    private fun listenTransactions() {
        listener = db.collection("Transactions")
            .whereEqualTo("settled", false)
            .whereEqualTo("voided", false)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    progressBar.visibility = View.GONE
                    return@addSnapshotListener
                }

                val items = mutableListOf<TipTransactionItem>()
                for (doc in snapshots) {
                    val type = doc.getString("type") ?: "SALE"
                    if (type != "SALE" && type != "CAPTURE") continue

                    val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                    val hasCreditPayment = payments.any { p ->
                        val m = p as? Map<*, *> ?: return@any false
                        val pt = (m["paymentType"] as? String) ?: ""
                        val status = (m["status"] as? String) ?: ""
                        (pt.equals("Credit", true) || pt.equals("Debit", true))
                                && !status.equals("VOIDED", true)
                    }
                    if (!hasCreditPayment) continue

                    val totalPaidCents = doc.getLong("totalPaidInCents") ?: 0L
                    val tipCents = doc.getLong("tipAmountInCents") ?: 0L
                    val tipAdjusted = doc.getBoolean("tipAdjusted") ?: false
                    val orderNumber = doc.getLong("orderNumber") ?: 0L
                    val orderId = doc.getString("orderId") ?: ""
                    val batchId = doc.getString("batchId") ?: ""
                    val createdAt = doc.getDate("createdAt")

                    var cardBrand = ""
                    var last4 = ""
                    var gatewayRefId = ""
                    for (p in payments) {
                        val m = p as? Map<*, *> ?: continue
                        val pt = (m["paymentType"] as? String) ?: ""
                        val st = (m["status"] as? String) ?: ""
                        if ((pt.equals("Credit", true) || pt.equals("Debit", true))
                            && !st.equals("VOIDED", true)
                        ) {
                            cardBrand = (m["cardBrand"] as? String) ?: ""
                            last4 = (m["last4"] as? String) ?: ""
                            gatewayRefId = (m["referenceId"] as? String) ?: ""
                            break
                        }
                    }
                    if (gatewayRefId.isBlank()) continue

                    items.add(
                        TipTransactionItem(
                            transactionId = doc.id,
                            orderId = orderId,
                            orderNumber = orderNumber,
                            batchId = batchId,
                            totalPaidCents = totalPaidCents,
                            tipAmountCents = tipCents,
                            tipAdded = tipCents > 0L,
                            tipAdjusted = tipAdjusted,
                            cardBrand = cardBrand,
                            last4 = last4,
                            referenceId = gatewayRefId,
                            createdAt = createdAt
                        )
                    )
                }

                adapter.submitList(items)
                progressBar.visibility = View.GONE
                txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }
    }

    // ─── Tip dialog ──────────────────────────────────────────────────────

    private fun showTipDialog(item: TipTransactionItem) {
        val baseAmountCents = item.totalPaidCents - item.tipAmountCents
        val baseAmountDollars = baseAmountCents / 100.0

        val presets = TipConfig.getPresets(this)
        val customEnabled = TipConfig.isCustomTipEnabled(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }

        val amountLabel = TextView(this).apply {
            text = "Sale Amount: ${MoneyUtils.centsToDisplay(baseAmountCents)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#555555"))
        }
        container.addView(amountLabel)

        if (item.tipAdded) {
            val currentTip = TextView(this).apply {
                text = "Current Tip: ${MoneyUtils.centsToDisplay(item.tipAmountCents)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#2E7D32"))
                setPadding(0, dp(4), 0, 0)
            }
            container.addView(currentTip)
        }

        var selectedCents = 0L
        /** Custom keypad: value built digit-by-digit in cents (1→1¢, 100→$1.00). */
        var enteredCents = 0L

        val tipDisplay = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }

        var amountDisplay: TextView? = null

        fun updateTipDisplay() {
            tipDisplay.text = if (selectedCents > 0L) {
                "Tip: ${MoneyUtils.centsToDisplay(selectedCents)}"
            } else {
                "Tip: \$0.00"
            }
            amountDisplay?.text = String.format(Locale.US, "$%.2f", selectedCents / 100.0)
        }
        updateTipDisplay()

        var presetButtonRefs: List<MaterialButton> = emptyList()
        if (presets.isNotEmpty()) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(8))
            }

            val presetButtons = mutableListOf<MaterialButton>()
            for (pct in presets) {
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "$pct%"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    }
                    setOnClickListener {
                        selectedCents = MoneyUtils.dollarsToCents(baseAmountDollars * pct / 100.0)
                        enteredCents = 0L
                        presetButtons.forEach { b -> b.strokeColor = ColorStateList.valueOf(Color.parseColor("#CCCCCC")) }
                        strokeColor = ColorStateList.valueOf(Color.parseColor("#1976D2"))
                        updateTipDisplay()
                    }
                }
                presetButtons.add(btn)
                btnRow.addView(btn)
            }
            presetButtonRefs = presetButtons

            container.addView(btnRow)
        }

        container.addView(tipDisplay)

        if (customEnabled) {
            val customLabel = TextView(this).apply {
                text = if (presets.isNotEmpty()) "Or enter custom amount:" else "Enter tip amount:"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#777777"))
                setPadding(0, dp(12), 0, dp(4))
            }
            container.addView(customLabel)

            amountDisplay = TextView(this).apply {
                text = String.format(Locale.US, "$%.2f", 0.0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Color.parseColor("#1A1A1A"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(12))
            }
            container.addView(amountDisplay!!)

            val maxTipCents = 99_999_999L

            val keypad = GridLayout(this).apply {
                columnCount = 3
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            fun clearPresetHighlights() {
                presetButtonRefs.forEach { b ->
                    b.strokeColor = ColorStateList.valueOf(Color.parseColor("#CCCCCC"))
                }
            }

            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫")
            for (key in keys) {
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = key
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(Color.parseColor("#333333"))
                    minimumHeight = dp(52)
                    minimumWidth = 0
                    insetTop = 0
                    insetBottom = 0
                    val param = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    layoutParams = param
                    setOnClickListener {
                        var keypadChanged = false
                        when (key) {
                            "⌫" -> {
                                if (enteredCents > 0L) {
                                    enteredCents /= 10L
                                    selectedCents = enteredCents
                                    keypadChanged = true
                                }
                            }
                            "C" -> {
                                enteredCents = 0L
                                selectedCents = 0L
                                keypadChanged = true
                            }
                            else -> {
                                val d = key.toIntOrNull() ?: return@setOnClickListener
                                enteredCents = (enteredCents * 10L + d).coerceAtMost(maxTipCents)
                                selectedCents = enteredCents
                                keypadChanged = true
                            }
                        }
                        if (keypadChanged) clearPresetHighlights()
                        updateTipDisplay()
                    }
                }
                keypad.addView(btn)
            }

            container.addView(keypad)
        }

        val title = if (item.tipAdded) "Adjust Tip" else "Add Tip"
        AlertDialog.Builder(this)
            .setTitle("$title — Order #${item.orderNumber}")
            .setView(container)
            .setPositiveButton("Confirm") { _, _ ->
                if (selectedCents <= 0L) {
                    Toast.makeText(this, "Enter a valid tip amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyTip(item, selectedCents)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Atomic Firestore tip update ─────────────────────────────────────

    private fun applyTip(item: TipTransactionItem, newTipCents: Long) {
        val existingTipCents = item.tipAmountCents
        val baseAmountCents = item.totalPaidCents - existingTipCents
        val baseAmountDollars = baseAmountCents / 100.0
        val newTipDollars = newTipCents / 100.0

        Toast.makeText(this, "Processing tip adjustment\u2026", Toast.LENGTH_SHORT).show()

        paymentService.tipAdjust(
            originalAmount = baseAmountDollars,
            tipAmount = newTipDollars,
            referenceId = item.referenceId,
            onSuccess = { _ ->
                runOnUiThread {
                    finalizeTipInFirestore(item, newTipCents, existingTipCents, baseAmountCents)
                }
            },
            onFailure = { errorMsg ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Tip Adjust Failed")
                        .setMessage(errorMsg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    private fun finalizeTipInFirestore(
        item: TipTransactionItem,
        newTipCents: Long,
        oldTipCents: Long,
        baseAmountCents: Long
    ) {
        val txRef = db.collection("Transactions").document(item.transactionId)
        val orderRef = if (item.orderId.isNotBlank()) db.collection("Orders").document(item.orderId) else null
        val hasBatch = item.batchId.isNotBlank()
        val batchRef = if (hasBatch) db.collection("Batches").document(item.batchId) else null
        val deltaTipCents = newTipCents - oldTipCents

        db.runTransaction { transaction ->
            val txSnap = transaction.get(txRef)
            val orderSnap = if (orderRef != null) transaction.get(orderRef) else null
            val batchSnap = if (batchRef != null) transaction.get(batchRef) else null

            val newTotalPaidCents = baseAmountCents + newTipCents

            transaction.update(txRef, mapOf(
                "tipAmountInCents" to newTipCents,
                "totalPaidInCents" to newTotalPaidCents,
                "tipAdjusted" to true,
                "tipAdjustedAt" to Timestamp.now()
            ))

            if (orderSnap != null && orderRef != null) {
                val orderTotalCents = orderSnap.getLong("totalInCents") ?: 0L
                val orderTipCents = orderSnap.getLong("tipAmountInCents") ?: 0L
                val newOrderTotalCents = orderTotalCents - orderTipCents + newTipCents

                transaction.update(orderRef, mapOf(
                    "tipAmountInCents" to newTipCents,
                    "totalInCents" to newOrderTotalCents,
                    "updatedAt" to Date()
                ))
            }

            if (batchSnap != null && batchRef != null) {
                val batchClosed = batchSnap.getBoolean("closed") ?: true
                if (batchClosed) throw Exception("Batch is already closed")

                val currentBatchTips = batchSnap.getLong("totalTipsInCents") ?: 0L
                transaction.update(batchRef, mapOf(
                    "totalTipsInCents" to currentBatchTips + deltaTipCents,
                    "netTotalInCents" to FieldValue.increment(deltaTipCents)
                ))
            }

            null
        }.addOnSuccessListener {
            Toast.makeText(this, "Tip adjusted: ${MoneyUtils.centsToDisplay(newTipCents)}", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            Log.e("TIP_ADJUST", "Firestore transaction failed", e)
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Tip was approved by terminal but failed to save: ${e.message}\nPlease try again or contact support.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

// ─── Data class ──────────────────────────────────────────────────────────

data class TipTransactionItem(
    val transactionId: String,
    val orderId: String,
    val orderNumber: Long,
    val batchId: String,
    val totalPaidCents: Long,
    val tipAmountCents: Long,
    val tipAdded: Boolean,
    val tipAdjusted: Boolean,
    val cardBrand: String,
    val last4: String,
    val referenceId: String,
    val createdAt: Date?
)

// ─── Adapter ──────────────────────────────────────────────────────────────

class TipTransactionAdapter(
    private val onItemClick: (TipTransactionItem) -> Unit
) : RecyclerView.Adapter<TipTransactionAdapter.VH>() {

    private var items = listOf<TipTransactionItem>()
    private val dateFmt = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US)

    fun submitList(newItems: List<TipTransactionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardTip)
        val txtOrderNum: TextView = view.findViewById(R.id.txtOrderNum)
        val txtCardInfo: TextView = view.findViewById(R.id.txtCardInfo)
        val txtDate: TextView = view.findViewById(R.id.txtDate)
        val txtAmount: TextView = view.findViewById(R.id.txtAmount)
        val txtTipBadge: TextView = view.findViewById(R.id.txtTipBadge)
        val txtTipInfo: TextView = view.findViewById(R.id.txtTipInfo)
        val txtTotal: TextView = view.findViewById(R.id.txtTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tip_transaction, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.txtOrderNum.text = "Order #${item.orderNumber}"

        val cardLabel = buildString {
            if (item.cardBrand.isNotBlank()) append(item.cardBrand)
            if (item.last4.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append("****${item.last4}")
            }
            if (isEmpty()) append("Credit Card")
        }
        holder.txtCardInfo.text = cardLabel

        holder.txtDate.text = if (item.createdAt != null) dateFmt.format(item.createdAt) else ""

        val baseAmount = item.totalPaidCents - item.tipAmountCents
        holder.txtAmount.text = MoneyUtils.centsToDisplay(baseAmount)

        if (item.tipAdded) {
            holder.card.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
            holder.txtTipBadge.visibility = View.VISIBLE
            holder.txtTipInfo.visibility = View.VISIBLE
            holder.txtTipInfo.text = "Tip: ${MoneyUtils.centsToDisplay(item.tipAmountCents)}"
            holder.txtTotal.visibility = View.VISIBLE
            holder.txtTotal.text = "Total: ${MoneyUtils.centsToDisplay(item.totalPaidCents)}"
        } else {
            holder.card.setCardBackgroundColor(Color.WHITE)
            holder.txtTipBadge.visibility = View.GONE
            holder.txtTipInfo.visibility = View.GONE
            holder.txtTotal.visibility = View.GONE
        }

        holder.card.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
