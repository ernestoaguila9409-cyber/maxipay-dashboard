package com.ernesto.myapplication

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.ernesto.myapplication.payments.SpinDefaults

/**
 * Add / edit a payment terminal. Reads and writes the same `payment_terminals`
 * collection and document shape as the maxipay web dashboard (Payments page).
 * Legacy [Terminals] is no longer used here — import legacy data from the
 * dashboard if needed.
 */
class PaymentTerminalActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var edtName: EditText
    private lateinit var edtTpn: EditText
    private lateinit var edtIpAddress: EditText
    private lateinit var edtRegisterId: EditText
    private lateinit var edtAuthKey: EditText
    private lateinit var btnDelete: MaterialButton

    private var terminalId: String? = null
    private var existingDeviceModel: String = ""
    private var existingActive: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_terminal)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        edtName = findViewById(R.id.edtTerminalName)
        edtTpn = findViewById(R.id.edtTpn)
        edtIpAddress = findViewById(R.id.edtIpAddress)
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
        db.collection("payment_terminals").document(id).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(this, "Terminal not found (sync with web dashboard: payment_terminals)", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                @Suppress("UNCHECKED_CAST")
                val config = (doc.get("config") as? Map<*, *>)?.mapValues { (_, v) -> v?.toString().orEmpty() }.orEmpty()
                edtName.setText(doc.getString("name") ?: "")
                edtTpn.setText(config["tpn"] ?: doc.getString("tpn") ?: "")
                edtRegisterId.setText(config["registerId"] ?: doc.getString("registerId") ?: "")
                edtAuthKey.setText(config["authKey"] ?: doc.getString("authKey") ?: "")
                edtIpAddress.setText(config["ipAddress"] ?: doc.getString("ipAddress") ?: "")
                existingDeviceModel = doc.getString("deviceModel")?.trim() ?: ""
                existingActive = doc.getBoolean("active") ?: true
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun saveTerminal() {
        val name = edtName.text.toString().trim()
        val tpn = edtTpn.text.toString().trim()
        val ipAddress = edtIpAddress.text.toString().trim()
        val registerId = edtRegisterId.text.toString().trim()
        val authKey = edtAuthKey.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "Terminal name is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (tpn.isBlank() || registerId.isBlank() || authKey.isBlank()) {
            Toast.makeText(this, "TPN, Register ID, and Auth Key are required", Toast.LENGTH_SHORT).show()
            return
        }

        val config = mutableMapOf(
            "tpn" to tpn,
            "registerId" to registerId,
            "authKey" to authKey,
        )
        if (ipAddress.isNotBlank()) config["ipAddress"] = ipAddress

        if (terminalId != null) {
            val id = terminalId!!
            val updates: MutableMap<String, Any> = hashMapOf(
                "name" to name,
                "config" to config,
                "deviceModel" to existingDeviceModel,
                "active" to existingActive,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            db.collection("payment_terminals").document(id).update(updates)
                .addOnSuccessListener {
                    TerminalPrefs.refreshCache()
                    Toast.makeText(this, "Terminal saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save: ${it.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            val newDoc: MutableMap<String, Any> = hashMapOf(
                "name" to name,
                "provider" to "SPIN_Z",
                "deviceModel" to "",
                "active" to true,
                "baseUrl" to SpinDefaults.BASE_URL,
                "endpoints" to SpinDefaults.ENDPOINTS,
                "capabilities" to SpinDefaults.CAPABILITIES,
                "config" to config,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            db.collection("payment_terminals")
                .add(newDoc)
                .addOnSuccessListener {
                    TerminalPrefs.refreshCache()
                    Toast.makeText(this, "Terminal saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Terminal")
            .setMessage("Are you sure you want to delete this terminal? This removes it for all devices and the web dashboard.")
            .setPositiveButton("Delete") { _, _ -> deleteTerminal() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTerminal() {
        val id = terminalId ?: return
        db.collection("payment_terminals").document(id).delete()
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
