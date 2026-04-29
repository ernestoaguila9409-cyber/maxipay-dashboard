package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale

class TodaySalesActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var txtTodayTotal: TextView
    private lateinit var txtTodayCount: TextView
    private var currentSalesListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_today_sales)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Current Sales"

        txtTodayTotal = findViewById(R.id.txtTodayTotal)
        txtTodayCount = findViewById(R.id.txtTodayCount)

        findViewById<Button>(R.id.btnViewTransactions).setOnClickListener {
            val intent = Intent(this, TransactionActivity::class.java)
            intent.putExtra("employeeName", SessionEmployee.getEmployeeName(this))
            intent.putExtra("CURRENT_TRANSACTION", true)
            intent.putExtra("SHOW_UNSETTLED_AND_TODAY_REFUNDS", true)
            startActivity(intent)
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

        attachCurrentSalesListener()
    }

    override fun onDestroy() {
        currentSalesListener?.remove()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        attachCurrentSalesListener()
    }

    private fun attachCurrentSalesListener() {
        currentSalesListener?.remove()
        currentSalesListener = db.collection("Transactions")
            .whereEqualTo("settled", false)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("TodaySalesActivity", "Current sales query failed", error)
                    return@addSnapshotListener
                }
                if (snapshots == null || isDestroyed) return@addSnapshotListener
                val (finalTotal, finalCount) = UnsettledSalesSummary.compute(snapshots)
                txtTodayTotal.text = String.format(Locale.US, "Current Sales: $%.2f", finalTotal)
                txtTodayCount.text = "Transactions: $finalCount"
            }
    }
}
