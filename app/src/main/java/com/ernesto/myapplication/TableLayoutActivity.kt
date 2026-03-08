package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.abs

class TableLayoutActivity : AppCompatActivity() {

    companion object {
        const val SECTION_ALL = "All"
    }

    private val db = FirebaseFirestore.getInstance()
    private lateinit var canvas: FrameLayout
    private lateinit var chipGroup: ChipGroup
    private val tableViews = mutableMapOf<String, View>()
    private val tableSections = mutableMapOf<String, String>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = SECTION_ALL
    private val dragThreshold = 15f

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

        canvas = findViewById(R.id.tableCanvas)
        chipGroup = findViewById(R.id.chipGroupSections)

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedSection = if (checkedIds.isNotEmpty()) {
                group.findViewById<Chip>(checkedIds[0])?.text?.toString() ?: SECTION_ALL
            } else {
                SECTION_ALL
            }
            filterTablesBySection()
        }

        rebuildSectionChips()

        findViewById<ExtendedFloatingActionButton>(R.id.fabAddTable).setOnClickListener {
            showAddTableDialog()
        }

        loadSectionsAndTables()
    }

    // ── SECTION CHIPS ──────────────────────────────────────

    private fun rebuildSectionChips() {
        chipGroup.removeAllViews()

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bgColors = intArrayOf(0xFF5D4037.toInt(), 0xFFE0E0E0.toInt())
        val txtColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF333333.toInt())

        val allLabels = listOf(SECTION_ALL) + knownSections
        val activeLabel = if (selectedSection in allLabels) selectedSection else SECTION_ALL
        if (selectedSection != activeLabel) selectedSection = activeLabel

        val allChip = Chip(this).apply {
            text = SECTION_ALL
            isCheckable = true
            isCheckedIconVisible = false
            chipBackgroundColor = ColorStateList(states, bgColors)
            setTextColor(ColorStateList(states, txtColors))
            isChecked = activeLabel == SECTION_ALL
        }
        chipGroup.addView(allChip)

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
            db.collection("Sections").document(section).set(hashMapOf("name" to section))
            rebuildSectionChips()
        }
    }

    private fun filterTablesBySection() {
        for ((id, view) in tableViews) {
            val section = tableSections[id] ?: ""
            view.visibility = if (selectedSection == SECTION_ALL || section == selectedSection)
                View.VISIBLE else View.GONE
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
                db.collection("Sections").document(name)
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
                db.collection("Sections").document(section).delete()
                    .addOnSuccessListener {
                        knownSections.remove(section)
                        if (selectedSection == section) selectedSection = SECTION_ALL
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
        db.collection("Sections").get()
            .addOnSuccessListener { snap ->
                knownSections.clear()
                for (doc in snap.documents) {
                    val name = doc.getString("name") ?: doc.id
                    if (name.isNotBlank() && name != SECTION_ALL) knownSections.add(name)
                }
                rebuildSectionChips()
                loadTables()
            }
            .addOnFailureListener {
                loadTables()
            }
    }

    private fun loadTables() {
        db.collection("Tables")
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { snap ->
                tableViews.values.forEach { canvas.removeView(it) }
                tableViews.clear()
                tableSections.clear()

                var sectionsAdded = false

                for (doc in snap.documents) {
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
                        db.collection("Sections").document(section)
                            .set(hashMapOf("name" to section))
                        sectionsAdded = true
                    }
                    addTableToCanvas(doc.id, name, seats, shape, posX, posY)
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

        setupDragAndLongPress(tableView, id)
        tableViews[id] = tableView
    }

    // ── DRAG + LONG PRESS ──────────────────────────────────

    private fun setupDragAndLongPress(view: View, tableId: String) {
        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false

        view.isLongClickable = true
        view.setOnLongClickListener {
            showEditDeleteDialog(tableId)
            true
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    v.elevation = 16f
                    v.scaleX = 1.05f
                    v.scaleY = 1.05f
                    v.alpha = 0.85f
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = abs(event.rawX - startRawX)
                    val movedY = abs(event.rawY - startRawY)

                    if (!isDragging && (movedX > dragThreshold || movedY > dragThreshold)) {
                        isDragging = true
                        v.cancelLongPress()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isDragging) {
                        v.x = (event.rawX + dX).coerceIn(0f, (canvas.width - v.width).toFloat())
                        v.y = (event.rawY + dY).coerceIn(0f, (canvas.height - v.height).toFloat())
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.elevation = 4f
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.alpha = 1f
                    if (isDragging) {
                        saveTablePosition(tableId, v.x, v.y)
                        isDragging = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun saveTablePosition(tableId: String, x: Float, y: Float) {
        db.collection("Tables").document(tableId)
            .update("posX", x.toDouble(), "posY", y.toDouble())
    }

    // ── GRID PLACEMENT ─────────────────────────────────────

    private fun nextAvailablePosition(): Pair<Float, Float> {
        val dp = resources.displayMetrics.density
        val cellW = 200f * dp
        val cellH = 160f * dp
        val padding = 24f * dp

        val canvasWidth = if (canvas.width > 0) canvas.width.toFloat()
                          else resources.displayMetrics.widthPixels.toFloat()
        val columns = ((canvasWidth - padding) / cellW).toInt().coerceAtLeast(1)

        val visibleViews = tableViews.filter { (id, _) ->
            selectedSection == SECTION_ALL || tableSections[id] == selectedSection
        }.values

        for (index in 0 until columns * 100) {
            val col = index % columns
            val row = index / columns
            val x = padding + col * cellW
            val y = padding + row * cellH

            val overlaps = visibleViews.any { v ->
                abs(v.x - x) < cellW * 0.6f && abs(v.y - y) < cellH * 0.6f
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
        if (knownSections.isEmpty()) {
            Toast.makeText(this, "Create a section first using the + button", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_table, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtTableName)
        val edtSeats = dialogView.findViewById<EditText>(R.id.edtTableSeats)
        val spinnerShape = dialogView.findViewById<Spinner>(R.id.spinnerShape)
        val spinnerSection = dialogView.findViewById<Spinner>(R.id.spinnerSection)

        spinnerShape.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapeLabels)

        val defaultSection = if (selectedSection != SECTION_ALL) selectedSection
                             else knownSections.first()
        setupSectionSpinner(spinnerSection, defaultSection)

        AlertDialog.Builder(this)
            .setTitle("Add Table")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = edtName.text.toString().trim()
                val seats = edtSeats.text.toString().trim().toIntOrNull() ?: 0
                val shape = shapeValues[spinnerShape.selectedItemPosition]
                val section = knownSections[spinnerSection.selectedItemPosition]

                if (name.isBlank()) {
                    Toast.makeText(this, "Table name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val (newX, newY) = nextAvailablePosition()

                val data = hashMapOf(
                    "name" to name,
                    "seats" to seats,
                    "shape" to TableShapeView.shapeToString(shape),
                    "posX" to newX.toDouble(),
                    "posY" to newY.toDouble(),
                    "section" to section,
                    "active" to true
                )

                db.collection("Tables").add(data)
                    .addOnSuccessListener { ref ->
                        tableSections[ref.id] = section
                        ensureSection(section)
                        addTableToCanvas(ref.id, name, seats, shape, newX, newY)
                        filterTablesBySection()
                        Toast.makeText(this, "\"$name\" added", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── EDIT / DELETE ──────────────────────────────────────

    private fun showEditDeleteDialog(tableId: String) {
        db.collection("Tables").document(tableId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val currentName = doc.getString("name") ?: ""
                val currentSeats = doc.getLong("seats")?.toInt() ?: 0
                val currentShape = TableShapeView.shapeFromString(doc.getString("shape"))
                val currentSection = doc.getString("section") ?: ""

                AlertDialog.Builder(this)
                    .setTitle(currentName)
                    .setItems(arrayOf("Edit", "Delete")) { _, which ->
                        when (which) {
                            0 -> showEditTableDialog(tableId, currentName, currentSeats, currentShape, currentSection)
                            1 -> confirmDeleteTable(tableId, currentName)
                        }
                    }
                    .show()
            }
    }

    private fun showEditTableDialog(
        tableId: String, currentName: String,
        currentSeats: Int, currentShape: TableShapeView.Shape,
        currentSection: String
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_table, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtTableName)
        val edtSeats = dialogView.findViewById<EditText>(R.id.edtTableSeats)
        val spinnerShape = dialogView.findViewById<Spinner>(R.id.spinnerShape)
        val spinnerSection = dialogView.findViewById<Spinner>(R.id.spinnerSection)

        spinnerShape.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapeLabels)
        ensureSection(currentSection)
        setupSectionSpinner(spinnerSection, currentSection)

        edtName.setText(currentName)
        edtSeats.setText(currentSeats.toString())
        spinnerShape.setSelection(shapeValues.indexOf(currentShape).coerceAtLeast(0))

        AlertDialog.Builder(this)
            .setTitle("Edit Table")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = edtName.text.toString().trim()
                val seats = edtSeats.text.toString().trim().toIntOrNull() ?: 0
                val shape = shapeValues[spinnerShape.selectedItemPosition]
                val section = knownSections[spinnerSection.selectedItemPosition]

                if (name.isBlank()) {
                    Toast.makeText(this, "Table name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                db.collection("Tables").document(tableId)
                    .update(
                        "name", name,
                        "seats", seats,
                        "shape", TableShapeView.shapeToString(shape),
                        "section", section
                    )
                    .addOnSuccessListener {
                        val tv = tableViews[tableId] as? TableShapeView
                        if (tv != null) {
                            tv.tableName = name
                            tv.seatCount = seats
                            tv.shape = shape
                        }
                        tableSections[tableId] = section
                        ensureSection(section)
                        filterTablesBySection()
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
                db.collection("Tables").document(tableId)
                    .update("active", false)
                    .addOnSuccessListener {
                        tableViews[tableId]?.let { canvas.removeView(it) }
                        tableViews.remove(tableId)
                        tableSections.remove(tableId)
                        Toast.makeText(this, "\"$name\" deleted", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
