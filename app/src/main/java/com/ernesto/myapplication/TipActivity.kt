package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.firebase.firestore.FirebaseFirestore
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.Locale

class TipActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var orderId: String? = null
    private var batchId: String? = null
    private var subtotalCents: Long = 0L
    private var taxCents: Long = 0L
    private var totalCents: Long = 0L

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

        db.collection("Orders").document(oid).get()
            .addOnSuccessListener { doc ->
                totalCents = doc.getLong("totalInCents") ?: 0L

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
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load order", Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
    }

    private fun setupUI() {
        val txtTotal = findViewById<TextView>(R.id.txtTipOrderTotal)
        val txtBaseLabel = findViewById<TextView>(R.id.txtTipBaseLabel)
        val presetButtonsContainer = findViewById<LinearLayout>(R.id.presetButtonsContainer)
        val presetAmountsContainer = findViewById<LinearLayout>(R.id.presetAmountsContainer)
        val btnCustomTip = findViewById<Button>(R.id.btnCustomTip)
        val btnNoTip = findViewById<Button>(R.id.btnNoTip)

        txtTotal.text = "Order Total: ${MoneyUtils.centsToDisplay(totalCents)}"

        val isSubtotal = TipConfig.isSubtotalBased(this)
        val baseCents = if (isSubtotal) subtotalCents else totalCents
        val baseLabel = if (isSubtotal) "Tip calculated on subtotal (${MoneyUtils.centsToDisplay(subtotalCents)})"
            else "Tip calculated on total (${MoneyUtils.centsToDisplay(totalCents)})"
        txtBaseLabel.text = baseLabel

        val presets = TipConfig.getPresets(this)

        for (pct in presets) {
            val tipCents = roundCents(baseCents * pct / 100.0)
            val tipDisplay = MoneyUtils.centsToDisplay(tipCents)

            val btnCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(8, 0, 8, 0)
                layoutParams = lp
            }

            val pctBtn = Button(this).apply {
                text = "$pct%"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)

                val bg = GradientDrawable()
                bg.setColor(Color.parseColor("#6A4FB3"))
                bg.cornerRadius = 16f
                background = bg

                val btnLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = btnLp

                setOnClickListener { applyTip(tipCents) }
            }

            val amountLabel = TextView(this).apply {
                text = tipDisplay
                textSize = 13f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            }

            btnCol.addView(pctBtn)
            presetButtonsContainer.addView(btnCol)

            val amtCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
            amtCol.addView(amountLabel)
            presetAmountsContainer.addView(amtCol)
        }

        if (TipConfig.isCustomTipEnabled(this)) {
            btnCustomTip.visibility = android.view.View.VISIBLE
            btnCustomTip.setOnClickListener { showCustomTipDialog() }
        }

        btnNoTip.setOnClickListener { applyTip(0L) }
    }

    private fun showCustomTipDialog() {
        val input = EditText(this).apply {
            hint = "Tip amount ($)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
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

        db.collection("Orders").document(oid)
            .get()
            .addOnSuccessListener { doc ->
                val totalPaidInCents = doc.getLong("totalPaidInCents") ?: 0L
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

    override fun onBackPressed() {
        applyTip(0L)
    }
}
