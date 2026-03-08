package com.ernesto.myapplication

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore

class PaymentTerminalActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var edtName: EditText
    private lateinit var edtTpn: EditText
    private lateinit var edtRegisterId: EditText
    private lateinit var edtAuthKey: EditText
    private lateinit var btnDelete: MaterialButton

    private var terminalId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_terminal)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        edtName = findViewById(R.id.edtTerminalName)
        edtTpn = findViewById(R.id.edtTpn)
        edtRegisterId = findViewById(R.id.edtRegisterId)
        edtAuthKey = findViewById(R.id.edtAuthKey)
        btnDelete = findViewById(R.id.btnDeleteTerminal)

        terminalId = intent.getStringExtra("TERMINAL_ID")

        if (terminalId != null) {
            supportActionBar?.title = "Edit Terminal"
            findViewById<TextView>(R.id.txtScreenTitle).text = "Edit Terminal"
            btnDelete.visibility = android.view.View.VISIBLE
            loadTerminal(terminalId!!)
        } else {
            supportActionBar?.title = "Add Terminal"
        }

        findViewById<MaterialButton>(R.id.btnSaveTerminal).setOnClickListener { saveTerminal() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun loadTerminal(id: String) {
        db.collection("Terminals").document(id).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                edtName.setText(doc.getString("name") ?: "")
                edtTpn.setText(doc.getString("tpn") ?: "")
                edtRegisterId.setText(doc.getString("registerId") ?: "")
                edtAuthKey.setText(doc.getString("authKey") ?: "")
            }
    }

    private fun saveTerminal() {
        val name = edtName.text.toString().trim()
        val tpn = edtTpn.text.toString().trim()
        val registerId = edtRegisterId.text.toString().trim()
        val authKey = edtAuthKey.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "Terminal name is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (tpn.isBlank() || authKey.isBlank()) {
            Toast.makeText(this, "TPN and Auth Key are required", Toast.LENGTH_SHORT).show()
            return
        }

        val data = mapOf(
            "name" to name,
            "tpn" to tpn,
            "registerId" to registerId,
            "authKey" to authKey
        )

        val docRef = if (terminalId != null) {
            db.collection("Terminals").document(terminalId!!)
        } else {
            db.collection("Terminals").document()
        }

        docRef.set(data)
            .addOnSuccessListener {
                TerminalPrefs.refreshCache()
                Toast.makeText(this, "Terminal saved", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Terminal")
            .setMessage("Are you sure you want to delete this terminal?")
            .setPositiveButton("Delete") { _, _ -> deleteTerminal() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTerminal() {
        val id = terminalId ?: return
        db.collection("Terminals").document(id).delete()
            .addOnSuccessListener {
                TerminalPrefs.refreshCache()
                Toast.makeText(this, "Terminal deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to delete: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
