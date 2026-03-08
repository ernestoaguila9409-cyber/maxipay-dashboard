package com.ernesto.myapplication

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.FirebaseFirestore

class TableSelectionActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var canvas: FrameLayout
    private lateinit var chipGroup: ChipGroup

    private val tableViews = mutableMapOf<String, View>()
    private val tableSections = mutableMapOf<String, String>()
    private val tableNames = mutableMapOf<String, String>()
    private val tableSeats = mutableMapOf<String, Int>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = ""

    private var batchId: String = ""
    private var employeeName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table_selection)
        supportActionBar?.hide()

        batchId = intent.getStringExtra("batchId") ?: ""
        employeeName = intent.getStringExtra("employeeName") ?: ""

        canvas = findViewById(R.id.tableCanvas)
        chipGroup = findViewById(R.id.chipGroupSections)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedSection = if (checkedIds.isNotEmpty()) {
                group.findViewById<Chip>(checkedIds[0])?.text?.toString() ?: ""
            } else {
                knownSections.firstOrNull() ?: ""
            }
            filterTablesBySection()
        }

        loadSectionsAndTables()
    }

    private fun rebuildSectionChips() {
        chipGroup.removeAllViews()

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bgColors = intArrayOf(0xFF6A4FB3.toInt(), 0xFFE0E0E0.toInt())
        val txtColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF333333.toInt())

        if (selectedSection.isEmpty() && knownSections.isNotEmpty()) {
            selectedSection = knownSections.first()
        }

        for (section in knownSections) {
            val chip = Chip(this).apply {
                text = section
                isCheckable = true
                isCheckedIconVisible = false
                chipBackgroundColor = ColorStateList(states, bgColors)
                setTextColor(ColorStateList(states, txtColors))
                isChecked = section == selectedSection
            }
            chipGroup.addView(chip)
        }
    }

    private fun filterTablesBySection() {
        for ((id, view) in tableViews) {
            val section = tableSections[id] ?: ""
            view.visibility = if (selectedSection.isEmpty() || section == selectedSection)
                View.VISIBLE else View.GONE
        }
    }

    private fun loadSectionsAndTables() {
        db.collection("Sections").get()
            .addOnSuccessListener { snap ->
                knownSections.clear()
                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: doc.id
                    if (name.isNotBlank()) knownSections.add(name)
                }
                rebuildSectionChips()
                loadTables()
            }
            .addOnFailureListener { loadTables() }
    }

    private fun loadTables() {
        db.collection("Tables")
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { snap ->
                tableViews.values.forEach { canvas.removeView(it) }
                tableViews.clear()
                tableSections.clear()
                tableNames.clear()
                tableSeats.clear()

                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: "Table"
                    val seats = doc.getLong("seats")?.toInt() ?: 4
                    val shapeStr = doc.getString("shape")
                    val shape = TableShapeView.shapeFromString(shapeStr)
                    val posX = doc.getDouble("posX")?.toFloat() ?: 50f
                    val posY = doc.getDouble("posY")?.toFloat() ?: 50f
                    val section = doc.getString("section") ?: ""

                    tableSections[doc.id] = section
                    tableNames[doc.id] = name
                    tableSeats[doc.id] = seats

                    addTableToCanvas(doc.id, name, seats, shape, posX, posY)
                }

                filterTablesBySection()

                if (snap.isEmpty) {
                    Toast.makeText(this, "No tables configured. Set them up in Configuration → Table Layout.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load tables: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun addTableToCanvas(
        id: String, name: String, seats: Int,
        shape: TableShapeView.Shape, posX: Float, posY: Float
    ) {
        val tableView = TableShapeView(this).apply {
            tableName = name
            seatCount = seats
            this.shape = shape
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tableView.layoutParams = params
        canvas.addView(tableView)

        tableView.post {
            tableView.x = posX
            tableView.y = posY
        }

        tableView.setOnClickListener {
            showGuestCountDialog(id)
        }

        tableViews[id] = tableView
    }

    private fun showGuestCountDialog(tableId: String) {
        val name = tableNames[tableId] ?: "Table"
        val maxSeats = tableSeats[tableId] ?: 4

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_guest_count, null)
        val txtTableInfo = dialogView.findViewById<TextView>(R.id.txtTableInfo)
        val txtGuestCount = dialogView.findViewById<TextView>(R.id.txtGuestCount)
        val btnMinus = dialogView.findViewById<Button>(R.id.btnMinus)
        val btnPlus = dialogView.findViewById<Button>(R.id.btnPlus)

        txtTableInfo.text = "$name • $maxSeats seats"

        var guestCount = 1

        fun updateDisplay() {
            txtGuestCount.text = guestCount.toString()
            btnMinus.isEnabled = guestCount > 1
            btnMinus.alpha = if (guestCount > 1) 1f else 0.4f
        }

        updateDisplay()

        btnMinus.setOnClickListener {
            if (guestCount > 1) {
                guestCount--
                updateDisplay()
            }
        }

        btnPlus.setOnClickListener {
            guestCount++
            updateDisplay()
        }

        AlertDialog.Builder(this)
            .setTitle("How many guests?")
            .setView(dialogView)
            .setPositiveButton("Start Order") { _, _ ->
                navigateToMenu(tableId, name, guestCount)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToMenu(tableId: String, tableName: String, guestCount: Int) {
        val intent = Intent(this, MenuActivity::class.java)
        intent.putExtra("batchId", batchId)
        intent.putExtra("employeeName", employeeName)
        intent.putExtra("orderType", "DINE_IN")
        intent.putExtra("tableId", tableId)
        intent.putExtra("tableName", tableName)
        intent.putExtra("guestCount", guestCount)
        startActivity(intent)
        finish()
    }
}
