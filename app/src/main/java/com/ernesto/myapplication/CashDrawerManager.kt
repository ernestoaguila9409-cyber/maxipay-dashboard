package com.ernesto.myapplication

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

object CashDrawerManager {

    private const val TAG = "CashDrawerManager"

    private const val PREFS_NAME = "cash_drawer_settings"
    private const val KEY_AUTO_OPEN_ON_CASH = "autoOpenDrawerOnCash"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoOpenOnCash(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_OPEN_ON_CASH, true)

    fun setAutoOpenOnCash(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_OPEN_ON_CASH, enabled).apply()
    }

    // ── Manual open: always requires reason ─────────────────────────

    fun showManualOpenDialog(activity: Activity, batchId: String?) {
        val employeeName = SessionEmployee.getEmployeeName(activity)
        if (employeeName.isBlank()) {
            Toast.makeText(activity, "Please log in before opening the drawer", Toast.LENGTH_LONG).show()
            return
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 32)
        }

        val subtitle = TextView(activity).apply {
            text = "Select reason for opening the drawer"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 32)
        }
        root.addView(subtitle)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Open Cash Drawer")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        fun makeButton(label: String, icon: String, bgColor: String, onClick: () -> Unit): View {
            val btn = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 36, 0, 36)
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor(bgColor))
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }

            val iconTv = TextView(activity).apply {
                text = icon
                textSize = 22f
                setPadding(0, 0, 20, 0)
            }
            btn.addView(iconTv)

            val labelTv = TextView(activity).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }
            btn.addView(labelTv)

            return btn
        }

        root.addView(makeButton("Add Cash", "\uD83D\uDCB5", "#2E7D32") {
            showAmountDialog(activity, "CASH_ADD", batchId, employeeName)
        })

        root.addView(makeButton("Remove Cash", "\uD83D\uDCE4", "#C62828") {
            showAmountDialog(activity, "PAID_OUT", batchId, employeeName)
        })

        val loggedAs = TextView(activity).apply {
            text = "Logged in as: $employeeName"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        root.addView(loggedAs)

        dialog.show()
    }

    // ── Amount + note input dialog ──────────────────────────────────

    private fun showAmountDialog(
        activity: Activity,
        type: String,
        batchId: String?,
        employeeName: String
    ) {
        val label = if (type == "CASH_ADD") "Add Cash" else "Remove Cash"
        val accentColor = if (type == "CASH_ADD") "#2E7D32" else "#C62828"

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
        }

        val amountLabel = TextView(activity).apply {
            text = "Amount"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setTypeface(null, Typeface.BOLD)
        }
        container.addView(amountLabel)

        val amountInput = EditText(activity).apply {
            hint = "0.00"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 28f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 16)
        }
        container.addView(amountInput)

        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 8; bottomMargin = 24 }
        }
        container.addView(divider)

        val noteLabel = TextView(activity).apply {
            text = "Note (optional)"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setTypeface(null, Typeface.BOLD)
        }
        container.addView(noteLabel)

        val noteInput = EditText(activity).apply {
            hint = "e.g. Change for register"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(0, 12, 0, 12)
        }
        container.addView(noteInput)

        val employeeLabel = TextView(activity).apply {
            text = "By: $employeeName"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 24, 0, 0)
        }
        container.addView(employeeLabel)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(label)
            .setView(container)
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val confirmBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmBtn.setTextColor(Color.parseColor(accentColor))
            confirmBtn.setTypeface(null, Typeface.BOLD)
            confirmBtn.isEnabled = false

            amountInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val v = s?.toString()?.toDoubleOrNull()
                    confirmBtn.isEnabled = v != null && v > 0
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            confirmBtn.setOnClickListener {
                val amount = amountInput.text.toString().trim().toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    amountInput.error = "Enter a valid amount greater than 0"
                    return@setOnClickListener
                }
                val note = noteInput.text.toString().trim()
                dialog.dismiss()
                saveMovementAndOpen(activity, type, amount, note, batchId, null, employeeName)
            }

            amountInput.requestFocus()
        }

        dialog.show()
    }

    // ── Save movement record then open drawer ───────────────────────

    private fun saveMovementAndOpen(
        context: Context,
        type: String,
        amount: Double,
        note: String,
        batchId: String?,
        orderId: String?,
        employeeName: String
    ) {
        val amountInCents = Math.round(amount * 100)

        val record = hashMapOf<String, Any>(
            "type" to type,
            "amount" to amount,
            "amountInCents" to amountInCents,
            "note" to note,
            "createdAt" to Date(),
            "userId" to employeeName,
            "employeeName" to employeeName
        )
        if (!batchId.isNullOrBlank()) record["batchId"] = batchId
        if (!orderId.isNullOrBlank()) record["orderId"] = orderId

        val displayLabel = if (type == "CASH_ADD") "PAID_IN" else "PAID_OUT"

        FirebaseFirestore.getInstance()
            .collection("Transactions")
            .add(record)
            .addOnSuccessListener {
                Log.d(TAG, "Drawer opened by $employeeName ($displayLabel \$${String.format("%.2f", amount)})")
                openDrawerHardware(context)
                if (context is Activity) {
                    context.runOnUiThread {
                        Toast.makeText(context, "$displayLabel \$${String.format("%.2f", amount)} recorded", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save cash movement: ${e.message}", e)
                if (context is Activity) {
                    context.runOnUiThread {
                        Toast.makeText(context, "Failed to log drawer open: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    // ── Auto-open for cash payments (no dialog) ─────────────────────

    fun openCashDrawerIfCash(context: Context, paymentType: String) {
        if (!paymentType.equals("Cash", ignoreCase = true)) return
        if (!isAutoOpenOnCash(context)) return
        Log.d(TAG, "Auto-opening cash drawer for Cash payment")
        openDrawerHardware(context)
    }

    // ── Auto-open for cash refunds (no dialog) ─────────────────────

    fun openCashDrawerForRefund(context: Context) {
        if (!isAutoOpenOnCash(context)) return
        Log.d(TAG, "Auto-opening cash drawer for Cash refund")
        openDrawerHardware(context)
    }

    // ── Low-level drawer open ───────────────────────────────────────

    private fun openDrawerHardware(context: Context) {
        EscPosPrinter.openCashDrawer(context)
    }
}
