package com.ernesto.myapplication

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.util.LinkedHashSet

/**
 * Dedicated table-selection screen for the **Reservation** flow.
 * Completely independent from the Dine-In [TableSelectionActivity].
 *
 * Two interaction modes:
 * 1. **Normal mode** – tap tables to select/deselect for the reservation.
 * 2. **Join mode** – tap 2+ same-shape tables, then tap **Confirm** or the link icon to
 *    merge them into a single virtual combined table. The merge is UI-only
 *    and NOT persisted to Firestore.
 */
class ReservationTableSelectionActivity : AppCompatActivity() {

    companion object {
        /**
         * Each string is one selection unit: a single table id, or comma-separated ids
         * for a joined group (e.g. `"t1,t4"`). Used to re-open the picker with the same
         * joins and selection.
         */
        const val EXTRA_SELECTION_GROUP_SPECS = "selectionGroupSpecs"

        /**
         * Drag positions for the reservation picker, round-tripped through [ReservationActivity].
         * Format: `tableId|x|y` entries joined by `;` (x/y = view top-left in px).
         */
        const val EXTRA_SCREEN_POSITION_OVERRIDES = "screenPosOverridesV1"
    }

    private val db = FirebaseFirestore.getInstance()
    private lateinit var canvas: FrameLayout
    private lateinit var chipGroup: ChipGroup
    private lateinit var btnConfirm: TextView
    private lateinit var btnJoinTables: ImageButton
    private lateinit var txtSubtitle: TextView

    // ── Table data (refreshed on each Firestore snapshot) ──────

    private val tableViews = mutableMapOf<String, View>()
    private val tableSections = mutableMapOf<String, String>()
    private val tableNames = mutableMapOf<String, String>()
    private val tableSeats = mutableMapOf<String, Int>()
    private val tableShapes = mutableMapOf<String, TableShapeView.Shape>()
    private val tableStatuses = mutableMapOf<String, String>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = ""

    private var useTableLayouts = false
    private var activeLayoutId = ""
    private var layoutCanvasW = 1200.0
    private var layoutCanvasH = 800.0
    private var layoutTablesListener: ListenerRegistration? = null
    private var legacyTablesListener: ListenerRegistration? = null
    private var layoutParentMetaListener: ListenerRegistration? = null
    private var reservationsForLayoutListener: ListenerRegistration? = null

    /** Same layout timing as Dine-In — table docs are RESERVED early; UI follows the hold window. */
    private var reservationsSnapshot: QuerySnapshot? = null
    private var layoutGraceAfterSlotMs: Long = 0L
    private var layoutHoldStartsBeforeSlotMs: Long = 0L
    private var previewReservedTableIds: Set<String> = emptySet()

    private val joinedTableIdsByTableId = mutableMapOf<String, List<String>>()
    private val tableReservationIds = mutableMapOf<String, String>()

    private data class TableRect(val x: Float, val y: Float, val w: Int, val h: Int)
    private val tableRects = mutableMapOf<String, TableRect>()
    private var visibleTableIds: List<String> = emptyList()

    // ── User interaction state (survives Firestore refreshes) ──

    /** Committed virtual join groups. Each inner list is sorted table IDs. */
    private val joinedGroups = mutableListOf<List<String>>()

    /** Whether join-mode toggle is active. */
    private var joinMode = false

    /** Tables the user has tapped while in join mode (building a group). */
    private val joinModeSelection = LinkedHashSet<String>()

    /** Tables selected for the reservation (normal mode). */
    private val selectedTableIds = LinkedHashSet<String>()

    /**
     * Screen-space top-left for a table or merged group (key = canonical rep id:
     * sorted-first member of a join group, or the table id for singles). Survives
     * [placeTableViews] so dragged positions are kept until Firestore removes the table id.
     */
    private val screenPositionOverrides = mutableMapOf<String, Pair<Float, Float>>()

    /**
     * Layout meta + reservations listeners often fire together; coalescing avoids two full
     * [placeTableViews] tear-downs in a row (startup flicker).
     */
    private val layoutRefreshHandler = Handler(Looper.getMainLooper())
    private val coalescedLayoutRefreshRunnable = Runnable {
        placeTableViews()
        filterTablesBySection()
        updateUI()
        scheduleReservationHoldUiBoundaryRefreshes()
    }

    /** Re-applies reserved badges when clock crosses hold start / hold end (no Firestore event). */
    private var reservationHoldBoundaryRunnable: Runnable? = null

    /** Latest tables query snapshot; applied once per frame batch (avoids cache+server double redraw). */
    private var pendingLayoutTablesSnapshot: QuerySnapshot? = null

    private val applyLayoutTablesRunnable = Runnable {
        val snap = pendingLayoutTablesSnapshot ?: return@Runnable
        pendingLayoutTablesSnapshot = null
        layoutRefreshHandler.removeCallbacks(coalescedLayoutRefreshRunnable)
        var sectionsAdded = false
        clearTableData()
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
            val status = doc.getString("status") ?: ""

            tableSections[doc.id] = section
            tableNames[doc.id] = name
            tableSeats[doc.id] = seats
            tableShapes[doc.id] = shape
            tableStatuses[doc.id] = status
            tableReservationIds[doc.id] = doc.getString("reservationId")?.trim().orEmpty()
            joinedTableIdsByTableId[doc.id] = TableJoinGroupFirestore.parseJoinedIds(doc, doc.id)

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
        screenPositionOverrides.keys.retainAll { it in visibleTableIds }
        pruneStaleGroups()
        tryConsumeInitialSelectionFromIntent()
        pruneStaleGroups()
        if (sectionsAdded) rebuildSectionChips()
        placeTableViews()
        filterTablesBySection()
        updateUI()
        scheduleReservationHoldUiBoundaryRefreshes()
    }

    /**
     * Parsed from [Intent] in [onCreate] — each inner list is one selected unit (join group or single).
     * Must not read [intent] in a property initializer: it is not attached until after [android.app.Activity.attach].
     */
    private var initialSelectionGroupSpecs: List<List<String>> = emptyList()

    /** True after we applied [initialSelectionGroupSpecs] once, or decided there is nothing to apply. */
    private var initialSelectionConsumed = false

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialSelectionGroupSpecs = parseSelectionGroupSpecsFrom(intent)
        screenPositionOverrides.putAll(parseScreenPositionOverridesFrom(intent))
        setContentView(R.layout.activity_reservation_table_selection)
        supportActionBar?.hide()

        canvas = findViewById(R.id.tableCanvas)
        chipGroup = findViewById(R.id.chipGroupSections)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnJoinTables = findViewById(R.id.btnJoinTables)
        txtSubtitle = findViewById(R.id.txtSubtitle)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnConfirm.setOnClickListener { onConfirmButtonClick() }
        btnJoinTables.setOnClickListener { onJoinButtonClick() }
        btnJoinTables.setOnLongClickListener {
            if (joinMode) cancelJoinMode()
            true
        }

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

    override fun onResume() {
        super.onResume()
        scheduleCoalescedLayoutRefresh()
    }

    private fun parseSelectionGroupSpecsFrom(i: Intent): List<List<String>> =
        i.getStringArrayListExtra(EXTRA_SELECTION_GROUP_SPECS)
            ?.mapNotNull { line ->
                val ids = line.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                ids.takeIf { it.isNotEmpty() }
            }
            .orEmpty()

    private fun parseScreenPositionOverridesFrom(i: Intent): Map<String, Pair<Float, Float>> {
        val raw = i.getStringExtra(EXTRA_SCREEN_POSITION_OVERRIDES)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Pair<Float, Float>>()
        for (segment in raw.split(";")) {
            val s = segment.trim()
            if (s.isEmpty()) continue
            val parts = s.split("|")
            if (parts.size != 3) continue
            val id = parts[0].trim()
            if (id.isEmpty()) continue
            val x = parts[1].trim().toFloatOrNull() ?: continue
            val y = parts[2].trim().toFloatOrNull() ?: continue
            out[id] = Pair(x, y)
        }
        return out
    }

    /** Encodes [screenPositionOverrides] for ids in [keys] (merged rep or single table id). */
    private fun encodePersistedScreenPositionsForKeys(keys: Iterable<String>): String =
        keys.mapNotNull { id ->
            screenPositionOverrides[id]?.let { (x, y) -> "$id|$x|$y" }
        }.joinToString(";")

    override fun onDestroy() {
        super.onDestroy()
        clearReservationHoldBoundarySchedule()
        layoutRefreshHandler.removeCallbacks(coalescedLayoutRefreshRunnable)
        canvas.removeCallbacks(applyLayoutTablesRunnable)
        pendingLayoutTablesSnapshot = null
        layoutTablesListener?.remove()
        legacyTablesListener?.remove()
        detachReservationHoldUi()
    }

    private fun scheduleCoalescedLayoutRefresh() {
        layoutRefreshHandler.removeCallbacks(coalescedLayoutRefreshRunnable)
        layoutRefreshHandler.post(coalescedLayoutRefreshRunnable)
    }

    private fun clearReservationHoldBoundarySchedule() {
        reservationHoldBoundaryRunnable?.let { layoutRefreshHandler.removeCallbacks(it) }
        reservationHoldBoundaryRunnable = null
    }

    /**
     * [placeTableViews] uses [System.currentTimeMillis] vs slot ± layout hold; without this, a table
     * stays "Reserved" until the user leaves if nothing else triggers a refresh.
     */
    private fun scheduleReservationHoldUiBoundaryRefreshes() {
        clearReservationHoldBoundarySchedule()
        if (isFinishing) return
        if (!useTableLayouts || activeLayoutId.isBlank()) return
        val snap = reservationsSnapshot ?: return
        val beforeMs = layoutHoldStartsBeforeSlotMs
        val graceAfter = layoutGraceAfterSlotMs
        val now = System.currentTimeMillis()
        var nextAt = Long.MAX_VALUE
        for (doc in snap.documents) {
            if (!ReservationFirestoreHelper.isReservationActiveForList(doc)) continue
            val slot = ReservationFirestoreHelper.reservationSlotMillisForExpiry(doc) ?: continue
            if (ReservationFirestoreHelper.isReservationHoldExpired(doc, graceAfter)) continue
            val showReservedAt = slot - beforeMs
            if (showReservedAt > now) nextAt = minOf(nextAt, showReservedAt)
            // [isReservationHoldExpired] uses strict `now > slot + grace`
            val holdEndsAt = slot + graceAfter + 1L
            if (holdEndsAt > now) nextAt = minOf(nextAt, holdEndsAt)
        }
        if (nextAt == Long.MAX_VALUE) return
        val delay = (nextAt - now).coerceIn(1L, 48L * 60 * 60 * 1000L)
        val r = Runnable {
            reservationHoldBoundaryRunnable = null
            if (isFinishing) return@Runnable
            scheduleCoalescedLayoutRefresh()
            scheduleReservationHoldUiBoundaryRefreshes()
        }
        reservationHoldBoundaryRunnable = r
        layoutRefreshHandler.postDelayed(r, delay)
    }

    private fun detachReservationHoldUi() {
        clearReservationHoldBoundarySchedule()
        layoutParentMetaListener?.remove()
        layoutParentMetaListener = null
        reservationsForLayoutListener?.remove()
        reservationsForLayoutListener = null
        reservationsSnapshot = null
        layoutGraceAfterSlotMs = 0L
        layoutHoldStartsBeforeSlotMs = 0L
        previewReservedTableIds = emptySet()
    }

    private fun attachLayoutParentMetaListener() {
        layoutParentMetaListener?.remove()
        layoutParentMetaListener = null
        if (!useTableLayouts || activeLayoutId.isBlank()) return
        layoutParentMetaListener = db.collection("tableLayouts").document(activeLayoutId)
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    layoutGraceAfterSlotMs =
                        ReservationFirestoreHelper.graceAfterSlotMsFromLayoutSnapshot(snap)
                    layoutHoldStartsBeforeSlotMs =
                        ReservationFirestoreHelper.holdStartsBeforeSlotMsFromLayoutSnapshot(snap)
                }
                scheduleCoalescedLayoutRefresh()
            }
    }

    private fun attachReservationsListenerForLayout() {
        reservationsForLayoutListener?.remove()
        reservationsForLayoutListener = null
        reservationsSnapshot = null
        if (!useTableLayouts || activeLayoutId.isBlank()) return
        reservationsForLayoutListener = db.collection(ReservationFirestoreHelper.COLLECTION)
            .whereEqualTo("tableLayoutId", activeLayoutId)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                reservationsSnapshot = snap
                scheduleCoalescedLayoutRefresh()
            }
    }

    // ── JOIN MODE ──────────────────────────────────────────────

    private fun onJoinButtonClick() {
        if (!joinMode) {
            joinMode = true
            joinModeSelection.clear()
            placeTableViews()
            filterTablesBySection()
            updateUI()
            return
        }
        if (joinModeSelection.size < 2) {
            Toast.makeText(this, "Select at least 2 tables to join", Toast.LENGTH_SHORT).show()
            return
        }
        if (!commitJoinSelectionIfReady()) return
    }

    /**
     * When join mode has 2+ same-shape tables selected, merges them into a committed group
     * and exits join mode. Returns false if not enough tables or shapes mismatch.
     */
    private fun commitJoinSelectionIfReady(): Boolean {
        if (!joinMode || joinModeSelection.size < 2) return false
        val shapes = joinModeSelection.mapNotNull { tableShapes[it] }.toSet()
        if (shapes.size > 1) {
            Toast.makeText(this, "Only similar tables can be joined", Toast.LENGTH_SHORT).show()
            return false
        }
        val group = joinModeSelection.toList().sorted()
        joinedGroups.add(group)
        selectedTableIds.addAll(group)
        joinModeSelection.clear()
        joinMode = false
        placeTableViews()
        filterTablesBySection()
        updateUI()
        return true
    }

    private fun onConfirmButtonClick() {
        if (joinMode && joinModeSelection.size >= 2) {
            if (!commitJoinSelectionIfReady()) return
            // One tap: commit the join and return the selection (no second Confirm).
            if (selectedTableIds.isNotEmpty()) confirmSelection()
            return
        }
        if (selectedTableIds.isNotEmpty()) {
            confirmSelection()
        }
    }

    private fun cancelJoinMode() {
        joinModeSelection.forEach { screenPositionOverrides.remove(it) }
        joinModeSelection.clear()
        joinMode = false
        placeTableViews()
        filterTablesBySection()
        updateUI()
        Toast.makeText(this, "Join cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun splitGroup(group: List<String>) {
        joinedGroups.remove(group)
        selectedTableIds.removeAll(group.toSet())
        group.forEach { screenPositionOverrides.remove(it) }
        placeTableViews()
        filterTablesBySection()
        updateUI()
    }

    // ── TABLE TAP HANDLING ─────────────────────────────────────

    private fun onTableTapped(tableId: String) {
        val st = tableStatuses[tableId]?.trim()?.uppercase().orEmpty()
        val g = resolvedFirestoreJoinGroup(tableId)
        val uiReserved = if (useTableLayouts) {
            TableReservationHoldDisplay.isUiReservedForHold(
                g,
                tableStatuses,
                tableReservationIds,
                reservationsSnapshot,
                layoutGraceAfterSlotMs,
                layoutHoldStartsBeforeSlotMs,
                previewReservedTableIds,
            )
        } else {
            st == "RESERVED"
        }
        if (st == "OCCUPIED" || uiReserved) {
            Toast.makeText(this, "This table is not available", Toast.LENGTH_SHORT).show()
            return
        }
        if (joinMode) {
            handleJoinModeTap(tableId)
        } else {
            handleNormalModeTap(tableId)
        }
    }

    private fun handleJoinModeTap(tableId: String) {
        if (joinedGroups.any { tableId in it }) {
            Toast.makeText(this, "This table is already in a group", Toast.LENGTH_SHORT).show()
            return
        }
        if (joinModeSelection.isNotEmpty()) {
            val refShape = tableShapes[joinModeSelection.first()]
            val thisShape = tableShapes[tableId]
            if (thisShape != refShape) {
                Toast.makeText(this, "Only similar tables can be joined", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (tableId in joinModeSelection) joinModeSelection.remove(tableId) else joinModeSelection.add(tableId)
        placeTableViews()
        filterTablesBySection()
        updateUI()
    }

    private fun handleNormalModeTap(tableId: String) {
        val group = joinedGroups.find { tableId in it }
        if (group != null) {
            if (group.all { it in selectedTableIds }) {
                selectedTableIds.removeAll(group.toSet())
            } else {
                selectedTableIds.addAll(group)
            }
        } else {
            if (tableId in selectedTableIds) selectedTableIds.remove(tableId)
            else selectedTableIds.add(tableId)
        }
        placeTableViews()
        filterTablesBySection()
        updateUI()
    }

    private fun onTableLongPressed(tableId: String): Boolean {
        val group = joinedGroups.find { tableId in it }
        if (group != null) {
            val label = group.joinToString(" + ") { tableNames[it] ?: "Table" }
            AlertDialog.Builder(this)
                .setTitle("Split $label?")
                .setMessage("This removes the join so each table can be selected separately.")
                .setPositiveButton("Split") { _, _ -> splitGroup(group) }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return false
    }

    // ── UI UPDATES ─────────────────────────────────────────────

    private fun updateUI() {
        updateJoinButtonVisual()
        updateSubtitle()
        updateConfirmButton()
    }

    private fun updateJoinButtonVisual() {
        val tint = if (joinMode) 0xFF6A4FB3.toInt() else 0xFF5D4037.toInt()
        btnJoinTables.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun updateSubtitle() {
        if (joinMode) {
            txtSubtitle.text = when {
                joinModeSelection.isEmpty() -> "Tap tables of the same shape to join them"
                joinModeSelection.size == 1 -> "Select one more table to create a group"
                else -> {
                    val label = joinModeSelection.joinToString(" + ") { tableNames[it] ?: "Table" }
                    val seats = joinModeSelection.sumOf { tableSeats[it] ?: 4 }
                    getString(R.string.reservation_table_join_confirm_hint, label, seats)
                }
            }
        } else if (selectedTableIds.isNotEmpty()) {
            val allIds = selectedTableIds.toList().sorted()
            val labels = mutableListOf<String>()
            val counted = mutableSetOf<String>()
            for (id in allIds) {
                if (id in counted) continue
                val group = joinedGroups.find { id in it }
                if (group != null) {
                    labels.add(group.joinToString(" + ") { tableNames[it] ?: "Table" })
                    counted.addAll(group)
                } else {
                    labels.add(tableNames[id] ?: "Table")
                    counted.add(id)
                }
            }
            val seats = selectedTableIds.sumOf { tableSeats[it] ?: 4 }
            txtSubtitle.text = "${labels.joinToString(", ")} • $seats seats"
        } else {
            txtSubtitle.text = "Tap tables to select, or join tables for large parties"
        }
    }

    private fun updateConfirmButton() {
        val joinPreviewReady = joinMode && joinModeSelection.size >= 2
        val hasSelection = selectedTableIds.isNotEmpty()
        btnConfirm.visibility = if (joinPreviewReady || hasSelection) View.VISIBLE else View.GONE
    }

    // ── CANVAS DRAWING ─────────────────────────────────────────

    private fun detachAllViews() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()
    }

    /**
     * First time we have table ids from Firestore, rebuild [joinedGroups] / [selectedTableIds]
     * from the intent so "Select table" again shows the same joins.
     */
    private fun tryConsumeInitialSelectionFromIntent() {
        if (initialSelectionConsumed) return
        if (visibleTableIds.isEmpty()) return
        if (initialSelectionGroupSpecs.isEmpty()) {
            initialSelectionConsumed = true
            return
        }
        val visible = visibleTableIds.toSet()
        joinedGroups.clear()
        selectedTableIds.clear()
        joinModeSelection.clear()
        joinMode = false
        for (unit in initialSelectionGroupSpecs) {
            val members = unit.filter { it in visible }
            if (members.size != unit.size) continue
            if (members.isEmpty()) continue
            if (members.size >= 2) {
                val sortedMembers = members.sorted()
                joinedGroups.add(sortedMembers)
                selectedTableIds.addAll(sortedMembers)
            } else {
                selectedTableIds.add(members.first())
            }
        }
        initialSelectionConsumed = true
    }

    private fun screenPosOrDefault(key: String, defaultX: Float, defaultY: Float): Pair<Float, Float> =
        screenPositionOverrides[key] ?: Pair(defaultX, defaultY)

    private fun placeTableViews() {
        detachAllViews()
        previewReservedTableIds = TableReservationHoldDisplay.rebuildPreviewReservedTableIds(
            reservationsSnapshot,
            layoutHoldStartsBeforeSlotMs,
            layoutGraceAfterSlotMs,
        )

        val committedIds = joinedGroups.flatten().toSet()
        val previewGroup: List<String>? =
            if (joinMode && joinModeSelection.size >= 2) joinModeSelection.toList().sorted() else null
        val previewIds = previewGroup?.toSet() ?: emptySet()
        val hiddenIds = mutableSetOf<String>()

        // 1. Committed join groups → one merged table each (rep = lexicographically first id)
        for (group in joinedGroups) {
            val rep = group.first()
            hiddenIds.addAll(group.filter { it != rep })
            val avgX = group.mapNotNull { tableRects[it]?.x }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
            val avgY = group.mapNotNull { tableRects[it]?.y }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
            val (px, py) = screenPosOrDefault(rep, avgX, avgY)
            val totalW = group.sumOf { TableShapeView.defaultMeasuredWidthPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE) }
            val totalH = group.maxOf { TableShapeView.defaultMeasuredHeightPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE) }
            val label = group.joinToString(" + ") { tableNames[it] ?: "Table" }
            val seats = group.sumOf { tableSeats[it] ?: 4 }
            val isSelected = group.all { it in selectedTableIds }
            addTableViewForMembers(
                repId = rep,
                memberIds = group,
                name = label,
                seats = seats,
                tableShape = TableShapeView.Shape.RECTANGLE,
                posX = px,
                posY = py,
                customSize = Pair(totalW, totalH),
                highlighted = isSelected,
                animateMerge = false,
            )
        }

        // 2. Join-mode preview → temporary merged table (same rep rule as committed: sorted first)
        if (previewGroup != null) {
            val rep = previewGroup.first()
            hiddenIds.addAll(previewGroup.filter { it != rep })
            val avgX = previewGroup.mapNotNull { tableRects[it]?.x }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
            val avgY = previewGroup.mapNotNull { tableRects[it]?.y }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
            val (px, py) = screenPosOrDefault(rep, avgX, avgY)
            val totalW = previewGroup.sumOf { TableShapeView.defaultMeasuredWidthPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE) }
            val totalH = previewGroup.maxOf { TableShapeView.defaultMeasuredHeightPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE) }
            val label = previewGroup.joinToString(" + ") { tableNames[it] ?: "Table" }
            val seats = previewGroup.sumOf { tableSeats[it] ?: 4 }
            addTableViewForMembers(
                repId = rep,
                memberIds = previewGroup,
                name = label,
                seats = seats,
                tableShape = TableShapeView.Shape.RECTANGLE,
                posX = px,
                posY = py,
                customSize = Pair(totalW, totalH),
                highlighted = true,
                animateMerge = true,
            )
        }

        // 3. Individual tables (skip members of committed groups & preview)
        for (tableId in visibleTableIds) {
            if (tableId in hiddenIds || tableId in committedIds || tableId in previewIds) continue
            val rect = tableRects[tableId] ?: continue
            val (px, py) = screenPosOrDefault(tableId, rect.x, rect.y)
            val name = tableNames[tableId] ?: "Table"
            val seats = tableSeats[tableId] ?: 4
            val shape = tableShapes[tableId] ?: TableShapeView.Shape.SQUARE
            val highlighted = tableId in selectedTableIds ||
                (joinMode && tableId in joinModeSelection)
            addTableViewForMembers(
                repId = tableId,
                memberIds = listOf(tableId),
                name = name,
                seats = seats,
                tableShape = shape,
                posX = px,
                posY = py,
                customSize = null,
                highlighted = highlighted,
                animateMerge = false,
            )
        }
    }

    private fun resolvedFirestoreJoinGroup(tableId: String): List<String> =
        (joinedTableIdsByTableId[tableId] ?: listOf(tableId)).sorted()

    private fun addTableViewForMembers(
        repId: String,
        memberIds: List<String>,
        name: String,
        seats: Int,
        tableShape: TableShapeView.Shape,
        posX: Float,
        posY: Float,
        customSize: Pair<Int, Int>?,
        highlighted: Boolean,
        animateMerge: Boolean,
    ) {
        val tableView = TableShapeView(this).apply {
            tableName = name
            seatCount = seats
            shape = tableShape
            forcedSizePx = customSize
            isJoinLinkSelected = highlighted
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        tableView.layoutParams = params
        canvas.addView(tableView)
        tableView.post {
            tableView.x = posX
            tableView.y = posY
        }

        applyTableStatusForMembers(memberIds, tableView)

        if (animateMerge) {
            tableView.scaleX = 0.6f
            tableView.scaleY = 0.6f
            tableView.alpha = 0.4f
            tableView.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280)
                .start()
        }

        setupDragAndTap(tableView, repId)
        tableViews[repId] = tableView
    }

    /**
     * Combined touch listener that distinguishes drag vs tap vs long-press.
     * Dragging is purely visual (no Firestore writes) so the user can
     * reposition tables to avoid overlap.
     */
    private fun setupDragAndTap(view: TableShapeView, tableId: String) {
        val dragThreshold = 14f * resources.displayMetrics.density
        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false
        var longPressPosted = false

        val longPressRunnable = Runnable {
            if (!isDragging) {
                longPressPosted = false
                onTableLongPressed(tableId)
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    longPressPosted = true
                    v.handler?.postDelayed(longPressRunnable, 500L)
                    v.elevation = 8f
                    v.bringToFront()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = abs(event.rawX - startRawX)
                    val movedY = abs(event.rawY - startRawY)
                    if (!isDragging && (movedX > dragThreshold || movedY > dragThreshold)) {
                        isDragging = true
                        if (longPressPosted) {
                            v.handler?.removeCallbacks(longPressRunnable)
                            longPressPosted = false
                        }
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDragging) {
                        val maxX = (canvas.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (canvas.height - v.height).toFloat().coerceAtLeast(0f)
                        v.x = (event.rawX + dX).coerceIn(0f, maxX)
                        v.y = (event.rawY + dY).coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.elevation = 0f
                    if (longPressPosted) {
                        v.handler?.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (isDragging) {
                        val maxX = (canvas.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (canvas.height - v.height).toFloat().coerceAtLeast(0f)
                        screenPositionOverrides[tableId] = Pair(
                            v.x.coerceIn(0f, maxX),
                            v.y.coerceIn(0f, maxY),
                        )
                    } else {
                        onTableTapped(tableId)
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.elevation = 0f
                    if (longPressPosted) {
                        v.handler?.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (isDragging) {
                        val maxX = (canvas.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (canvas.height - v.height).toFloat().coerceAtLeast(0f)
                        screenPositionOverrides[tableId] = Pair(
                            v.x.coerceIn(0f, maxX),
                            v.y.coerceIn(0f, maxY),
                        )
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun applyTableStatusForMembers(memberIds: List<String>, view: TableShapeView) {
        val occupied = memberIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "OCCUPIED" }
        val reserved = if (useTableLayouts) {
            memberIds.any { tid ->
                val g = resolvedFirestoreJoinGroup(tid)
                TableReservationHoldDisplay.isUiReservedForHold(
                    g,
                    tableStatuses,
                    tableReservationIds,
                    reservationsSnapshot,
                    layoutGraceAfterSlotMs,
                    layoutHoldStartsBeforeSlotMs,
                    previewReservedTableIds,
                )
            }
        } else {
            memberIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "RESERVED" }
        }
        view.isOccupied = occupied
        view.isReserved = reserved
        view.detailPartyOf = ""
        when {
            view.isOccupied -> {
                view.detailStatusLabel = getString(R.string.table_status_occupied)
                view.statusPill = TableShapeView.StatusPill.OCCUPIED
            }
            view.isReserved -> {
                view.detailStatusLabel = getString(R.string.table_status_reserved)
                view.statusPill = TableShapeView.StatusPill.RESERVED
            }
            else -> {
                view.detailStatusLabel = ""
                view.statusPill = TableShapeView.StatusPill.NONE
            }
        }
        view.guestInfo = ""
    }

    // ── CONFIRM / RESULT ───────────────────────────────────────

    private fun confirmSelection() {
        if (selectedTableIds.isEmpty()) {
            Toast.makeText(this, "Select at least one table", Toast.LENGTH_SHORT).show()
            return
        }
        val sorted = selectedTableIds.toList().sorted()
        val primaryId = sorted.first()
        val labels = mutableListOf<String>()
        val counted = mutableSetOf<String>()
        val selectionGroupSpecs = ArrayList<String>()
        val positionKeys = linkedSetOf<String>()
        for (id in sorted) {
            if (id in counted) continue
            val group = joinedGroups.find { id in it }
            if (group != null) {
                labels.add(group.joinToString(" + ") { tableNames[it] ?: "Table" })
                counted.addAll(group)
                selectionGroupSpecs.add(group.joinToString(","))
                positionKeys.add(group.first())
            } else {
                labels.add(tableNames[id] ?: "Table")
                counted.add(id)
                selectionGroupSpecs.add(id)
                positionKeys.add(id)
            }
        }
        val posEncoded = encodePersistedScreenPositionsForKeys(positionKeys)
        val cw = canvas.width.coerceAtLeast(1)
        val ch = canvas.height.coerceAtLeast(1)
        val mapUiNormsEncoded = positionKeys.mapNotNull { id ->
            screenPositionOverrides[id]?.let { (px, py) ->
                "${id}|${px / cw}|${py / ch}"
            }
        }.joinToString(";")
        val result = Intent().apply {
            putExtra("tableId", primaryId)
            putExtra("tableName", labels.joinToString(", "))
            putExtra("tableLayoutId", if (useTableLayouts) activeLayoutId else "")
            putStringArrayListExtra("joinedTableIds", ArrayList(sorted))
            putStringArrayListExtra(EXTRA_SELECTION_GROUP_SPECS, selectionGroupSpecs)
            if (posEncoded.isNotEmpty()) {
                putExtra(EXTRA_SCREEN_POSITION_OVERRIDES, posEncoded)
            }
            if (mapUiNormsEncoded.isNotEmpty()) {
                putExtra(ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1, mapUiNormsEncoded)
            }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    // ── SECTIONS ───────────────────────────────────────────────

    private fun rebuildSectionChips() {
        chipGroup.removeAllViews()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bgColors = intArrayOf(0xFF6A4FB3.toInt(), 0xFFE0E0E0.toInt())
        val txtColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF333333.toInt())
        if (selectedSection.isEmpty() && knownSections.isNotEmpty()) selectedSection = knownSections.first()
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
            val group = joinedGroups.find { id in it }
            val idsToCheck = group ?: listOf(id)
            val match = selectedSection.isEmpty() ||
                idsToCheck.any { (tableSections[it] ?: "").equals(selectedSection, ignoreCase = true) }
            view.visibility = if (match) View.VISIBLE else View.GONE
        }
    }

    // ── DATA LOADING ───────────────────────────────────────────

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

    private fun clearTableData() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()
        tableSections.clear()
        tableNames.clear()
        tableSeats.clear()
        tableShapes.clear()
        tableStatuses.clear()
        tableRects.clear()
        joinedTableIdsByTableId.clear()
        tableReservationIds.clear()
        visibleTableIds = emptyList()
    }

    private fun loadTablesPreferred() {
        db.collection("tableLayouts").get()
            .addOnSuccessListener { layoutSnap ->
                if (layoutSnap.isEmpty) {
                    useTableLayouts = false
                    activeLayoutId = ""
                    detachReservationHoldUi()
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
                layoutGraceAfterSlotMs =
                    ReservationFirestoreHelper.graceAfterSlotMsFromLayoutSnapshot(layoutDoc)
                layoutHoldStartsBeforeSlotMs =
                    ReservationFirestoreHelper.holdStartsBeforeSlotMsFromLayoutSnapshot(layoutDoc)

                layoutTablesListener?.remove()
                layoutTablesListener = db.collection("tableLayouts").document(activeLayoutId)
                    .collection("tables")
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        applyLayoutSnapshot(snap)
                    }
                attachLayoutParentMetaListener()
                attachReservationsListenerForLayout()
            }
            .addOnFailureListener {
                detachReservationHoldUi()
                loadTablesLegacy()
            }
    }

    private fun applyLayoutSnapshot(snap: com.google.firebase.firestore.QuerySnapshot) {
        pendingLayoutTablesSnapshot = snap
        canvas.removeCallbacks(applyLayoutTablesRunnable)
        canvas.post(applyLayoutTablesRunnable)
    }

    private fun loadTablesLegacy() {
        detachReservationHoldUi()
        legacyTablesListener?.remove()
        legacyTablesListener = db.collection("Tables")
            .whereEqualTo("active", true)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                layoutRefreshHandler.removeCallbacks(coalescedLayoutRefreshRunnable)
                canvas.post {
                    clearTableData()
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
                        val status = doc.getString("status") ?: ""

                        tableSections[doc.id] = section
                        tableNames[doc.id] = name
                        tableSeats[doc.id] = seats
                        tableShapes[doc.id] = shape
                        tableStatuses[doc.id] = status
                        tableReservationIds[doc.id] = doc.getString("reservationId")?.trim().orEmpty()
                        joinedTableIdsByTableId[doc.id] = TableJoinGroupFirestore.parseJoinedIds(doc, doc.id)

                        val wPx = TableShapeView.defaultMeasuredWidthPx(this, shape)
                        val hPx = TableShapeView.defaultMeasuredHeightPx(this, shape)
                        tableRects[doc.id] = TableRect(posX, posY, wPx, hPx)
                        ids.add(doc.id)
                    }
                    visibleTableIds = ids.toList()
                    screenPositionOverrides.keys.retainAll { it in visibleTableIds }
                    pruneStaleGroups()
                    tryConsumeInitialSelectionFromIntent()
                    pruneStaleGroups()
                    placeTableViews()
                    filterTablesBySection()
                    updateUI()
                }
            }
    }

    /** Remove committed groups whose member IDs no longer exist in the current data. */
    private fun pruneStaleGroups() {
        val currentIds = visibleTableIds.toSet()
        joinedGroups.removeAll { group -> group.any { it !in currentIds } }
        selectedTableIds.retainAll(currentIds)
        joinModeSelection.retainAll(currentIds)
        screenPositionOverrides.keys.retainAll { key ->
            key in currentIds || joinedGroups.any { it.first() == key }
        }
    }
}
