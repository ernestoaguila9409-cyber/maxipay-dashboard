package com.ernesto.myapplication

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ernesto.myapplication.engine.OrderEngine
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date

class TableSelectionActivity : AppCompatActivity() {

    private data class OccupiedTableInfo(
        val orderId: String,
        val guestName: String?,
        val guestCount: Int,
        val itemsCount: Long,
        val createdAt: Date?
    )

    private val db = FirebaseFirestore.getInstance()
    private lateinit var canvas: FrameLayout
    private lateinit var chipGroup: ChipGroup
    private lateinit var orderEngine: OrderEngine

    private val tableViews = mutableMapOf<String, View>()
    private val tableSections = mutableMapOf<String, String>()
    private val tableNames = mutableMapOf<String, String>()
    private val tableSeats = mutableMapOf<String, Int>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = ""
    private val occupiedTableData = mutableMapOf<String, OccupiedTableInfo>()
    private var occupiedListener: ListenerRegistration? = null

    private val waitingHandler = Handler(Looper.getMainLooper())
    private val waitingRefreshRunnable = object : Runnable {
        override fun run() {
            applyOccupiedState()
            waitingHandler.postDelayed(this, WAITING_REFRESH_MS)
        }
    }

    private var batchId: String = ""
    private var employeeName: String = ""

    private var waitingThresholdMs = 5L * 60 * 1000

    companion object {
        private const val WAITING_REFRESH_MS = 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table_selection)
        supportActionBar?.hide()

        batchId = intent.getStringExtra("batchId") ?: ""
        employeeName = intent.getStringExtra("employeeName") ?: ""

        canvas = findViewById(R.id.tableCanvas)
        chipGroup = findViewById(R.id.chipGroupSections)
        orderEngine = OrderEngine(db)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedSection = if (checkedIds.isNotEmpty()) {
                group.findViewById<Chip>(checkedIds[0])?.text?.toString() ?: ""
            } else {
                knownSections.firstOrNull() ?: ""
            }
            filterTablesBySection()
        }

        waitingThresholdMs = OrderTypePrefs.getWaitingAlertMinutes(this).toLong() * 60 * 1000

        loadSectionsAndTables()
        listenForOccupiedTables()
        waitingHandler.postDelayed(waitingRefreshRunnable, WAITING_REFRESH_MS)
    }

    override fun onResume() {
        super.onResume()
        waitingThresholdMs = OrderTypePrefs.getWaitingAlertMinutes(this).toLong() * 60 * 1000
        applyOccupiedState()
    }

    override fun onDestroy() {
        super.onDestroy()
        occupiedListener?.remove()
        waitingHandler.removeCallbacks(waitingRefreshRunnable)
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
                    if (name.isNotBlank() && name != "Bar") knownSections.add(name)
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
                    val areaType = doc.getString("areaType") ?: "DINING_TABLE"
                    if (areaType == "BAR_SEAT") continue

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
                applyOccupiedState()

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
            val info = occupiedTableData[id]
            if (info != null) {
                navigateToExistingOrder(id, info.orderId)
            } else {
                showGuestCountDialog(id)
            }
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
        val guestNamesContainer = dialogView.findViewById<LinearLayout>(R.id.guestNamesContainer)

        txtTableInfo.text = "$name • $maxSeats seats"

        var guestCount = 1
        val nameInputs = mutableListOf<EditText>()

        fun updateNameInputs() {
            val currentCount = nameInputs.size
            when {
                guestCount > currentCount -> {
                    for (i in currentCount until guestCount) {
                        val editText = EditText(this).apply {
                            hint = "Guest ${i + 1} name"
                            setPadding(32, 24, 32, 24)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 12 }
                        }
                        guestNamesContainer.addView(editText)
                        nameInputs.add(editText)
                    }
                }
                guestCount < currentCount -> {
                    for (i in guestCount until currentCount) {
                        guestNamesContainer.removeView(nameInputs.last())
                        nameInputs.removeAt(nameInputs.lastIndex)
                    }
                }
            }
        }

        fun updateDisplay() {
            txtGuestCount.text = guestCount.toString()
            btnMinus.isEnabled = guestCount > 1
            btnMinus.alpha = if (guestCount > 1) 1f else 0.4f
            btnPlus.isEnabled = guestCount < maxSeats
            btnPlus.alpha = if (guestCount < maxSeats) 1f else 0.4f
            updateNameInputs()
        }

        updateDisplay()

        btnMinus.setOnClickListener {
            if (guestCount > 1) {
                guestCount--
                updateDisplay()
            }
        }

        btnPlus.setOnClickListener {
            if (guestCount < maxSeats) {
                guestCount++
                updateDisplay()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("How many guests?")
            .setView(dialogView)
            .setPositiveButton("Start Order") { _, _ ->
                val guestNames = nameInputs.map { it.text.toString().trim() }
                navigateToMenu(tableId, name, guestCount, guestNames)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun listenForOccupiedTables() {
        occupiedListener = db.collection("Orders")
            .whereEqualTo("status", "OPEN")
            .whereEqualTo("orderType", "DINE_IN")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                occupiedTableData.clear()
                for (doc in snap.documents) {
                    val tid = doc.getString("tableId")
                    if (!tid.isNullOrBlank()) {
                        @Suppress("UNCHECKED_CAST")
                        val gNames = doc.get("guestNames") as? List<String>
                        val firstGuest = gNames?.firstOrNull()?.takeIf { it.isNotBlank() }
                        val gCount = (doc.getLong("guestCount") ?: 0L).toInt()
                        val iCount = doc.getLong("itemsCount") ?: 0L
                        val created = doc.getTimestamp("createdAt")?.toDate()
                            ?: doc.getDate("createdAt")
                        occupiedTableData[tid] = OccupiedTableInfo(
                            orderId = doc.id,
                            guestName = firstGuest,
                            guestCount = gCount,
                            itemsCount = iCount,
                            createdAt = created
                        )
                    }
                }
                applyOccupiedState()
            }
    }

    private fun applyOccupiedState() {
        val now = System.currentTimeMillis()
        for ((id, view) in tableViews) {
            if (view is TableShapeView) {
                val info = occupiedTableData[id]
                view.isOccupied = info != null
                if (info != null) {
                    val parts = mutableListOf<String>()
                    if (!info.guestName.isNullOrBlank()) parts.add(info.guestName)
                    if (info.guestCount > 0) parts.add("${info.guestCount} guest${if (info.guestCount > 1) "s" else ""}")
                    view.guestInfo = parts.joinToString(" • ")

                    val elapsed = info.createdAt?.let { now - it.time } ?: 0L
                    view.isWaitingForOrder = info.itemsCount <= 0 && elapsed > waitingThresholdMs
                } else {
                    view.guestInfo = ""
                    view.isWaitingForOrder = false
                }
            }
        }
    }

    private fun navigateToExistingOrder(tableId: String, orderId: String) {
        val intent = Intent(this, MenuActivity::class.java)
        intent.putExtra("batchId", batchId)
        intent.putExtra("employeeName", employeeName)
        intent.putExtra("orderType", "DINE_IN")
        intent.putExtra("tableId", tableId)
        intent.putExtra("tableName", tableNames[tableId] ?: "Table")
        intent.putExtra("ORDER_ID", orderId)
        startActivity(intent)
        finish()
    }

    private fun navigateToMenu(tableId: String, tableName: String, guestCount: Int, guestNames: List<String>) {
        if (intent.getBooleanExtra("SELECT_TABLE_ONLY", false)) {
            val result = Intent().apply {
                putExtra("tableId", tableId)
                putExtra("tableName", tableName)
                putExtra("guestCount", guestCount)
                putStringArrayListExtra("guestNames", ArrayList(guestNames))
            }
            setResult(RESULT_OK, result)
            finish()
            return
        }

        orderEngine.ensureOrder(
            currentOrderId = null,
            employeeName = employeeName,
            orderType = "DINE_IN",
            tableId = tableId,
            tableName = tableName,
            guestCount = if (guestCount > 0) guestCount else null,
            guestNames = if (guestNames.isNotEmpty()) guestNames else null,
            onSuccess = { orderId ->
                val intent = Intent(this, MenuActivity::class.java)
                intent.putExtra("batchId", batchId)
                intent.putExtra("employeeName", employeeName)
                intent.putExtra("orderType", "DINE_IN")
                intent.putExtra("tableId", tableId)
                intent.putExtra("tableName", tableName)
                intent.putExtra("guestCount", guestCount)
                intent.putStringArrayListExtra("guestNames", ArrayList(guestNames))
                intent.putExtra("ORDER_ID", orderId)
                startActivity(intent)
                finish()
            },
            onFailure = { e ->
                Toast.makeText(this, "Failed to create order: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
