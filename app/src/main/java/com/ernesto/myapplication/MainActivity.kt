package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView

    private var currentBatchId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var employeeName = intent.getStringExtra("employeeName") ?: ""
        var employeeRole = intent.getStringExtra("employeeRole") ?: ""
        if (employeeName.isNotBlank()) {
            SessionEmployee.setEmployee(this, employeeName, employeeRole)
        } else {
            employeeName = SessionEmployee.getEmployeeName(this)
            employeeRole = SessionEmployee.getEmployeeRole(this)
        }

        val txtLoggedUser = findViewById<TextView>(R.id.txtLoggedUser)
        txtLoggedUser.text = "Logged in as: $employeeName ($employeeRole)"

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        ensureOpenBatch()

        findViewById<android.view.View>(R.id.btnDineIn).setOnClickListener {
            val intent = Intent(this, TableSelectionActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            intent.putExtra("employeeName", employeeName)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btnToGo).setOnClickListener {
            val intent = Intent(this, MenuActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            intent.putExtra("employeeName", employeeName)
            intent.putExtra("orderType", "TO_GO")
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btnBar).setOnClickListener {
            val intent = Intent(this, BarTabsActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            intent.putExtra("employeeName", employeeName)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btnTransactions).setOnClickListener {
            val intent = Intent(this, TransactionActivity::class.java)
            intent.putExtra("employeeName", employeeName)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btnSettle).setOnClickListener {
            startActivity(Intent(this, BatchManagementActivity::class.java))
        }

        findViewById<android.view.View>(R.id.todaySalesArea).setOnClickListener {
            startActivity(Intent(this, TodaySalesActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnEmployees).setOnClickListener {
            startActivity(Intent(this, EmployeesActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnCustomers).setOnClickListener {
            startActivity(Intent(this, CustomersActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnOrders).setOnClickListener {
            val intent = Intent(this, OrdersActivity::class.java)
            intent.putExtra("employeeName", employeeName)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btnSetup).setOnClickListener {
            val intent = Intent(this, SideMenuActivity::class.java)
            intent.putExtra("employeeName", employeeName)
            intent.putExtra("employeeRole", employeeRole)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.btnModifiers).setOnClickListener {
            startActivity(Intent(this, GlobalModifierActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnInventory).setOnClickListener {
            val intent = Intent(this, MenuOnlyActivity::class.java)
            intent.putExtra("batchId", currentBatchId)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.btnLogout).setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        loadCurrentSales()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentSales()
        applyOrderTypeVisibility()
    }

    private fun applyOrderTypeVisibility() {
        findViewById<android.view.View>(R.id.btnDineIn).visibility =
            if (OrderTypePrefs.isDineInEnabled(this)) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.btnToGo).visibility =
            if (OrderTypePrefs.isToGoEnabled(this)) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.btnBar).visibility =
            if (OrderTypePrefs.isBarTabEnabled(this)) android.view.View.VISIBLE else android.view.View.GONE
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

    private fun loadCurrentSales() {
        db.collection("Transactions")
            .whereEqualTo("settled", false)
            .get()
            .addOnSuccessListener { documents ->
                Executors.newSingleThreadExecutor().execute {
                    var total = 0.0
                    var count = 0

                    for (doc in documents) {
                        val voided = doc.getBoolean("voided") ?: false
                        if (voided) continue

                        val type = doc.getString("type") ?: "SALE"

                        if (type == "SALE" || type == "CAPTURE") {
                            val payments = doc.get("payments") as? List<*> ?: emptyList<Any>()
                            var totalCents = 0L

                            for (p in payments) {
                                val map = p as? Map<*, *> ?: continue
                                val amountInCents = (map["amountInCents"] as? Number)?.toLong() ?: 0L
                                totalCents += amountInCents
                            }

                            if (totalCents > 0L) {
                                total += totalCents / 100.0
                                count++
                            } else {
                                val amount = doc.getDouble("amount")
                                    ?: doc.getDouble("totalPaid")
                                    ?: 0.0
                                if (amount > 0.0) {
                                    total += amount
                                    count++
                                }
                            }
                        } else if (type == "REFUND") {
                            val amount = doc.getDouble("amount") ?: 0.0
                            if (amount > 0.0) {
                                total -= amount
                                count++
                            }
                        }
                    }

                    val finalTotal = total
                    val finalCount = count
                    runOnUiThread {
                        if (!isDestroyed) {
                            txtTodayTotal.text = String.format(Locale.US, "Current Sales: $%.2f", finalTotal)
                            txtTodayCount.text = "Transactions: $finalCount"
                        }
                    }
                }
            }
    }
}