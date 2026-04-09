package com.ernesto.myapplication

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.ScrollView
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

    /** When true, table defs come from Firestore `tableLayouts/{id}/tables` (synced with web). */
    private var useTableLayouts: Boolean = false
    private var activeLayoutId: String = ""
    private var layoutCanvasW: Double = 1200.0
    private var layoutCanvasH: Double = 800.0
    private var layoutTablesListener: ListenerRegistration? = null

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
        layoutTablesListener?.remove()
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
                loadTablesPreferred()
            }
            .addOnFailureListener { loadTablesPreferred() }
    }

    private fun clearTableCanvas() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()
        tableSections.clear()
        tableNames.clear()
        tableSeats.clear()
    }

    /**
     * Prefer shared `tableLayouts` + `tables` subcollection (web dashboard).
     * Falls back to legacy top-level `Tables` if no layouts exist.
     */
    private fun loadTablesPreferred() {
        db.collection("tableLayouts").get()
            .addOnSuccessListener { layoutSnap ->
                if (layoutSnap.isEmpty) {
                    useTableLayouts = false
                    activeLayoutId = ""
                    layoutTablesListener?.remove()
                    layoutTablesListener = null
                    loadTablesLegacy()
                    return@addOnSuccessListener
                }
                useTableLayouts = true
                val layoutDoc = layoutSnap.documents.find { it.getBoolean("isDefault") == true }
                    ?: layoutSnap.documents.minByOrNull { it.getLong("sortOrder") ?: 0L }
                    ?: layoutSnap.documents.first()
                activeLayoutId = layoutDoc.id
                layoutCanvasW = layoutDoc.getDouble("canvasWidth") ?: 1200.0
                layoutCanvasH = layoutDoc.getDouble("canvasHeight") ?: 800.0

                layoutTablesListener?.remove()
                layoutTablesListener = db.collection("tableLayouts").document(activeLayoutId)
                    .collection("tables")
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        applyLayoutTablesSnapshot(snap)
                    }
            }
            .addOnFailureListener {
                useTableLayouts = false
                loadTablesLegacy()
            }
    }

    private fun applyLayoutTablesSnapshot(snap: com.google.firebase.firestore.QuerySnapshot) {
        clearTableCanvas()
        var sectionsAdded = false
        canvas.post {
            val cw = canvas.width.toFloat().coerceAtLeast(1f)
            val ch = canvas.height.toFloat().coerceAtLeast(1f)

            for (doc in snap.documents) {
                val isActive: Boolean = when {
                    doc.contains("isActive") -> doc.getBoolean("isActive") ?: true
                    doc.contains("active") -> doc.getBoolean("active") ?: true
                    else -> true
                }
                if (!isActive) continue
                val areaType = doc.getString("areaType") ?: "DINING_TABLE"
                if (areaType == "BAR_SEAT") continue

                val name = doc.getString("name") ?: "Table"
                val seats = (doc.getLong("capacity") ?: doc.getLong("seats"))?.toInt() ?: 4
                val shapeStr = doc.getString("shape")
                val shape = TableShapeView.shapeFromString(shapeStr)
                val xL = doc.getDouble("x") ?: doc.getDouble("posX") ?: 50.0
                val yL = doc.getDouble("y") ?: doc.getDouble("posY") ?: 50.0
                val section = doc.getString("section") ?: ""

                tableSections[doc.id] = section
                tableNames[doc.id] = name
                tableSeats[doc.id] = seats
                if (section.isNotBlank() && section !in knownSections) {
                    knownSections.add(section)
                    db.collection("Sections").document(section)
                        .set(hashMapOf("name" to section))
                    sectionsAdded = true
                }

                val posX = (xL * cw / layoutCanvasW).toFloat()
                val posY = (yL * ch / layoutCanvasH).toFloat()
                addTableToCanvas(doc.id, name, seats, shape, posX, posY)
            }

            if (sectionsAdded) rebuildSectionChips()
            filterTablesBySection()
            applyOccupiedState()

            if (snap.isEmpty) {
                Toast.makeText(
                    this,
                    "No tables in this layout. Add tables in Dashboard → Settings → Table Layout.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadTablesLegacy() {
        db.collection("Tables")
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { snap ->
                clearTableCanvas()

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

        tableView.setOnLongClickListener {
            val info = occupiedTableData[id]
            if (info != null) {
                showDeoccupyDialog(id, info)
            }
            true
        }

        tableViews[id] = tableView
    }

    private fun showGuestCountDialog(tableId: String) {
        val name = tableNames[tableId] ?: "Table"
        val maxSeats = tableSeats[tableId] ?: 4

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_guest_count, null)
        val guestDialogContentScroll = dialogView.findViewById<ScrollView>(R.id.guestDialogContentScroll)
        val btnGuestDialogCancel = dialogView.findViewById<TextView>(R.id.btnGuestDialogCancel)
        val btnGuestDialogStart = dialogView.findViewById<TextView>(R.id.btnGuestDialogStart)
        val txtTableInfo = dialogView.findViewById<TextView>(R.id.txtTableInfo)
        val txtGuestCount = dialogView.findViewById<TextView>(R.id.txtGuestCount)
        val btnMinus = dialogView.findViewById<Button>(R.id.btnMinus)
        val btnPlus = dialogView.findViewById<Button>(R.id.btnPlus)
        val guestNamesContainer = dialogView.findViewById<LinearLayout>(R.id.guestNamesContainer)
        val guestNameKeypadHost = dialogView.findViewById<LinearLayout>(R.id.guestNameKeypadHost)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        txtTableInfo.text = "$name • $maxSeats seats"

        var guestCount = 1
        val nameInputs = mutableListOf<EditText>()
        var activeNameEdit: EditText? = null

        val keypad = ReceiptEmailKeypadDialog.buildKeypadView(
            this,
            ReceiptEmailKeypadDialog.KeypadVariant.GUEST_NAME,
            keyMinHeightDp = 30f,
            keyMarginDp = 1.5f,
            keyTextSizeSp = 13.5f,
            keyTextSizeCompactSp = 11f,
            panelPaddingHorizontalDp = 5f,
            panelPaddingVerticalDp = 6f,
        ) { token ->
            activeNameEdit?.let { ReceiptEmailKeypadDialog.insertAtCaret(it, token) }
        }
        guestNameKeypadHost.addView(
            keypad,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        fun wireGuestNameField(editText: EditText) {
            editText.inputType = InputType.TYPE_NULL
            editText.showSoftInputOnFocus = false
            editText.isCursorVisible = true
            editText.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    activeNameEdit = v as EditText
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    scrollGuestDialogToShowField(guestDialogContentScroll, v as EditText)
                }
            }
            editText.setOnClickListener {
                activeNameEdit = editText
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
                scrollGuestDialogToShowField(guestDialogContentScroll, editText)
            }
        }

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
                        wireGuestNameField(editText)
                        guestNamesContainer.addView(editText)
                        nameInputs.add(editText)
                    }
                }
                guestCount < currentCount -> {
                    for (i in guestCount until currentCount) {
                        val removed = nameInputs.removeAt(nameInputs.lastIndex)
                        guestNamesContainer.removeView(removed)
                        if (activeNameEdit === removed) {
                            activeNameEdit = nameInputs.lastOrNull()
                            activeNameEdit?.requestFocus()
                        }
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

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun confirmStartOrder() {
            val guestNames = nameInputs.map { it.text.toString().trim() }
            imm.hideSoftInputFromWindow(dialogView.windowToken, 0)
            dialog.dismiss()
            navigateToMenu(tableId, name, guestCount, guestNames)
        }

        btnGuestDialogCancel.setOnClickListener {
            imm.hideSoftInputFromWindow(dialogView.windowToken, 0)
            dialog.dismiss()
        }
        btnGuestDialogStart.setOnClickListener { confirmStartOrder() }

        dialog.setOnShowListener {
            dialog.window?.let { win ->
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                val displayHeight = resources.displayMetrics.heightPixels
                win.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (displayHeight * 0.92).toInt()
                )
            }
            nameInputs.firstOrNull()?.let { first ->
                activeNameEdit = first
                first.post {
                    first.requestFocus()
                    imm.hideSoftInputFromWindow(first.windowToken, 0)
                }
            }
        }

        dialog.show()
    }

    private fun scrollGuestDialogToShowField(scroll: ScrollView, field: View) {
        scroll.post {
            var y = 0
            var v: View? = field
            while (v != null) {
                val p = v.parent
                if (p === scroll) break
                y += v.top
                v = p as? View
            }
            val slack = (resources.displayMetrics.density * 12f).toInt()
            val bottomVisible = scroll.scrollY + scroll.height - scroll.paddingBottom
            val fieldBottom = y + field.height
            if (fieldBottom > bottomVisible - slack) {
                scroll.smoothScrollTo(0, fieldBottom - scroll.height + scroll.paddingBottom + slack)
            } else {
                val topVisible = scroll.scrollY + scroll.paddingTop
                if (y < topVisible + slack) {
                    scroll.smoothScrollTo(0, (y - slack).coerceAtLeast(0))
                }
            }
        }
    }

    private fun showDeoccupyDialog(tableId: String, info: OccupiedTableInfo) {
        val tableName = tableNames[tableId] ?: "Table"

        AlertDialog.Builder(this)
            .setTitle("Free Up $tableName?")
            .setMessage("This will set the table to available and delete the open order associated with it.\n\nAre you sure?")
            .setPositiveButton("Yes, Free Table") { _, _ ->
                deleteOrderAndFreeTable(info.orderId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteOrderAndFreeTable(orderId: String) {
        val orderRef = db.collection("Orders").document(orderId)

        orderRef.collection("items").get()
            .addOnSuccessListener { itemsSnap ->
                val batch = db.batch()
                for (item in itemsSnap.documents) {
                    batch.delete(item.reference)
                }
                batch.delete(orderRef)

                batch.commit()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Table freed and order deleted", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load order items: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
        val sectionName = tableSections[tableId]?.takeIf { it.isNotBlank() } ?: ""
        val intent = Intent(this, MenuActivity::class.java)
        intent.putExtra("batchId", batchId)
        intent.putExtra("employeeName", employeeName)
        intent.putExtra("orderType", "DINE_IN")
        intent.putExtra("tableId", tableId)
        intent.putExtra("tableName", tableNames[tableId] ?: "Table")
        intent.putExtra("sectionId", sectionName)
        intent.putExtra("sectionName", sectionName)
        intent.putExtra("ORDER_ID", orderId)
        startActivity(intent)
        finish()
    }

    private fun navigateToMenu(tableId: String, tableName: String, guestCount: Int, guestNames: List<String>) {
        val sectionName = tableSections[tableId]?.takeIf { it.isNotBlank() } ?: ""
        val sectionId = sectionName

        if (intent.getBooleanExtra("SELECT_TABLE_ONLY", false)) {
            val result = Intent().apply {
                putExtra("tableId", tableId)
                putExtra("tableName", tableName)
                putExtra("sectionId", sectionId)
                putExtra("sectionName", sectionName)
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
            sectionId = sectionId.takeIf { it.isNotBlank() },
            sectionName = sectionName.takeIf { it.isNotBlank() },
            guestCount = if (guestCount > 0) guestCount else null,
            guestNames = if (guestNames.isNotEmpty()) guestNames else null,
            onSuccess = { orderId ->
                val intent = Intent(this, MenuActivity::class.java)
                intent.putExtra("batchId", batchId)
                intent.putExtra("employeeName", employeeName)
                intent.putExtra("orderType", "DINE_IN")
                intent.putExtra("tableId", tableId)
                intent.putExtra("tableName", tableName)
                intent.putExtra("sectionId", sectionId)
                intent.putExtra("sectionName", sectionName)
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
