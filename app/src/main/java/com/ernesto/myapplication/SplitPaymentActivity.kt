package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

class SplitPaymentActivity : AppCompatActivity() {

    private var orderId: String? = null
    private var batchId: String? = null
    private var remainingBalance = 0.0

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
            Toast.makeText(this, "By items", Toast.LENGTH_SHORT).show()
            // TODO: implement by items flow
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
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
