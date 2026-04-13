package com.ernesto.myapplication

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Read-only floor plan for a single reservation: shows where the booked table(s)
 * sit on the layout, with a clear highlight on the reserved spot(s).
 */
class ReservationMappingActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var canvas: FrameLayout
    private lateinit var chipGroup: ChipGroup
    private lateinit var txtSubtitle: TextView
    private lateinit var btnCancelReservation: TextView
    private lateinit var currentReservationId: String

    private val tableViews = mutableMapOf<String, View>()
    private val tableSections = mutableMapOf<String, String>()
    private val tableNames = mutableMapOf<String, String>()
    private val tableSeats = mutableMapOf<String, Int>()
    private val tableShapes = mutableMapOf<String, TableShapeView.Shape>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = ""

    private var layoutTablesListener: ListenerRegistration? = null
    private var legacyTablesListener: ListenerRegistration? = null

    private data class TableRect(val x: Float, val y: Float, val w: Int, val h: Int)
    private val tableRects = mutableMapOf<String, TableRect>()
    private var visibleTableIds: List<String> = emptyList()

    private var layoutCanvasW = 1200.0
    private var layoutCanvasH = 800.0
    private var activeLayoutId = ""
    /** Table doc ids for this reservation (sorted, from reservation doc). */
    private var reservedTableIds: List<String> = emptyList()
    private var mergedLabelFromReservation: String = ""
    /** Top-left normalized to canvas (0–1) when the booking was saved; key = table id or merged rep id. */
    private var mapUiNormByTableId: Map<String, Pair<Float, Float>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation_mapping)
        supportActionBar?.hide()

        val reservationId = intent.getStringExtra(EXTRA_RESERVATION_ID)?.trim().orEmpty()
        if (reservationId.isEmpty()) {
            Toast.makeText(this, "Missing reservation", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentReservationId = reservationId

        canvas = findViewById(R.id.tableCanvas)
        chipGroup = findViewById(R.id.chipGroupSections)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        btnCancelReservation = findViewById(R.id.btnCancelReservation)
        btnCancelReservation.setOnClickListener { confirmCancelReservation() }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedSection = if (checkedIds.isNotEmpty()) {
                group.findViewById<Chip>(checkedIds[0])?.text?.toString() ?: ""
            } else {
                knownSections.firstOrNull() ?: ""
            }
            filterTablesBySection()
        }

        loadReservationAndFloor(reservationId)
    }

    override fun onDestroy() {
        super.onDestroy()
        layoutTablesListener?.remove()
        legacyTablesListener?.remove()
    }

    private fun loadReservationAndFloor(reservationId: String) {
        db.collection(ReservationFirestoreHelper.COLLECTION).document(reservationId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(this, "Reservation not found", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                val canCancel = ReservationFirestoreHelper.isReservationActiveForList(doc)
                btnCancelReservation.visibility = if (canCancel) View.VISIBLE else View.GONE
                val primary = doc.getString("tableId")?.trim().orEmpty()
                if (primary.isEmpty()) {
                    Toast.makeText(this, "No table assigned to this reservation", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                reservedTableIds = TableJoinGroupFirestore.parseJoinedIds(doc, primary).distinct().sorted()
                mergedLabelFromReservation = doc.getString("tableName")?.trim().orEmpty()
                    .ifEmpty { reservedTableIds.joinToString(" + ") { tableNames[it] ?: it } }
                mapUiNormByTableId = ReservationFirestoreHelper.parseReservationMapUiNormsV1(
                    doc.getString(ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1),
                )

                val guest = doc.getString("guestName")?.trim().orEmpty()
                val party = (doc.getLong("partySize") ?: 0L).toInt()
                val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                val whenStr = doc.getTimestamp("reservationTime")?.toDate()?.let { fmt.format(it) }
                    ?: doc.getString("whenText")?.trim().orEmpty()
                val partyLine = when {
                    party <= 0 -> ""
                    party == 1 -> getString(R.string.reservation_guests_one)
                    else -> getString(R.string.reservation_guests_many, party)
                }
                val parts = mutableListOf<String>()
                if (guest.isNotEmpty()) parts.add(guest)
                if (whenStr.isNotEmpty()) parts.add(whenStr)
                if (partyLine.isNotEmpty()) parts.add(partyLine)
                val subtitleLine = parts.joinToString(" · ")
                val hint = if (canCancel) {
                    getString(R.string.reservation_mapping_highlight_hint)
                } else {
                    getString(R.string.reservation_mapping_not_active)
                }
                txtSubtitle.text = if (subtitleLine.isNotEmpty()) {
                    "$subtitleLine\n$hint"
                } else {
                    hint
                }

                val layoutFromRes = doc.getString("tableLayoutId")?.trim()?.takeIf { it.isNotEmpty() }
                loadSectionsThenFloor(layoutFromRes)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Could not load reservation: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun loadSectionsThenFloor(layoutIdFromReservation: String?) {
        db.collection("Sections").get()
            .addOnSuccessListener { snap ->
                knownSections.clear()
                for (d in snap.documents) {
                    val name = d.getString("name") ?: d.id
                    if (name.isNotBlank() && name != "Bar") knownSections.add(name)
                }
                rebuildSectionChips()
                startFloorListener(layoutIdFromReservation)
            }
            .addOnFailureListener { startFloorListener(layoutIdFromReservation) }
    }

    private fun startFloorListener(layoutIdFromReservation: String?) {
        if (!layoutIdFromReservation.isNullOrEmpty()) {
            db.collection("tableLayouts").document(layoutIdFromReservation).get()
                .addOnSuccessListener { layoutDoc ->
                    if (!layoutDoc.exists()) {
                        attachDefaultLayoutListener()
                    } else {
                        activeLayoutId = layoutIdFromReservation
                        layoutCanvasW = layoutDoc.getDouble("canvasWidth") ?: 1200.0
                        layoutCanvasH = layoutDoc.getDouble("canvasHeight") ?: 800.0
                        attachTablesListener(activeLayoutId)
                    }
                }
                .addOnFailureListener { attachDefaultLayoutListener() }
            return
        }
        attachDefaultLayoutListener()
    }

    private fun attachDefaultLayoutListener() {
        db.collection("tableLayouts").get()
            .addOnSuccessListener { layoutSnap ->
                if (layoutSnap.isEmpty()) {
                    activeLayoutId = ""
                    loadTablesLegacy()
                    return@addOnSuccessListener
                }
                val layoutDoc = layoutSnap.documents.find { it.getBoolean("isDefault") == true }
                    ?: layoutSnap.documents.minByOrNull { it.getLong("sortOrder") ?: 0L }
                    ?: layoutSnap.documents.first()
                activeLayoutId = layoutDoc.id
                layoutCanvasW = layoutDoc.getDouble("canvasWidth") ?: 1200.0
                layoutCanvasH = layoutDoc.getDouble("canvasHeight") ?: 800.0
                attachTablesListener(activeLayoutId)
            }
            .addOnFailureListener { loadTablesLegacy() }
    }

    private fun attachTablesListener(layoutId: String) {
        layoutTablesListener?.remove()
        layoutTablesListener = db.collection("tableLayouts").document(layoutId)
            .collection("tables")
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                applyLayoutSnapshot(snap)
            }
    }

    private fun applyLayoutSnapshot(snap: com.google.firebase.firestore.QuerySnapshot) {
        clearCanvas()
        var sectionsAdded = false
        canvas.post {
            val cw = canvas.width.toFloat().coerceAtLeast(1f)
            val ch = canvas.height.toFloat().coerceAtLeast(1f)
            val ids = mutableListOf<String>()
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
                val shape = TableShapeView.shapeFromString(doc.getString("shape"))
                val xL = doc.getDouble("x") ?: doc.getDouble("posX") ?: 50.0
                val yL = doc.getDouble("y") ?: doc.getDouble("posY") ?: 50.0
                val section = doc.getString("section") ?: ""

                tableSections[doc.id] = section
                tableNames[doc.id] = name
                tableSeats[doc.id] = seats
                tableShapes[doc.id] = shape

                if (section.isNotBlank() && section !in knownSections) {
                    knownSections.add(section)
                    sectionsAdded = true
                }

                val posX = (xL * cw / layoutCanvasW).toFloat()
                val posY = (yL * ch / layoutCanvasH).toFloat()
                val wPx = TableShapeView.defaultMeasuredWidthPx(this, shape)
                val hPx = TableShapeView.defaultMeasuredHeightPx(this, shape)
                tableRects[doc.id] = TableRect(posX, posY, wPx, hPx)
                ids.add(doc.id)
            }
            visibleTableIds = ids.toList()
            if (sectionsAdded) rebuildSectionChips()
            placeMappingViews()
            filterTablesBySection()
        }
    }

    private fun loadTablesLegacy() {
        legacyTablesListener?.remove()
        legacyTablesListener = db.collection("Tables")
            .whereEqualTo("active", true)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                clearCanvas()
                canvas.post {
                    val ids = mutableListOf<String>()
                    for (doc in snap.documents) {
                        val areaType = doc.getString("areaType") ?: "DINING_TABLE"
                        if (areaType == "BAR_SEAT") continue
                        val name = doc.getString("name") ?: "Table"
                        val seats = doc.getLong("seats")?.toInt() ?: 4
                        val shape = TableShapeView.shapeFromString(doc.getString("shape"))
                        val posX = doc.getDouble("posX")?.toFloat() ?: 50f
                        val posY = doc.getDouble("posY")?.toFloat() ?: 50f
                        val section = doc.getString("section") ?: ""

                        tableSections[doc.id] = section
                        tableNames[doc.id] = name
                        tableSeats[doc.id] = seats
                        tableShapes[doc.id] = shape

                        val wPx = TableShapeView.defaultMeasuredWidthPx(this, shape)
                        val hPx = TableShapeView.defaultMeasuredHeightPx(this, shape)
                        tableRects[doc.id] = TableRect(posX, posY, wPx, hPx)
                        ids.add(doc.id)
                    }
                    visibleTableIds = ids.toList()
                    placeMappingViews()
                    filterTablesBySection()
                }
            }
    }

    private fun clearCanvas() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()
        tableSections.clear()
        tableNames.clear()
        tableSeats.clear()
        tableShapes.clear()
        tableRects.clear()
        visibleTableIds = emptyList()
    }

    private fun placeMappingViews() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()

        val cw = canvas.width.toFloat().coerceAtLeast(1f)
        val ch = canvas.height.toFloat().coerceAtLeast(1f)

        val reserved = reservedTableIds.toSet()
        val presentIds = reservedTableIds.filter { it in tableRects }
        val merge = presentIds.size >= 2

        if (merge) {
            val rep = presentIds.first()
            val group = presentIds
            val rects = group.mapNotNull { tableRects[it] }
            val norm = mapUiNormByTableId[rep]
            val avgX: Float
            val avgY: Float
            if (norm != null) {
                avgX = norm.first * cw
                avgY = norm.second * ch
            } else {
                avgX = rects.map { it.x }.average().toFloat()
                avgY = rects.map { it.y }.average().toFloat()
            }
            val totalW = group.sumOf {
                TableShapeView.defaultMeasuredWidthPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE)
            }
            val totalH = group.maxOf {
                TableShapeView.defaultMeasuredHeightPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE)
            }
            val seats = group.sumOf { tableSeats[it] ?: 4 }
            val label = mergedLabelFromReservation.ifBlank {
                group.joinToString(" + ") { tableNames[it] ?: "Table" }
            }
            addShapeView(rep, label, seats, TableShapeView.Shape.RECTANGLE, avgX, avgY, Pair(totalW, totalH), highlight = true)
        }

        val mergedIds = if (merge) presentIds.toSet() else emptySet()
        for (tableId in visibleTableIds) {
            if (tableId in mergedIds) continue
            val rect = tableRects[tableId] ?: continue
            val name = tableNames[tableId] ?: "Table"
            val seats = tableSeats[tableId] ?: 4
            val shape = tableShapes[tableId] ?: TableShapeView.Shape.SQUARE
            val highlight = tableId in reserved
            val norm = mapUiNormByTableId[tableId]
            val posX = if (norm != null) norm.first * cw else rect.x
            val posY = if (norm != null) norm.second * ch else rect.y
            addShapeView(tableId, name, seats, shape, posX, posY, null, highlight = highlight)
        }
    }

    private fun addShapeView(
        id: String,
        name: String,
        seats: Int,
        shape: TableShapeView.Shape,
        posX: Float,
        posY: Float,
        forced: Pair<Int, Int>?,
        highlight: Boolean,
    ) {
        val v = TableShapeView(this).apply {
            tableName = name
            seatCount = seats
            this.shape = shape
            forcedSizePx = forced
            isJoinLinkSelected = highlight
            alpha = if (highlight) 1f else 0.55f
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        v.layoutParams = lp
        canvas.addView(v)
        v.post {
            v.x = posX
            v.y = posY
        }
        tableViews[id] = v
    }

    private fun rebuildSectionChips() {
        chipGroup.removeAllViews()
        if (knownSections.isEmpty()) return
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bgColors = intArrayOf(0xFF6A4FB3.toInt(), 0xFFE0E0E0.toInt())
        val txtColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF333333.toInt())
        if (selectedSection.isEmpty()) selectedSection = knownSections.first()
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
        if (knownSections.isEmpty()) {
            chipGroup.visibility = View.GONE
            for ((_, view) in tableViews) view.visibility = View.VISIBLE
            return
        }
        chipGroup.visibility = View.VISIBLE
        val presentReserved = reservedTableIds.filter { it in tableRects }
        val mergedRep = if (presentReserved.size >= 2) presentReserved.first() else null
        for ((id, view) in tableViews) {
            val groupIds = when {
                mergedRep != null && id == mergedRep -> presentReserved
                else -> listOf(id)
            }
            val match = selectedSection.isEmpty() ||
                groupIds.any { (tableSections[it] ?: "").equals(selectedSection, ignoreCase = true) }
            view.visibility = if (match) View.VISIBLE else View.GONE
        }
    }

    private fun confirmCancelReservation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reservation_cancel_title)
            .setMessage(R.string.reservation_cancel_message)
            .setPositiveButton(R.string.reservation_cancel_confirm) { _, _ ->
                ReservationFirestoreHelper.cancelReservation(
                    db = db,
                    reservationId = currentReservationId,
                    onSuccess = {
                        Toast.makeText(this, R.string.reservation_cancelled, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            this,
                            getString(R.string.reservation_cancel_failed, e.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_RESERVATION_ID = "reservationId"
    }
}
