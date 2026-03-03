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
            startActivity(Intent(this, TransactionActivity::class.java))
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
                        "createdAt" to Date()
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

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startOfDay = calendar.time

        db.collection("Transactions")
            .whereGreaterThanOrEqualTo("createdAt", startOfDay)
            .get()
            .addOnSuccessListener { documents ->

                var total = 0.0
                var count = 0

                for (doc in documents) {

                    val voided = doc.getBoolean("voided") ?: false
                    val settled = doc.getBoolean("settled") ?: false
                    val type = doc.getString("type") ?: "SALE"

                    val amount = if (type == "SALE") {
                        doc.getDouble("totalPaid") ?: 0.0
                    } else {
                        doc.getDouble("amount") ?: 0.0
                    }

                    if (!voided && !settled) {

                        when (type) {

                            "SALE" -> {
                                total += amount
                                count++
                            }

                            "REFUND" -> {
                                total -= amount
                                count++
                            }
                        }
                    }
                }

                txtTodayTotal.text = String.format(Locale.US, "Today: $%.2f", total)
                txtTodayCount.text = "Transactions: $count"
            }
    }
}