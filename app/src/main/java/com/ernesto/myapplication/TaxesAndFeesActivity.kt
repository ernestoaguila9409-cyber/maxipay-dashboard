package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore

class TaxesAndFeesActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: TaxAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var txtNoTaxes: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_taxes_and_fees)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Taxes and Fees"

        recycler = findViewById(R.id.recyclerTaxes)
        txtNoTaxes = findViewById(R.id.txtNoTaxes)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = TaxAdapter(
            onItemClick = { item ->
                val intent = Intent(this, AddTaxActivity::class.java).apply {
                    putExtra("TAX_ID", item.id)
                    putExtra("TAX_TYPE", item.type)
                    putExtra("TAX_NAME", item.name)
                    putExtra("TAX_AMOUNT", item.amount)
                }
                startActivity(intent)
            },
            onToggleEnabled = { item, enabled -> setTaxEnabled(item, enabled) }
        )
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAddTaxOrFee).setOnClickListener {
            startActivity(Intent(this, AddTaxActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadTaxes()
    }

    private fun loadTaxes() {
        db.collection("Taxes")
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    val type = doc.getString("type") ?: return@mapNotNull null
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val amount = (doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble()) ?: return@mapNotNull null
                    val enabled = doc.getBoolean("enabled") ?: true
                    TaxItem(id = doc.id, type = type, name = name, amount = amount, enabled = enabled)
                }
                adapter.submitList(list)
                if (list.isEmpty()) {
                    recycler.visibility = View.GONE
                    txtNoTaxes.visibility = View.VISIBLE
                } else {
                    recycler.visibility = View.VISIBLE
                    txtNoTaxes.visibility = View.GONE
                }
            }
    }

    private fun setTaxEnabled(item: TaxItem, enabled: Boolean) {
        db.collection("Taxes").document(item.id)
            .update("enabled", enabled)
            .addOnSuccessListener { loadTaxes() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
