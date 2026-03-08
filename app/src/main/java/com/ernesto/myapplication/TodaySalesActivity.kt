package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class TodaySalesActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_today_sales)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Today's Sales"

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        findViewById<Button>(R.id.btnViewTransactions).setOnClickListener {
            db.collection("Batches")
                .whereEqualTo("closed", false)
                .limit(1)
                .get()
                .addOnSuccessListener { snap ->
                    val batchId = snap.documents.firstOrNull()?.id
                    val intent = Intent(this, TransactionActivity::class.java)
                    intent.putExtra("employeeName", SessionEmployee.getEmployeeName(this))
                    intent.putExtra("CURRENT_TRANSACTION", true)
                    if (batchId != null) intent.putExtra("BATCH_ID", batchId)
                    startActivity(intent)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Could not load batch", Toast.LENGTH_SHORT).show()
                }
        }

        findViewById<Button>(R.id.btnCurrentOrder).setOnClickListener {
            db.collection("Batches")
                .whereEqualTo("closed", false)
                .limit(1)
                .get()
                .addOnSuccessListener { snap ->
                    val batchId = snap.documents.firstOrNull()?.id
                    if (batchId == null) {
                        AlertDialog.Builder(this)
                            .setMessage("NO ORDERS AT THE MOMENT")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val intent = Intent(this, OrdersActivity::class.java)
                        intent.putExtra("employeeName", SessionEmployee.getEmployeeName(this))
                        intent.putExtra("CURRENT_ORDER", true)
                        intent.putExtra("BATCH_ID", batchId)
                        startActivity(intent)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Could not load batch", Toast.LENGTH_SHORT).show()
                }
        }

        loadTodayStats()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        loadTodayStats()
    }

    private fun loadTodayStats() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.time

        db.collection("Transactions")
            .get()
            .addOnSuccessListener { documents ->
                var total = 0.0
                var count = 0

                for (doc in documents) {
                    val voided = doc.getBoolean("voided") ?: false
                    val settled = doc.getBoolean("settled") ?: false
                    if (voided || settled) continue

                    val type = doc.getString("type") ?: "SALE"

                    if (type == "SALE") {
                        val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                        var todaysCents = 0L

                        for (p in payments) {
                            val map = p as? Map<*, *> ?: continue
                            val ts = (map["timestamp"] as? com.google.firebase.Timestamp)?.toDate()
                            if (ts == null || ts.before(startOfDay)) continue

                            val amountInCents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                            todaysCents += amountInCents
                        }

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
                                count++
                            }
                        }
                    } else if (type == "REFUND") {
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
