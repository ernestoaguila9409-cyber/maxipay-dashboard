package com.volt.maximobile

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import kotlin.math.abs
class TableLayoutActivity : AppCompatActivity() {

    private fun rectsOverlap(
        ax: Float,
        ay: Float,
        aw: Int,
        ah: Int,
        bx: Float,
        by: Float,
        bw: Int,
        bh: Int,
    ): Boolean {
        if (aw <= 0 || ah <= 0 || bw <= 0 || bh <= 0) return false
        val ar = ax + aw
        val ab = ay + ah
        val br = bx + bw
        val bb = by + bh
        return ax < br && ar > bx && ay < bb && ab > by
    }

    private fun overlapsAnotherTable(selfId: String, self: View, x: Float, y: Float): Boolean {
        val w = self.width
        val h = self.height
        if (w <= 0 || h <= 0) return false
        for ((id, other) in tableViews) {
            if (id == selfId) continue
            if (other.visibility != View.VISIBLE) continue
            val ow = other.width
            val oh = other.height
            if (ow <= 0 || oh <= 0) continue
            if (rectsOverlap(x, y, w, h, other.x, other.y, ow, oh)) return true
        }
        return false
    }

    private val db = FirebaseFirestore.getInstance()
    private lateinit var canvasScroll: HorizontalScrollView
    private lateinit var canvas: FrameLayout
    private lateinit var chipGroup: ChipGroup
    /** Visible floor-plan area in pixels (used for coord mapping and canvas size). */
    private var editorViewportWidthPx = 1f
    private var editorViewportHeightPx = 1f
    /** Canvas size in pixels — matches viewport so layout fits on screen. */
    private var editorContentWidthPx = 1f
    private var editorContentHeightPx = 1f
    private val tableLayoutCoords = mutableMapOf<String, Pair<Double, Double>>()
    private val tableViews = mutableMapOf<String, View>()
    private val tableSections = mutableMapOf<String, String>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = ""
    private val dragThreshold = 15f

    private var useTableLayouts: Boolean = false
    private var activeLayoutId: String = ""
    private var layoutCanvasW: Double = 1200.0
    private var layoutCanvasH: Double = 800.0
    private var layoutTablesListener: ListenerRegistration? = null

    /**
     * Incremented on each layout snapshot apply. Pending [canvas.post] runnables from older
     * generations are skipped so we never stack duplicate tables (e.g. at 0,0) after rapid
     * Firestore updates while dragging.
     */
    private var layoutCanvasApplyGeneration = 0

    private var selectedTableId: String? = null
    /** While set, Firestore snapshot updates must not overwrite this table's on-screen position. */
    private var draggingTableId: String? = null
    /** Timestamp of the last position save; snapshot echoes within the cooldown are skipped. */
    private var lastSaveTimeMs = 0L
    private val saveCooldownMs = 1500L
    private var rotationControlsOverlay: View? = null
    private var txtRotationAngle: TextView? = null
    private var isRotateMode = false
    private val tableRotations = mutableMapOf<String, Float>()

    private val shapeLabels = arrayOf("Square", "Round", "Rectangle", "Booth")
    private val shapeValues = arrayOf(
        TableShapeView.Shape.SQUARE,
        TableShapeView.Shape.ROUND,
        TableShapeView.Shape.RECTANGLE,
        TableShapeView.Shape.BOOTH
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table_layout)
        supportActionBar?.hide()

        canvasScroll = findViewById(R.id.tableCanvasScroll)
        canvas = findViewById(R.id.tableCanvas)
        chipGroup = findViewById(R.id.chipGroupSections)
        canvas.isClickable = true
        canvas.setOnClickListener { exitRotateMode() }

        canvasScroll.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            val boundsChanged = l != ol || t != ot || r != or_ || b != ob
            if (boundsChanged && canvasScroll.width > 0 && canvasScroll.height > 0 && useTableLayouts) {
                updateEditorCanvasDimensions()
                if (draggingTableId == null) applyEditorPositionsToAllTables()
                updateRotationControlsPosition()
            }
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedSection = if (checkedIds.isNotEmpty()) {
                group.findViewById<Chip>(checkedIds[0])?.text?.toString() ?: ""
            } else {
                ""
            }
            filterTablesBySection()
        }

        rebuildSectionChips()

        findViewById<ExtendedFloatingActionButton>(R.id.fabAddTable).setOnClickListener {
            showAddTableDialog()
        }

        loadSectionsAndTables()
    }

    override fun onDestroy() {
        super.onDestroy()
        layoutTablesListener?.remove()
    }

    // ── SECTION CHIPS ──────────────────────────────────────

    private fun rebuildSectionChips() {
        chipGroup.removeAllViews()

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bgColors = intArrayOf(0xFF5D4037.toInt(), 0xFFE0E0E0.toInt())
        val txtColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF333333.toInt())

        val activeLabel = if (selectedSection in knownSections) selectedSection
                          else knownSections.firstOrNull() ?: ""
        if (selectedSection != activeLabel) selectedSection = activeLabel

        for (section in knownSections) {
            val chip = Chip(this).apply {
                text = section
                isCheckable = true
                isCheckedIconVisible = false
                isCloseIconVisible = true
                chipBackgroundColor = ColorStateList(states, bgColors)
                setTextColor(ColorStateList(states, txtColors))
                closeIconTint = ColorStateList(states, txtColors)
                isChecked = section == activeLabel
            }
            chip.setOnCloseIconClickListener { confirmDeleteSection(section) }
            chipGroup.addView(chip)
        }

        val addChip = Chip(this).apply {
            text = "+"
            isCheckable = false
            isCheckedIconVisible = false
            chipBackgroundColor = ColorStateList.valueOf(0xFFBDBDBD.toInt())
            setTextColor(ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        }
        addChip.setOnClickListener { showAddSectionDialog() }
        chipGroup.addView(addChip)
    }

    private fun ensureSection(section: String) {
        if (section.isNotBlank() && section !in knownSections) {
            knownSections.add(section)
            MerchantFirestore.col("Sections").document(section).set(hashMapOf("name" to section))
            rebuildSectionChips()
        }
    }

    private fun filterTablesBySection() {
        for ((id, view) in tableViews) {
            val section = tableSections[id] ?: ""
            val match = selectedSection.isEmpty() ||
                section.equals(selectedSection, ignoreCase = true)
            view.visibility = if (match) View.VISIBLE else View.GONE
        }
    }

    // ── ADD / DELETE SECTION ────────────────────────────────

    private fun showAddSectionDialog() {
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val editText = EditText(this).apply {
            hint = "e.g. Terrace, Rooftop..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val container = FrameLayout(this)
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("Add Section")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Section name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.equals(SECTION_ALL, ignoreCase = true)) {
                    Toast.makeText(this, "\"$SECTION_ALL\" is reserved", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name in knownSections) {
                    Toast.makeText(this, "\"$name\" already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                MerchantFirestore.col("Sections").document(name)
                    .set(hashMapOf("name" to name))
                    .addOnSuccessListener {
                        knownSections.add(name)
                        rebuildSectionChips()
                        Toast.makeText(this, "\"$name\" added", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSection(section: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Section")
            .setMessage("Delete \"$section\"?\nTables in this section will still appear under \"All\".")
            .setPositiveButton("Delete") { _, _ ->
                MerchantFirestore.col("Sections").document(section).delete()
                    .addOnSuccessListener {
                        knownSections.remove(section)
                        if (selectedSection == section) selectedSection = knownSections.firstOrNull() ?: ""
                        rebuildSectionChips()
                        filterTablesBySection()
                        Toast.makeText(this, "\"$section\" removed", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── LOAD ───────────────────────────────────────────────

    private fun loadSectionsAndTables() {
        MerchantFirestore.col("Sections").get()
            .addOnSuccessListener { snap ->
                knownSections.clear()
                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: doc.id
                    if (name.isNotBlank() && name != SECTION_ALL) knownSections.add(name)
                }
                rebuildSectionChips()
                loadTablesPreferred()
            }
            .addOnFailureListener {
                loadTablesPreferred()
            }
    }

    private fun clearEditorCanvas() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()
        tableSections.clear()
        tableRotations.clear()
        tableLayoutCoords.clear()
        exitRotateMode()
    }

    private data class EditorTableRow(
        val id: String,
        val name: String,
        val seats: Int,
        val shape: TableShapeView.Shape,
        val posX: Float,
        val posY: Float,
        val section: String,
        val rotation: Float,
    )

    /** Active dining tables from a layout snapshot, with pixel positions for the current canvas size. */
    private fun parseEditorTableRowsFromSnapshot(snap: QuerySnapshot, cw: Float, ch: Float): List<EditorTableRow> {
        val out = ArrayList<EditorTableRow>()
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
            val seats = (doc.getLong("capacity") ?: doc.getLong("seats"))?.toInt() ?: 0
            val shape = TableShapeView.shapeFromString(doc.getString("shape"))
            val xL = doc.getDouble("x") ?: doc.getDouble("posX") ?: 50.0
            val yL = doc.getDouble("y") ?: doc.getDouble("posY") ?: 50.0
            val section = doc.getString("section") ?: ""
            val rotation = wrapRotationDeg((doc.getDouble("rotation") ?: 0.0).toFloat())
            tableLayoutCoords[doc.id] = Pair(xL, yL)
            val (posX, posY) = layoutCoordsToScreen(xL, yL)
            out.add(EditorTableRow(doc.id, name, seats, shape, posX, posY, section, rotation))
        }
        return out
    }

    private fun loadTablesPreferred() {
        MerchantFirestore.col("tableLayouts").get()
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
                layoutTablesListener = MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                    .collection("tables")
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        applyLayoutTablesSnapshotForEditor(snap)
                    }
            }
            .addOnFailureListener { loadTablesLegacy() }
    }

    private fun applyLayoutTablesSnapshotForEditor(snap: QuerySnapshot) {
        if (draggingTableId != null) return
        if (System.currentTimeMillis() - lastSaveTimeMs < saveCooldownMs) return
        val gen = ++layoutCanvasApplyGeneration
        canvas.post {
            if (gen != layoutCanvasApplyGeneration) return@post
            if (draggingTableId != null) return@post
            var sectionsAdded = false
            updateEditorCanvasDimensions()
            val rows = parseEditorTableRowsFromSnapshot(snap, editorContentWidthPx, editorContentHeightPx)
            val newIds = rows.map { it.id }.toSet()
            val currentIds = tableViews.keys.toSet()

            for (row in rows) {
                if (row.section.isNotBlank() && row.section !in knownSections) {
                    knownSections.add(row.section)
                    MerchantFirestore.col("Sections").document(row.section)
                        .set(hashMapOf("name" to row.section))
                    sectionsAdded = true
                }
            }

            // Same tables as already on canvas: update in place (no remove/add). Avoids a one-frame
            // duplicate at (0,0) and avoids elevation shadow sticking at the origin while dragging.
            if (newIds == currentIds && currentIds.isNotEmpty()) {
                for (row in rows) {
                    tableSections[row.id] = row.section
                    val tv = tableViews[row.id] as? TableShapeView ?: continue
                    tv.tableName = row.name
                    tv.seatCount = row.seats
                    if (tv.shape != row.shape) {
                        tv.shape = row.shape
                        val ew = TableShapeView.editorMeasuredWidthPx(this@TableLayoutActivity, row.shape)
                        val eh = TableShapeView.editorMeasuredHeightPx(this@TableLayoutActivity, row.shape)
                        tv.forcedSizePx = Pair(ew, eh)
                    }
                    applyTableRotation(tv, row.id, row.rotation)
                    if (row.id == draggingTableId) continue
                    tv.x = row.posX
                    tv.y = row.posY
                }
                if (sectionsAdded) rebuildSectionChips()
                filterTablesBySection()
                updateRotationControlsPosition()
                return@post
            }

            clearEditorCanvas()
            for (row in rows) {
                tableSections[row.id] = row.section
                addTableToCanvas(row.id, row.name, row.seats, row.shape, row.posX, row.posY, row.rotation)
            }
            if (sectionsAdded) rebuildSectionChips()
            filterTablesBySection()
        }
    }

    private fun loadTablesLegacy() {
        MerchantFirestore.col("Tables")
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { snap ->
                clearEditorCanvas()

                var sectionsAdded = false

                for (doc in snap.documents) {
                    val areaType = doc.getString("areaType") ?: "DINING_TABLE"
                    if (areaType == "BAR_SEAT") continue

                    val name = doc.getString("name") ?: "Table"
                    val seats = doc.getLong("seats")?.toInt() ?: 0
                    val shapeStr = doc.getString("shape")
                    val shape = TableShapeView.shapeFromString(shapeStr)
                    val posX = doc.getDouble("posX")?.toFloat() ?: 50f
                    val posY = doc.getDouble("posY")?.toFloat() ?: 50f
                    val section = doc.getString("section") ?: ""

                    tableSections[doc.id] = section
                    if (section.isNotBlank() && section !in knownSections) {
                        knownSections.add(section)
                        MerchantFirestore.col("Sections").document(section)
                            .set(hashMapOf("name" to section))
                        sectionsAdded = true
                    }
                    val rotation = wrapRotationDeg((doc.getDouble("rotation") ?: 0.0).toFloat())
                    addTableToCanvas(doc.id, name, seats, shape, posX, posY, rotation)
                }

                if (sectionsAdded) rebuildSectionChips()
                filterTablesBySection()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load tables: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── ADD TO CANVAS ──────────────────────────────────────

    private fun addTableToCanvas(
        id: String, name: String, seats: Int,
        tableShape: TableShapeView.Shape, posX: Float, posY: Float,
        rotation: Float = 0f,
    ) {
        tableViews.remove(id)?.let { canvas.removeView(it) }

        val w = TableShapeView.editorMeasuredWidthPx(this, tableShape)
        val h = TableShapeView.editorMeasuredHeightPx(this, tableShape)
        val tableView = TableShapeView(this).apply {
            tableName = name
            seatCount = seats
            shape = tableShape
            forcedSizePx = Pair(w, h)
        }
        val params = FrameLayout.LayoutParams(w, h)
        tableView.layoutParams = params
        canvas.addView(tableView)
        applyTableRotation(tableView, id, rotation)
        tableView.post {
            tableView.translationX = 0f
            tableView.translationY = 0f
            tableView.x = posX
            tableView.y = posY
        }

        setupDragAndLongPress(tableView, id)
        tableViews[id] = tableView
        if (id == selectedTableId && isRotateMode) {
            (tableView as? TableShapeView)?.isEditorSelected = true
            updateRotationControlsPosition()
        }
    }

    // ── DRAG + LONG PRESS ──────────────────────────────────

    private fun setupDragAndLongPress(view: View, tableId: String) {
        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false

        view.isLongClickable = true
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (!isDragging) showEditDeleteDialog(tableId)
                }
            },
        )

        view.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    draggingTableId = tableId
                    v.elevation = 0f
                    v.scaleX = 1.05f
                    v.scaleY = 1.05f
                    v.alpha = 0.85f
                    canvasScroll.requestDisallowInterceptTouchEvent(true)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = abs(event.rawX - startRawX)
                    val movedY = abs(event.rawY - startRawY)

                    if (!isDragging && (movedX > dragThreshold || movedY > dragThreshold)) {
                        isDragging = true
                        v.cancelLongPress()
                    }

                    if (isDragging) {
                        val maxX = (editorContentWidthPx - v.width).coerceAtLeast(0f)
                        val maxY = (editorContentHeightPx - v.height).coerceAtLeast(0f)
                        val nx = (event.rawX + dX).coerceIn(0f, maxX)
                        val ny = (event.rawY + dY).coerceIn(0f, maxY)
                        if (!overlapsAnotherTable(tableId, v, nx, ny)) {
                            v.x = nx
                            v.y = ny
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.elevation = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.alpha = 1f
                    canvasScroll.requestDisallowInterceptTouchEvent(false)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (isDragging) {
                        saveTablePosition(tableId, v.x, v.y)
                        updateRotationControlsPosition()
                    }
                    isDragging = false
                    draggingTableId = null
                    true
                }
                else -> false
            }
        }
    }

    private fun saveTablePosition(tableId: String, x: Float, y: Float) {
        lastSaveTimeMs = System.currentTimeMillis()
        if (useTableLayouts && activeLayoutId.isNotBlank()) {
            val (xL, yL) = TableLayoutMobileScale.screenToLayout(
                x, y,
                editorViewportWidthPx.coerceAtLeast(1f),
                editorViewportHeightPx.coerceAtLeast(1f),
                layoutCanvasW, layoutCanvasH,
            )
            tableLayoutCoords[tableId] = Pair(xL, yL)
            MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                .collection("tables").document(tableId)
                .update(
                    mapOf(
                        "x" to xL,
                        "y" to yL,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
        } else {
            MerchantFirestore.col("Tables").document(tableId)
                .update("posX", x.toDouble(), "posY", y.toDouble())
        }
    }

    // ── GRID PLACEMENT ─────────────────────────────────────

    private fun nextAvailablePosition(shape: TableShapeView.Shape): Pair<Float, Float> {
        val dp = resources.displayMetrics.density
        val ew = TableShapeView.editorMeasuredWidthPx(this, shape)
        val eh = TableShapeView.editorMeasuredHeightPx(this, shape)
        val cellW = (ew + 14f * dp).coerceAtLeast(48f * dp)
        val cellH = (eh + 14f * dp).coerceAtLeast(44f * dp)
        val padding = 16f * dp

        val canvasWidth = if (editorContentWidthPx > 1f) editorContentWidthPx
            else if (canvasScroll.width > 0) canvasScroll.width.toFloat()
            else resources.displayMetrics.widthPixels.toFloat()
        val columns = ((canvasWidth - padding) / cellW).toInt().coerceAtLeast(1)

        val visibleViews = tableViews.filter { (id, _) ->
            selectedSection.isEmpty() ||
                (tableSections[id] ?: "").equals(selectedSection, ignoreCase = true)
        }.values

        for (index in 0 until columns * 100) {
            val col = index % columns
            val row = index / columns
            val x = padding + col * cellW
            val y = padding + row * cellH

            val overlaps = visibleViews.any { v ->
                rectsOverlap(x, y, ew, eh, v.x, v.y, v.width, v.height)
            }
            if (!overlaps) return Pair(x, y)
        }

        return Pair(padding, padding)
    }

    // ── DIALOG HELPERS ─────────────────────────────────────

    private fun setupSectionSpinner(spinner: Spinner, preselect: String) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, knownSections.toList())
        val idx = knownSections.indexOf(preselect)
        if (idx >= 0) spinner.setSelection(idx)
    }

    // ── ADD TABLE DIALOG ───────────────────────────────────

    private fun showAddTableDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_table, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtTableName)
        val edtSeats = dialogView.findViewById<EditText>(R.id.edtTableSeats)
        val spinnerShape = dialogView.findViewById<Spinner>(R.id.spinnerShape)
        val spinnerSection = dialogView.findViewById<Spinner>(R.id.spinnerSection)

        spinnerShape.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapeLabels)

        if (knownSections.isNotEmpty()) {
            val defaultSection = if (selectedSection.isNotEmpty()) selectedSection
                                 else knownSections.first()
            setupSectionSpinner(spinnerSection, defaultSection)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Table")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = edtName.text.toString().trim()
                val seats = edtSeats.text.toString().trim().toIntOrNull() ?: 0
                val shape = shapeValues[spinnerShape.selectedItemPosition]

                if (name.isBlank()) {
                    Toast.makeText(this, "Table name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (knownSections.isEmpty() || spinnerSection.selectedItemPosition < 0) {
                    Toast.makeText(this, "Create a section first using the + button", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val section = knownSections[spinnerSection.selectedItemPosition]

                val (newX, newY) = nextAvailablePosition(shape)

                if (useTableLayouts && activeLayoutId.isNotBlank()) {
                    val cw = editorContentWidthPx.coerceAtLeast(1f)
                    val ch = editorContentHeightPx.coerceAtLeast(1f)
                    val (xL, yL) = TableLayoutMobileScale.screenToLayout(
                        newX, newY, cw, ch, layoutCanvasW, layoutCanvasH,
                    )
                    val data = hashMapOf(
                        "name" to name,
                        "capacity" to seats,
                        "seats" to seats,
                        "shape" to TableShapeView.shapeToString(shape),
                        "x" to xL,
                        "y" to yL,
                        "width" to 100.0,
                        "height" to 80.0,
                        "rotation" to 0.0,
                        "section" to section,
                        "areaType" to "DINING_TABLE",
                        "isActive" to true,
                        "active" to true,
                        "sortOrder" to tableViews.size,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                        .collection("tables").add(data)
                        .addOnSuccessListener { ref ->
                            tableSections[ref.id] = section
                            ensureSection(section)
                            Toast.makeText(this, "\"$name\" added", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                } else {
                    val data = hashMapOf(
                        "name" to name,
                        "seats" to seats,
                        "shape" to TableShapeView.shapeToString(shape),
                        "posX" to newX.toDouble(),
                        "posY" to newY.toDouble(),
                        "section" to section,
                        "areaType" to "DINING_TABLE",
                        "active" to true
                    )
                    MerchantFirestore.col("Tables").add(data)
                        .addOnSuccessListener { ref ->
                            tableSections[ref.id] = section
                            ensureSection(section)
                            Toast.makeText(this, "\"$name\" added", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── EDIT / DELETE ──────────────────────────────────────

    private fun showEditDeleteDialog(tableId: String) {
        val tableRef = if (useTableLayouts && activeLayoutId.isNotBlank()) {
            MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                .collection("tables").document(tableId)
        } else {
            MerchantFirestore.col("Tables").document(tableId)
        }
        tableRef.get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val currentName = doc.getString("name") ?: ""
                val currentSeats = (doc.getLong("capacity") ?: doc.getLong("seats"))?.toInt() ?: 0
                val currentShape = TableShapeView.shapeFromString(doc.getString("shape"))
                val currentSection = doc.getString("section") ?: ""
                val currentRotation = wrapRotationDeg((doc.getDouble("rotation") ?: 0.0).toFloat())

                AlertDialog.Builder(this)
                    .setTitle(currentName)
                    .setItems(arrayOf("Edit", "Rotate", "Delete")) { _, which ->
                        when (which) {
                            0 -> showEditTableDialog(
                                tableId, currentName, currentSeats, currentShape, currentSection, currentRotation,
                            )
                            1 -> enterRotateMode(tableId)
                            2 -> confirmDeleteTable(tableId, currentName)
                        }
                    }
                    .show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not load table: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showEditTableDialog(
        tableId: String, currentName: String,
        currentSeats: Int, currentShape: TableShapeView.Shape,
        currentSection: String,
        currentRotation: Float,
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_table, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtTableName)
        val edtSeats = dialogView.findViewById<EditText>(R.id.edtTableSeats)
        val spinnerShape = dialogView.findViewById<Spinner>(R.id.spinnerShape)
        val spinnerSection = dialogView.findViewById<Spinner>(R.id.spinnerSection)
        val seekRotation = dialogView.findViewById<SeekBar>(R.id.seekRotation)
        val txtRotationValue = dialogView.findViewById<TextView>(R.id.txtRotationValue)

        spinnerShape.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapeLabels)

        ensureSection(currentSection)
        setupSectionSpinner(spinnerSection, currentSection)

        edtName.setText(currentName)
        edtSeats.setText(currentSeats.toString())
        spinnerShape.setSelection(shapeValues.indexOf(currentShape).coerceAtLeast(0))
        bindRotationSeekBar(seekRotation, txtRotationValue, currentRotation)

        AlertDialog.Builder(this)
            .setTitle("Edit Table")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = edtName.text.toString().trim()
                val seats = edtSeats.text.toString().trim().toIntOrNull() ?: 0
                val shape = shapeValues[spinnerShape.selectedItemPosition]
                val rotation = wrapRotationDeg(seekRotation.progress.toFloat())

                if (name.isBlank()) {
                    Toast.makeText(this, "Table name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (knownSections.isEmpty() || spinnerSection.selectedItemPosition < 0) {
                    Toast.makeText(this, "Select a section", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val section = knownSections[spinnerSection.selectedItemPosition]

                val updates = hashMapOf<String, Any>(
                    "name" to name,
                    "capacity" to seats,
                    "seats" to seats,
                    "shape" to TableShapeView.shapeToString(shape),
                    "section" to section,
                    "areaType" to "DINING_TABLE",
                    "rotation" to rotation.toDouble(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val ref = if (useTableLayouts && activeLayoutId.isNotBlank()) {
                    MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                        .collection("tables").document(tableId)
                } else {
                    MerchantFirestore.col("Tables").document(tableId)
                }
                ref.update(updates)
                    .addOnSuccessListener {
                        val tv = tableViews[tableId] as? TableShapeView
                        if (tv != null) {
                            tv.tableName = name
                            tv.seatCount = seats
                            tv.shape = shape
                            applyTableRotation(tv, tableId, rotation)
                        }
                        tableSections[tableId] = section
                        ensureSection(section)
                        filterTablesBySection()
                        updateRotationControlsPosition()
                        Toast.makeText(this, "\"$name\" updated", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTable(tableId: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Table")
            .setMessage("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ ->
                val del = if (useTableLayouts && activeLayoutId.isNotBlank()) {
                    MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                        .collection("tables").document(tableId)
                        .update(
                            mapOf(
                                "isActive" to false,
                                "active" to false,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                } else {
                    MerchantFirestore.col("Tables").document(tableId).update("active", false)
                }
                del.addOnSuccessListener {
                    ++layoutCanvasApplyGeneration
                    tableViews[tableId]?.let { canvas.removeView(it) }
                    tableViews.remove(tableId)
                    tableSections.remove(tableId)
                    tableRotations.remove(tableId)
                    tableLayoutCoords.remove(tableId)
                    if (selectedTableId == tableId) exitRotateMode()
                    filterTablesBySection()
                    Toast.makeText(this, "\"$name\" deleted", Toast.LENGTH_SHORT).show()
                }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to delete: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Canvas matches the visible viewport so logical coords map to what you see on screen
     * (avoids spreading tables across a 1200px-wide canvas on a narrow P8).
     */
    private fun updateEditorCanvasDimensions() {
        val viewportW = canvasScroll.width.coerceAtLeast(1)
        val viewportH = canvasScroll.height.coerceAtLeast(1)

        editorViewportWidthPx = viewportW.toFloat()
        editorViewportHeightPx = viewportH.toFloat()
        editorContentWidthPx = editorViewportWidthPx
        editorContentHeightPx = editorViewportHeightPx

        val newW = editorContentWidthPx.toInt()
        val newH = editorContentHeightPx.toInt()
        val lp = canvas.layoutParams
        if (lp != null && lp.width == newW && lp.height == newH) return
        val params = lp ?: FrameLayout.LayoutParams(newW, newH)
        params.width = newW
        params.height = newH
        canvas.layoutParams = params
    }

    private fun applyEditorPositionsToAllTables() {
        if (!useTableLayouts) return
        for ((id, view) in tableViews) {
            val tv = view as? TableShapeView ?: continue
            val coords = tableLayoutCoords[id] ?: continue
            val (sx, sy) = layoutCoordsToScreen(coords.first, coords.second)
            tv.x = sx
            tv.y = sy
        }
    }

    private fun layoutCoordsToScreen(xL: Double, yL: Double): Pair<Float, Float> {
        return TableLayoutMobileScale.layoutToScreen(
            xL, yL,
            editorViewportWidthPx, editorViewportHeightPx,
            layoutCanvasW, layoutCanvasH,
        )
    }

    private fun wrapRotationDeg(deg: Float): Float {
        var r = deg % 360f
        if (r < 0f) r += 360f
        return r
    }

    private fun applyTableRotation(view: TableShapeView, tableId: String, rotation: Float) {
        val r = wrapRotationDeg(rotation)
        tableRotations[tableId] = r
        view.rotation = r
    }

    private fun bindRotationSeekBar(seek: SeekBar, label: TextView, rotation: Float) {
        val r = wrapRotationDeg(rotation).toInt()
        seek.progress = r
        label.text = "${r}°"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "${progress}°"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun enterRotateMode(tableId: String) {
        if (!tableViews.containsKey(tableId)) return
        selectedTableId = tableId
        isRotateMode = true
        for ((id, view) in tableViews) {
            (view as? TableShapeView)?.isEditorSelected = id == tableId
        }
        val tv = tableViews[tableId] as? TableShapeView
        val current = wrapRotationDeg(tv?.rotation ?: tableRotations[tableId] ?: 0f)
        ensureRotationControls()
        txtRotationAngle?.text = "${current.toInt()}°"
        rotationControlsOverlay?.visibility = View.VISIBLE
        updateRotationControlsPosition()
        rotationControlsOverlay?.bringToFront()
    }

    private fun exitRotateMode() {
        if (!isRotateMode && selectedTableId == null) return
        isRotateMode = false
        selectedTableId = null
        rotationControlsOverlay?.visibility = View.GONE
        for ((_, view) in tableViews) {
            (view as? TableShapeView)?.isEditorSelected = false
        }
    }

    private fun ensureRotationControls() {
        if (rotationControlsOverlay != null) return
        val dp = resources.displayMetrics.density
        val btnSize = (52f * dp).toInt()
        val pad = (10f * dp).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xF2FFFFFF.toInt())
                setStroke((1.5f * dp).toInt().coerceAtLeast(1), 0xFF1565C0.toInt())
            }
            elevation = 12f
            visibility = View.GONE
            isClickable = true
        }

        fun makeRotateButton(iconRes: Int, contentDesc: String, delta: Float): ImageButton {
            return ImageButton(this).apply {
                setImageResource(iconRes)
                contentDescription = contentDesc
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE3F2FD.toInt())
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(pad, pad, pad, pad)
                setOnClickListener { adjustTableRotation(delta) }
            }
        }

        val btnNegative = makeRotateButton(
            R.drawable.ic_rotate_ccw,
            "Rotate counter-clockwise",
            -ROTATION_STEP_DEG,
        )
        val angleLabel = TextView(this).apply {
            text = "0°"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1565C0.toInt())
            setPadding((14f * dp).toInt(), 0, (14f * dp).toInt(), 0)
        }
        val btnPositive = makeRotateButton(
            R.drawable.ic_rotate_cw,
            "Rotate clockwise",
            ROTATION_STEP_DEG,
        )

        row.addView(
            btnNegative,
            LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = pad / 2 },
        )
        row.addView(
            angleLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        row.addView(
            btnPositive,
            LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = pad / 2 },
        )

        canvas.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        rotationControlsOverlay = row
        txtRotationAngle = angleLabel
    }

    private fun adjustTableRotation(deltaDeg: Float) {
        val tableId = selectedTableId ?: return
        val tv = tableViews[tableId] as? TableShapeView ?: return
        val newRotation = wrapRotationDeg(tv.rotation + deltaDeg)
        applyTableRotation(tv, tableId, newRotation)
        txtRotationAngle?.text = "${newRotation.toInt()}°"
        saveTableRotation(tableId, newRotation)
        updateRotationControlsPosition()
    }

    private fun updateRotationControlsPosition() {
        val overlay = rotationControlsOverlay ?: return
        if (!isRotateMode) {
            overlay.visibility = View.GONE
            return
        }
        val tableId = selectedTableId ?: return
        val tv = tableViews[tableId] ?: return
        if (tv.visibility != View.VISIBLE) {
            overlay.visibility = View.GONE
            return
        }
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(canvas.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(canvas.height, View.MeasureSpec.AT_MOST),
        )
        val dp = resources.displayMetrics.density
        val gap = 12f * dp
        val cx = tv.x + tv.width / 2f
        overlay.x = (cx - overlay.measuredWidth / 2f).coerceIn(0f, (canvas.width - overlay.measuredWidth).toFloat())
        overlay.y = (tv.y + tv.height + gap).coerceIn(0f, (canvas.height - overlay.measuredHeight).toFloat())
        overlay.bringToFront()
    }

    private fun saveTableRotation(tableId: String, rotation: Float) {
        val r = wrapRotationDeg(rotation)
        tableRotations[tableId] = r
        if (useTableLayouts && activeLayoutId.isNotBlank()) {
            MerchantFirestore.col("tableLayouts").document(activeLayoutId)
                .collection("tables").document(tableId)
                .update(
                    mapOf(
                        "rotation" to r.toDouble(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
        } else {
            MerchantFirestore.col("Tables").document(tableId)
                .update(
                    mapOf(
                        "rotation" to r.toDouble(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
        }
    }

    companion object {
        const val SECTION_ALL = "All"
        private const val ROTATION_STEP_DEG = 15f
    }
}
