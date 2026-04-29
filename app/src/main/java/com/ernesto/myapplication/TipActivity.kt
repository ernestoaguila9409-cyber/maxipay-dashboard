package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date

class TipActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var orderId: String? = null
    private var batchId: String? = null
    private var subtotalCents: Long = 0L
    private var taxCents: Long = 0L
    private var totalCents: Long = 0L

    private lateinit var btnCustomTip: Button

    private val paymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tip)

        orderId = intent.getStringExtra("ORDER_ID")
        batchId = intent.getStringExtra("BATCH_ID")
        if (orderId.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        loadOrderAndShowTips()
    }

    private fun loadOrderAndShowTips() {
        val oid = orderId ?: return

        fun applyDoc(doc: DocumentSnapshot) {
            totalCents = (doc.get("totalInCents") as? Number)?.toLong() ?: 0L

            @Suppress("UNCHECKED_CAST")
            val taxBreakdown = doc.get("taxBreakdown") as? List<Map<String, Any>>
            taxCents = 0L
            taxBreakdown?.forEach { tax ->
                taxCents += (tax["amountInCents"] as? Long)
                    ?: (tax["amountInCents"] as? Number)?.toLong() ?: 0L
            }
            subtotalCents = totalCents - taxCents

            setupUI()
        }

        db.collection("Orders").document(oid).get(Source.SERVER)
            .addOnSuccessListener { doc -> applyDoc(doc) }
            .addOnFailureListener {
                db.collection("Orders").document(oid).get()
                    .addOnSuccessListener { doc -> applyDoc(doc) }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_CANCELED)
                        finish()
                    }
            }
    }

    private fun setupUI() {
        val txtTotal = findViewById<TextView>(R.id.txtTipOrderTotal)
        val txtBaseLabel = findViewById<TextView>(R.id.txtTipBaseLabel)
        val presetContainer = findViewById<LinearLayout>(R.id.presetButtonsContainer)
        btnCustomTip = findViewById(R.id.btnCustomTip)
        val btnNoTip = findViewById<TextView>(R.id.btnNoTip)

        txtTotal.text = "Order Total: ${MoneyUtils.centsToDisplay(totalCents)}"

        val isSubtotal = TipConfig.isSubtotalBased(this)
        val baseCents = if (isSubtotal) subtotalCents else totalCents
        val baseLabel = if (isSubtotal)
            "Tip calculated on subtotal (${MoneyUtils.centsToDisplay(subtotalCents)})"
        else
            "Tip calculated on total (${MoneyUtils.centsToDisplay(totalCents)})"
        txtBaseLabel.text = baseLabel

        val presets = TipConfig.getPresets(this)
        val showCustom = TipConfig.isCustomTipEnabled(this)
        val businessName = ReceiptSettings.load(this).businessName

        CustomerDisplayManager.showTipScreen(
            activity = this,
            name = businessName,
            totalCents = totalCents,
            baseCents = baseCents,
            baseLabel = baseLabel,
            presets = presets,
            showCustomTip = showCustom,
            onTipSelected = { tipCents -> applyTip(tipCents) }
        )

        val cardHeight = dpToPx(72)
        val cardSpacing = dpToPx(10)

        for (pct in presets) {
            val tipCents = roundCents(baseCents * pct / 100.0)
            val tipDisplay = MoneyUtils.centsToDisplay(tipCents)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_tip_option_unselected)
                val lp = LinearLayout.LayoutParams(0, cardHeight, 1f)
                lp.setMargins(cardSpacing / 2, 0, cardSpacing / 2, 0)
                layoutParams = lp
                setPadding(0, dpToPx(10), 0, dpToPx(10))
                isClickable = true
                isFocusable = true
            }

            val pctLabel = TextView(this).apply {
                text = "$pct%"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2937"))
                gravity = Gravity.CENTER
            }

            val amtLabel = TextView(this).apply {
                text = tipDisplay
                textSize = 13f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(2), 0, 0)
            }

            card.addView(pctLabel)
            card.addView(amtLabel)

            card.setOnClickListener { applyTip(tipCents) }

            presetContainer.addView(card)
        }

        if (TipConfig.isCustomTipEnabled(this)) {
            btnCustomTip.visibility = View.VISIBLE
            btnCustomTip.setOnClickListener { showCustomTipDialog() }
        }

        btnNoTip.setOnClickListener { applyTip(0L) }
    }

    private fun showCustomTipDialog() {
        val input = EditText(this).apply {
            hint = "Tip amount ($)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Custom Tip")
            .setView(input)
            .setPositiveButton("Add Tip") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    val tipCents = MoneyUtils.dollarsToCents(amount)
                    applyTip(tipCents)
                } else {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTip(tipCents: Long) {
        val oid = orderId ?: return

        if (tipCents <= 0L) {
            navigateToPayment()
            return
        }

        val newTotalCents = totalCents + tipCents

        fun commitFromDoc(doc: DocumentSnapshot) {
            val totalPaidInCents = (doc.get("totalPaidInCents") as? Number)?.toLong() ?: 0L
            val newRemainingCents = (newTotalCents - totalPaidInCents).coerceAtLeast(0L)

            db.collection("Orders").document(oid)
                .update(
                    mapOf(
                        "tipAmountInCents" to tipCents,
                        "totalInCents" to newTotalCents,
                        "remainingInCents" to newRemainingCents,
                        "updatedAt" to Date()
                    )
                )
                .addOnSuccessListener { navigateToPayment() }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to save tip: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        db.collection("Orders").document(oid).get(Source.SERVER)
            .addOnSuccessListener { doc -> commitFromDoc(doc) }
            .addOnFailureListener {
                db.collection("Orders").document(oid).get()
                    .addOnSuccessListener { doc -> commitFromDoc(doc) }
            }
    }

    private fun navigateToPayment() {
        val oid = orderId ?: return
        val intent = Intent(this, PaymentActivity::class.java).apply {
            putExtra("ORDER_ID", oid)
            putExtra("BATCH_ID", batchId ?: "")
        }
        paymentLauncher.launch(intent)
    }

    private fun roundCents(value: Double): Long {
        return BigDecimal(value)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Skip tip and continue to payment; do not finish() here (that would return to the menu
        // and could race with starting payment).
        applyTip(0L)
    }
}
