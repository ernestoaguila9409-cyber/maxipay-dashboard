package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*
import com.ernesto.myapplication.ModifierManagementActivity

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView

    private var currentBatchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ImageButton>(R.id.btnHamburger).setOnClickListener {
            startActivity(Intent(this, SideMenuActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnModifiers).setOnClickListener {
            startActivity(Intent(this, GlobalModifierActivity::class.java))
        }
        // 🔥 MENU ICON CLICK (TOP RIGHT)
        findViewById<ImageButton>(R.id.btnMenuTop).setOnClickListener {
            val intent = Intent(this, MenuOnlyActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            startActivity(intent)
        }

        val employeeName = intent.getStringExtra("employeeName") ?: ""
        val employeeRole = intent.getStringExtra("employeeRole") ?: ""

        val txtLoggedUser = findViewById<TextView>(R.id.txtLoggedUser)
        txtLoggedUser.text = "Logged in as: $employeeName ($employeeRole)"

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        ensureOpenBatch()

        // TAKE PAYMENT → OPEN MENU
        findViewById<Button>(R.id.btnTakePayment).setOnClickListener {
            val intent = Intent(this, MenuActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            intent.putExtra("employeeName", employeeName)  // ✅ ADD THIS
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnTransactions).setOnClickListener {
            val intent = Intent(this, TransactionActivity::class.java)
            intent.putExtra("employeeName", employeeName)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnSettle).setOnClickListener {
            startActivity(Intent(this, BatchManagementActivity::class.java))
        }

        findViewById<Button>(R.id.btnEmployees).setOnClickListener {
            startActivity(Intent(this, EmployeesActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnLogout).setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        loadTodayStats()
    }

    override fun onResume() {
        super.onResume()
        loadTodayStats()
    }

    private fun ensureOpenBatch() {

        db.collection("Batches")
            .whereEqualTo("closed", false)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->

                if (!documents.isEmpty) {
                    currentBatchId = documents.documents[0].id
                } else {

                    val newBatchId = "BATCH_${System.currentTimeMillis()}"

                    val batchData = hashMapOf(
                        "batchId" to newBatchId,
                        "total" to 0.0,
                        "count" to 0,
                        "closed" to false,
                        "createdAt" to Date(),
                        "type" to "OPEN" // distinguish from settlement batches
                    )

                    db.collection("Batches")
                        .document(newBatchId)
                        .set(batchData)
                        .addOnSuccessListener {
                            currentBatchId = newBatchId
                        }
                }
            }
    }

    private fun loadTodayStats() {
        // 1. Start of today (midnight)
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.time

        // We have mixed schemas:
        // - New "SALE" docs use payments[] + totalPaidInCents + createdAt
        // - Older or REFUND docs may use amount + timestamp/createdAt
        // To keep logic simple and robust, read all Transactions and filter in memory.
        db.collection("Transactions")
            .get()
            .addOnSuccessListener { documents ->
                var total = 0.0
                var count = 0

                for (doc in documents) {
                    // After settlement, we want dashboard stats to reset.
                    // So ignore any transaction that is settled or voided.
                    val voided = doc.getBoolean("voided") ?: false
                    val settled = doc.getBoolean("settled") ?: false
                    if (voided || settled) continue

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE") {
                        // New schema: payments array with per‑payment timestamp + amountInCents
                        val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                        var todaysCents = 0L

                        for (p in payments) {
                            val map = p as? Map<*, *> ?: continue
                            val ts = (map["timestamp"] as? com.google.firebase.Timestamp)?.toDate()
                            if (ts == null || ts.before(startOfDay)) continue

                            val amountInCents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                            todaysCents += amountInCents
                        }

                        // Fallback for older SALE docs without payments[]
                        if (todaysCents == 0L) {
                            val ts =
                                doc.getTimestamp("timestamp")?.toDate()
                                    ?: doc.getTimestamp("createdAt")?.toDate()
                            if (ts != null && !ts.before(startOfDay)) {
                                val amount = doc.getDouble("amount")
                                    ?: doc.getDouble("totalPaid")
                                    ?: 0.0
                                if (amount > 0.0) {
                                    total += amount
                                    count++
                                }
                            }
                        } else {
                            val amountToday = todaysCents / 100.0
                            if (amountToday > 0.0) {
                                total += amountToday
                                count++ // count one sale if it had any payment today
                            }
                        }
                    } else if (type == "REFUND") {
                        // Treat refunds as negative (only for open / unsettled refunds)
                        val ts =
                            doc.getTimestamp("createdAt")?.toDate()
                                ?: doc.getTimestamp("timestamp")?.toDate()
                        if (ts == null || ts.before(startOfDay)) continue

                        val amount = doc.getDouble("amount") ?: 0.0
                        if (amount > 0.0) {
                            total -= amount
                            count++
                        }
                    }
                }

                txtTodayTotal.text = String.format(Locale.US, "Today: $%.2f", total)
                txtTodayCount.text = "Transactions: $count"
            }
    }
}