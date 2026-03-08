package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore

class TerminalListActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val terminals = mutableListOf<Terminal>()
    private lateinit var adapter: TerminalAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Payment Terminals"

        recycler = findViewById(R.id.recyclerTerminals)
        txtEmpty = findViewById(R.id.txtEmptyTerminals)

        adapter = TerminalAdapter(terminals) { terminal ->
            val intent = Intent(this, PaymentTerminalActivity::class.java)
            intent.putExtra("TERMINAL_ID", terminal.id)
            startActivity(intent)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnAddTerminal).setOnClickListener {
            startActivity(Intent(this, PaymentTerminalActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadTerminals()
    }

    private fun loadTerminals() {
        db.collection("Terminals")
            .orderBy("name")
            .get()
            .addOnSuccessListener { snap ->
                terminals.clear()
                for (doc in snap.documents) {
                    terminals.add(
                        Terminal(
                            id = doc.id,
                            name = doc.getString("name") ?: "",
                            tpn = doc.getString("tpn") ?: "",
                            registerId = doc.getString("registerId") ?: "",
                            authKey = doc.getString("authKey") ?: ""
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                if (terminals.isEmpty()) {
                    recycler.visibility = View.GONE
                    txtEmpty.visibility = View.VISIBLE
                } else {
                    recycler.visibility = View.VISIBLE
                    txtEmpty.visibility = View.GONE
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load terminals: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
