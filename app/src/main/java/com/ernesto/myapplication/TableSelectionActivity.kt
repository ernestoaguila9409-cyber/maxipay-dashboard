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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dine-In operational table screen. Shows tables at their floor-plan positions
 * with live order status. Tables that are reserved together (via the Reservation
 * flow) automatically merge into one combined visual table here.
 */
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
    private val tableShapes = mutableMapOf<String, TableShapeView.Shape>()
    private val knownSections = mutableListOf<String>()
    private var selectedSection = ""
    private val occupiedTableData = mutableMapOf<String, OccupiedTableInfo>()
    private val tableStatuses = mutableMapOf<String, String>()
    private var occupiedListener: ListenerRegistration? = null

    /** From Firestore `joinedTableIds` on each table doc; defaults to single-table list. */
    private val joinedTableIdsByTableId = mutableMapOf<String, List<String>>()

    /**
     * Denormalized copy of [ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1] from
     * each layout table doc (same string on every member of a join), for Dine-In canvas placement.
     */
    private val tableDocMapUiNormsEnc = mutableMapOf<String, String>()

    private var useTableLayouts: Boolean = false
    private var activeLayoutId: String = ""
    private var layoutCanvasW: Double = 1200.0
    private var layoutCanvasH: Double = 800.0
    private var layoutTablesListener: ListenerRegistration? = null
    private var legacyTablesListener: ListenerRegistration? = null
    private var lastLayoutTablesSnapshot: QuerySnapshot? = null
    private var layoutParentMetaListener: ListenerRegistration? = null

    /** From layout doc — used with reservation snapshot for Dine-In “reserved before slot” preview. */
    private var layoutGraceAfterSlotMs: Long = 0L
    private var layoutHoldStartsBeforeSlotMs: Long = 0L
    private var dineInReservationsListener: ListenerRegistration? = null
    private var lastDineInReservationsSnapshot: QuerySnapshot? = null
    private val dineInReservationPreviewTableIds = mutableSetOf<String>()

    /** [tableDocId] → reservation doc id on hold (Firestore sets RESERVED before the Dine-In preview window). */
    private val tableReservationIds = mutableMapOf<String, String>()

    /** When this changes, merged vs separate floor layout must be rebuilt (time crosses “reserved before” window). */
    private var lastFloorVisualMergeKey: String? = null

    private data class TableScreenRect(val x: Float, val y: Float, val w: Int, val h: Int)
    private val tableLayoutScreenRect = mutableMapOf<String, TableScreenRect>()
    private var layoutVisibleTableIds: List<String> = emptyList()

    private val waitingHandler = Handler(Looper.getMainLooper())
    private var reservationUiBoundaryRunnable: Runnable? = null

    /** Layout meta + reservation snapshot often fire together — one [applyOccupiedState] avoids double [placeLayoutTableVisuals]. */
    private val coalescedOccupiedRefreshRunnable = Runnable {
        applyOccupiedState()
        scheduleReservationUiBoundaryRefreshes()
    }

    /** Latest layout tables query; one apply per burst (cache + server). */
    private var pendingLayoutTablesSnapshot: QuerySnapshot? = null

    private val applyLayoutTablesRunnable = Runnable {
        val snap = pendingLayoutTablesSnapshot ?: return@Runnable
        pendingLayoutTablesSnapshot = null
        waitingHandler.removeCallbacks(coalescedOccupiedRefreshRunnable)
        clearReservationUiBoundarySchedule()
        var sectionsAdded = false
        clearTableCanvas()
        val cw = canvas.width.toFloat().coerceAtLeast(1f)
        val ch = canvas.height.toFloat().coerceAtLeast(1f)
        val visibleIds = mutableListOf<String>()
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
            tableStatuses[doc.id] = doc.getString("status") ?: ""
            tableReservationIds[doc.id] = doc.getString("reservationId")?.trim().orEmpty()
            joinedTableIdsByTableId[doc.id] = TableJoinGroupFirestore.parseJoinedIds(doc, doc.id)
            doc.getString(ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { enc -> tableDocMapUiNormsEnc[doc.id] = enc }

            if (section.isNotBlank() && section !in knownSections) {
                knownSections.add(section)
                db.collection("Sections").document(section).set(hashMapOf("name" to section))
                sectionsAdded = true
            }

            val posX = (xL * cw / layoutCanvasW).toFloat()
            val posY = (yL * ch / layoutCanvasH).toFloat()
            val wPx = TableShapeView.defaultMeasuredWidthPx(this, shape)
            val hPx = TableShapeView.defaultMeasuredHeightPx(this, shape)
            tableLayoutScreenRect[doc.id] = TableScreenRect(posX, posY, wPx, hPx)
            visibleIds.add(doc.id)
        }
        layoutVisibleTableIds = visibleIds.toList()
        if (sectionsAdded) rebuildSectionChips()
        placeLayoutTableVisuals()
        filterTablesBySection()
        applyOccupiedState()
        ReservationFirestoreHelper.sweepExpiredHoldsForTableDocuments(db, snap, activeLayoutId)
        lastLayoutTablesSnapshot = snap
        scheduleReservationUiBoundaryRefreshes()
    }

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
        findViewById<ImageButton>(R.id.btnJoinTables).visibility = View.GONE

        val subtitle = findViewById<TextView>(R.id.txtTableSelectSubtitle)
        subtitle.text = getString(R.string.table_select_subtitle_dine_in)

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
        scheduleCoalescedOccupiedRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        occupiedListener?.remove()
        layoutTablesListener?.remove()
        legacyTablesListener?.remove()
        layoutParentMetaListener?.remove()
        dineInReservationsListener?.remove()
        waitingHandler.removeCallbacks(waitingRefreshRunnable)
        waitingHandler.removeCallbacks(coalescedOccupiedRefreshRunnable)
        canvas.removeCallbacks(applyLayoutTablesRunnable)
        pendingLayoutTablesSnapshot = null
        clearReservationUiBoundarySchedule()
    }

    // ── HELPERS ────────────────────────────────────────────

    private fun resolvedJoinedGroup(tableId: String): List<String> =
        joinedTableIdsByTableId[tableId] ?: listOf(tableId)

    /**
     * Tables linked for a **future** reservation stay separate on the floor until the Dine-In
     * reserved window; then [shouldMergeGroup] is true. Until then, seating uses a single table id.
     */
    private fun operationalPartyTableIds(tableId: String): List<String> {
        val full = resolvedJoinedGroup(tableId).sorted()
        if (full.size < 2) return full
        return if (shouldMergeGroup(full)) full else listOf(tableId)
    }

    private fun detachDineInReservationPreview() {
        dineInReservationsListener?.remove()
        dineInReservationsListener = null
        lastDineInReservationsSnapshot = null
        dineInReservationPreviewTableIds.clear()
        layoutGraceAfterSlotMs = 0L
        layoutHoldStartsBeforeSlotMs = 0L
        clearReservationUiBoundarySchedule()
    }

    private fun clearReservationUiBoundarySchedule() {
        reservationUiBoundaryRunnable?.let { waitingHandler.removeCallbacks(it) }
        reservationUiBoundaryRunnable = null
    }

    /**
     * Re-applies Dine-In reserved/merge state at layout-defined clock boundaries (slot − minutes-before,
     * and hold end after slot + grace). Without this, [WAITING_REFRESH_MS] polling can show "Reserved"
     * up to a minute late.
     */
    private fun scheduleReservationUiBoundaryRefreshes() {
        clearReservationUiBoundarySchedule()
        if (isFinishing) return
        if (!useTableLayouts || activeLayoutId.isBlank()) return
        val snap = lastDineInReservationsSnapshot ?: return
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
            reservationUiBoundaryRunnable = null
            if (isFinishing) return@Runnable
            applyOccupiedState()
            scheduleReservationUiBoundaryRefreshes()
        }
        reservationUiBoundaryRunnable = r
        waitingHandler.postDelayed(r, delay)
    }

    private fun scheduleCoalescedOccupiedRefresh() {
        waitingHandler.removeCallbacks(coalescedOccupiedRefreshRunnable)
        waitingHandler.post(coalescedOccupiedRefreshRunnable)
    }

    private fun isDineInPreviewReservedForGroup(groupIds: List<String>): Boolean =
        groupIds.any { it in dineInReservationPreviewTableIds }

    /**
     * Reservation doc for this table group from the Dine-In listener (same layout).
     * Prefers [tableReservationIds] on table docs, then matches [Reservations] by joined tables.
     */
    private fun activeReservationDocForGroup(groupIds: List<String>): DocumentSnapshot? {
        val snap = lastDineInReservationsSnapshot ?: return null
        for (tid in groupIds) {
            val rid = tableReservationIds[tid]?.trim().orEmpty()
            if (rid.isNotEmpty()) {
                snap.documents.find { it.id == rid }
                    ?.takeIf { ReservationFirestoreHelper.isReservationActiveForList(it) }
                    ?.let { return it }
            }
        }
        for (doc in snap.documents) {
            if (!ReservationFirestoreHelper.isReservationActiveForList(doc)) continue
            val primary = doc.getString("tableId")?.trim().orEmpty()
            val joined = TableJoinGroupFirestore.parseJoinedIds(doc, primary)
            if (groupIds.any { it in joined }) return doc
        }
        return null
    }

    /**
     * Table docs are marked RESERVED as soon as a booking is saved; Dine-In display should follow
     * layout [reservationHoldStartsMinutesBeforeSlot] (same rule as dashboard copy).
     */
    private fun isFirestoreReservedForDineInDisplay(groupIds: List<String>): Boolean {
        if (!groupIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "RESERVED" }) return false
        val resDoc = activeReservationDocForGroup(groupIds)
            ?: return true
        if (ReservationFirestoreHelper.isReservationHoldExpired(resDoc, layoutGraceAfterSlotMs)) return false
        val slot = ReservationFirestoreHelper.reservationSlotMillisForExpiry(resDoc) ?: return true
        val now = System.currentTimeMillis()
        val beforeMs = layoutHoldStartsBeforeSlotMs
        return now >= slot - beforeMs
    }

    private fun isUiReservedForGroup(groupIds: List<String>): Boolean =
        isFirestoreReservedForDineInDisplay(groupIds) || isDineInPreviewReservedForGroup(groupIds)

    private fun rebuildDineInPreviewReservedTableIds() {
        dineInReservationPreviewTableIds.clear()
        val beforeMs = layoutHoldStartsBeforeSlotMs
        if (beforeMs <= 0L) return
        val snap = lastDineInReservationsSnapshot ?: return
        val now = System.currentTimeMillis()
        val graceAfter = layoutGraceAfterSlotMs
        for (doc in snap.documents) {
            if (!ReservationFirestoreHelper.isReservationActiveForList(doc)) continue
            val slot = ReservationFirestoreHelper.reservationSlotMillisForExpiry(doc) ?: continue
            if (ReservationFirestoreHelper.isReservationHoldExpired(doc, graceAfter)) continue
            if (now < slot - beforeMs) continue
            val primary = doc.getString("tableId")?.trim().orEmpty()
            if (primary.isEmpty()) continue
            val ids = TableJoinGroupFirestore.parseJoinedIds(doc, primary)
            dineInReservationPreviewTableIds.addAll(ids)
        }
    }

    private fun attachDineInReservationPreviewListener() {
        dineInReservationsListener?.remove()
        dineInReservationsListener = null
        lastDineInReservationsSnapshot = null
        dineInReservationPreviewTableIds.clear()
        if (!useTableLayouts || activeLayoutId.isBlank()) return
        dineInReservationsListener = db.collection(ReservationFirestoreHelper.COLLECTION)
            .whereEqualTo("tableLayoutId", activeLayoutId)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                lastDineInReservationsSnapshot = snap
                scheduleCoalescedOccupiedRefresh()
            }
    }

    /**
     * One merged shape when an open order spans the join, or when the reservation hold is
     * **visible** on Dine-In (slot − minutes-before through hold end). Future holds stay separate.
     */
    private fun shouldMergeGroup(group: List<String>): Boolean {
        if (group.size < 2) return false
        if (group.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "OCCUPIED" }) return true
        if (!group.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "RESERVED" }) return false
        return isUiReservedForGroup(group)
    }

    private fun floorVisualMergeStateKey(): String =
        joinedTableIdsByTableId.values
            .map { it.sorted() }
            .distinct()
            .sortedWith(compareBy { it.joinToString(",") })
            .joinToString("|") { g -> "${g.joinToString(",")}:${if (shouldMergeGroup(g)) "M" else "S"}" }

    /** IDs that are non-representative members of a merged group (hidden from canvas). */
    private fun mergedNonRepresentativeIds(): Set<String> {
        val out = mutableSetOf<String>()
        for (g in joinedTableIdsByTableId.values.map { it.sorted() }.distinct()) {
            if (!shouldMergeGroup(g)) continue
            out.addAll(g.drop(1))
        }
        return out
    }

    // ── SECTIONS ───────────────────────────────────────────

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
            val group = resolvedJoinedGroup(id)
            val idsToCheck = if (shouldMergeGroup(group)) group else listOf(id)
            val match = selectedSection.isEmpty() ||
                idsToCheck.any { (tableSections[it] ?: "").equals(selectedSection, ignoreCase = true) }
            view.visibility = if (match) View.VISIBLE else View.GONE
        }
    }

    // ── DATA LOADING ───────────────────────────────────────

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
        tableShapes.clear()
        tableStatuses.clear()
        joinedTableIdsByTableId.clear()
        tableDocMapUiNormsEnc.clear()
        tableReservationIds.clear()
        tableLayoutScreenRect.clear()
        layoutVisibleTableIds = emptyList()
        lastFloorVisualMergeKey = null
    }

    private fun detachAllTableViews() {
        tableViews.values.forEach { canvas.removeView(it) }
        tableViews.clear()
    }

    private fun loadTablesPreferred() {
        db.collection("tableLayouts").get()
            .addOnSuccessListener { layoutSnap ->
                if (layoutSnap.isEmpty) {
                    useTableLayouts = false
                    activeLayoutId = ""
                    layoutTablesListener?.remove(); layoutTablesListener = null
                    lastLayoutTablesSnapshot = null
                    layoutParentMetaListener?.remove(); layoutParentMetaListener = null
                    detachDineInReservationPreview()
                    loadTablesLegacy()
                    return@addOnSuccessListener
                }
                legacyTablesListener?.remove(); legacyTablesListener = null
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
                        applyLayoutTablesSnapshot(snap)
                    }
                attachLayoutParentMetaListener()
                attachDineInReservationPreviewListener()
            }
            .addOnFailureListener {
                useTableLayouts = false
                lastLayoutTablesSnapshot = null
                layoutParentMetaListener?.remove(); layoutParentMetaListener = null
                legacyTablesListener?.remove(); legacyTablesListener = null
                detachDineInReservationPreview()
                loadTablesLegacy()
            }
    }

    private fun attachLayoutParentMetaListener() {
        layoutParentMetaListener?.remove(); layoutParentMetaListener = null
        if (!useTableLayouts || activeLayoutId.isBlank()) return
        layoutParentMetaListener = db.collection("tableLayouts").document(activeLayoutId)
            .addSnapshotListener { layoutSnap, _ ->
                if (layoutSnap != null && layoutSnap.exists()) {
                    layoutGraceAfterSlotMs =
                        ReservationFirestoreHelper.graceAfterSlotMsFromLayoutSnapshot(layoutSnap)
                    layoutHoldStartsBeforeSlotMs =
                        ReservationFirestoreHelper.holdStartsBeforeSlotMsFromLayoutSnapshot(layoutSnap)
                }
                val cached = lastLayoutTablesSnapshot ?: return@addSnapshotListener
                ReservationFirestoreHelper.sweepExpiredHoldsForTableDocuments(db, cached, activeLayoutId)
                scheduleCoalescedOccupiedRefresh()
            }
    }

    private fun applyLayoutTablesSnapshot(snap: com.google.firebase.firestore.QuerySnapshot) {
        pendingLayoutTablesSnapshot = snap
        canvas.removeCallbacks(applyLayoutTablesRunnable)
        canvas.post(applyLayoutTablesRunnable)
    }

    /**
     * Places table views on the canvas. Tables that share `joinedTableIds` merge into one block
     * only when [shouldMergeGroup] (open order / visible reservation hold).
     */
    private fun placeLayoutTableVisuals() {
        rebuildDineInPreviewReservedTableIds()
        if (!useTableLayouts || layoutVisibleTableIds.isEmpty()) return
        detachAllTableViews()
        val cw = canvas.width.toFloat().coerceAtLeast(1f)
        val ch = canvas.height.toFloat().coerceAtLeast(1f)
        val skipIds = mergedNonRepresentativeIds()
        for (tableId in layoutVisibleTableIds) {
            if (tableId in skipIds) continue
            val rect = tableLayoutScreenRect[tableId] ?: continue
            val group = (joinedTableIdsByTableId[tableId] ?: listOf(tableId)).sorted()
            val merge = tableId == group.firstOrNull() && shouldMergeGroup(group)
            if (merge) {
                val totalW = group.sumOf {
                    TableShapeView.defaultMeasuredWidthPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE)
                }
                val totalH = group.maxOf {
                    TableShapeView.defaultMeasuredHeightPx(this, tableShapes[it] ?: TableShapeView.Shape.SQUARE)
                }
                val enc = tableDocMapUiNormsEnc[tableId]
                    ?: group.asSequence().mapNotNull { tableDocMapUiNormsEnc[it] }.firstOrNull().orEmpty()
                val norm = ReservationFirestoreHelper.parseReservationMapUiNormsV1(enc)[tableId]
                val avgX: Float
                val avgY: Float
                if (norm != null) {
                    avgX = norm.first * cw
                    avgY = norm.second * ch
                } else {
                    avgX = group.mapNotNull { tableLayoutScreenRect[it]?.x }
                        .let { if (it.isEmpty()) rect.x else it.average().toFloat() }
                    avgY = group.mapNotNull { tableLayoutScreenRect[it]?.y }
                        .let { if (it.isEmpty()) rect.y else it.average().toFloat() }
                }
                val label = group.joinToString(" + ") { tableNames[it] ?: "Table" }
                val seats = group.sumOf { tableSeats[it] ?: 4 }
                addTableToCanvas(tableId, label, seats, TableShapeView.Shape.RECTANGLE,
                    avgX, avgY, Pair(totalW, totalH))
            } else {
                val name = tableNames[tableId] ?: "Table"
                val seats = tableSeats[tableId] ?: 4
                val shape = tableShapes[tableId] ?: TableShapeView.Shape.SQUARE
                val enc = tableDocMapUiNormsEnc[tableId].orEmpty()
                val norm = ReservationFirestoreHelper.parseReservationMapUiNormsV1(enc)[tableId]
                val posX = if (norm != null) norm.first * cw else rect.x
                val posY = if (norm != null) norm.second * ch else rect.y
                addTableToCanvas(tableId, name, seats, shape, posX, posY, null)
            }
        }
    }

    private fun loadTablesLegacy() {
        lastLayoutTablesSnapshot = null
        layoutParentMetaListener?.remove(); layoutParentMetaListener = null
        detachDineInReservationPreview()
        legacyTablesListener?.remove()
        legacyTablesListener = db.collection("Tables")
            .whereEqualTo("active", true)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Toast.makeText(this, "Failed to load tables: ${err.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                waitingHandler.removeCallbacks(coalescedOccupiedRefreshRunnable)
                canvas.removeCallbacks(applyLayoutTablesRunnable)
                pendingLayoutTablesSnapshot = null
                canvas.post {
                    clearTableCanvas()
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
                        tableStatuses[doc.id] = doc.getString("status") ?: ""
                        tableReservationIds[doc.id] = doc.getString("reservationId")?.trim().orEmpty()
                        joinedTableIdsByTableId[doc.id] = TableJoinGroupFirestore.parseJoinedIds(doc, doc.id)

                        addTableToCanvas(doc.id, name, seats, shape, posX, posY, null)
                    }
                    filterTablesBySection()
                    applyOccupiedState()
                    ReservationFirestoreHelper.sweepExpiredHoldsForTableDocuments(db, snap, null)
                }
            }
    }

    // ── TABLE VIEWS ────────────────────────────────────────

    private fun addTableToCanvas(
        id: String, name: String, seats: Int,
        tableShape: TableShapeView.Shape, posX: Float, posY: Float,
        customSizePx: Pair<Int, Int>? = null,
    ) {
        val tableView = TableShapeView(this).apply {
            tableName = name
            seatCount = seats
            shape = tableShape
            forcedSizePx = customSizePx
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

        tableView.setOnClickListener {
            val groupIds = resolvedJoinedGroup(id)
            val info = groupIds.mapNotNull { occupiedTableData[it] }.firstOrNull()
            if (info != null) {
                navigateToExistingOrder(id, info.orderId)
            } else {
                val firestoreReserved =
                    groupIds.any { tableStatuses[it]?.trim()?.uppercase(Locale.US) == "RESERVED" }
                if (isUiReservedForGroup(groupIds)) {
                    if (firestoreReserved) {
                        showDineInReservedArrivalDialog(id, groupIds)
                    } else {
                        Toast.makeText(this, "This table is reserved.", Toast.LENGTH_LONG).show()
                    }
                    return@setOnClickListener
                }
                val docOnlyOccupied =
                    groupIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "OCCUPIED" }
                if (docOnlyOccupied) {
                    // Table doc still says OCCUPIED but there is no OPEN Dine-In order — common after a
                    // partial failure or manual DB edits; offer to clear so staff can seat again.
                    showClearStaleTableOccupancyDialog(groupIds)
                    return@setOnClickListener
                }
                showGuestCountDialog(id)
            }
        }

        tableView.setOnLongClickListener {
            val groupIds = resolvedJoinedGroup(id)
            val info = groupIds.mapNotNull { occupiedTableData[it] }.firstOrNull()
            when {
                info != null -> showDeoccupyDialog(id, info)
                groupIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "OCCUPIED" } ->
                    showClearStaleTableOccupancyDialog(groupIds)
            }
            true
        }

        tableViews[id] = tableView
    }

    // ── OCCUPIED STATE ─────────────────────────────────────

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
                        val created = doc.getTimestamp("createdAt")?.toDate() ?: doc.getDate("createdAt")
                        occupiedTableData[tid] = OccupiedTableInfo(
                            orderId = doc.id,
                            guestName = firstGuest,
                            guestCount = gCount,
                            itemsCount = iCount,
                            createdAt = created,
                        )
                    }
                }
                // Propagate occupied info to all members of joined groups
                val occCopy = occupiedTableData.toMap()
                for ((tid, info) in occCopy) {
                    val g = joinedTableIdsByTableId[tid] ?: listOf(tid)
                    for (x in g) {
                        if (x != tid && x !in occupiedTableData) occupiedTableData[x] = info
                    }
                }
                applyOccupiedState()
            }
    }

    private fun applyOccupiedState() {
        rebuildDineInPreviewReservedTableIds()
        if (useTableLayouts && layoutVisibleTableIds.isNotEmpty()) {
            val k = floorVisualMergeStateKey()
            if (lastFloorVisualMergeKey != null && k != lastFloorVisualMergeKey) {
                lastFloorVisualMergeKey = k
                placeLayoutTableVisuals()
                filterTablesBySection()
            } else {
                lastFloorVisualMergeKey = k
            }
        }
        applyTableShapeOccupiedReservedFlags()
    }

    private fun applyTableShapeOccupiedReservedFlags() {
        val now = System.currentTimeMillis()
        for ((id, view) in tableViews) {
            if (view is TableShapeView) {
                val groupIds = resolvedJoinedGroup(id)
                val info = groupIds.mapNotNull { occupiedTableData[it] }.firstOrNull()
                val docOccupied = groupIds.any { tableStatuses[it]?.trim()?.uppercase().orEmpty() == "OCCUPIED" }
                view.isOccupied = info != null || docOccupied
                view.isReserved = !view.isOccupied && isUiReservedForGroup(groupIds)
                if (info != null) {
                    view.detailPartyOf = if (info.guestCount > 0) {
                        resources.getQuantityString(R.plurals.table_shape_party_of, info.guestCount, info.guestCount)
                    } else {
                        ""
                    }
                    val elapsed = info.createdAt?.let { now - it.time } ?: 0L
                    view.isWaitingForOrder = info.itemsCount <= 0 && elapsed > waitingThresholdMs
                    if (view.isWaitingForOrder) {
                        view.detailStatusLabel = getString(R.string.table_status_waiting_for_order)
                        view.statusPill = TableShapeView.StatusPill.WAITING
                    } else {
                        view.detailStatusLabel = getString(R.string.table_status_order_open)
                        view.statusPill = TableShapeView.StatusPill.OPEN_ORDER
                    }
                    view.guestInfo = ""
                } else if (view.isOccupied && docOccupied) {
                    view.detailPartyOf = ""
                    view.detailStatusLabel = getString(R.string.table_status_occupied)
                    view.statusPill = TableShapeView.StatusPill.OCCUPIED
                    view.isWaitingForOrder = false
                    view.guestInfo = ""
                } else if (view.isReserved) {
                    view.detailPartyOf = ""
                    view.detailStatusLabel = getString(R.string.table_status_reserved)
                    view.statusPill = TableShapeView.StatusPill.RESERVED
                    view.isWaitingForOrder = false
                    view.guestInfo = ""
                } else {
                    view.detailPartyOf = ""
                    view.detailStatusLabel = ""
                    view.statusPill = TableShapeView.StatusPill.NONE
                    view.guestInfo = ""
                    view.isWaitingForOrder = false
                }
            }
        }
    }

    // ── NAVIGATION / DIALOGS ───────────────────────────────

    private fun reservationCustomerDisplayName(resDoc: DocumentSnapshot): String {
        val gn = resDoc.getString("guestName")?.trim().orEmpty()
        if (gn.isNotEmpty()) return gn
        return resDoc.getString("customerName")?.trim().orEmpty().ifEmpty { "—" }
    }

    private fun formatReservationTimeForDialog(resDoc: DocumentSnapshot): String {
        val ms = ReservationFirestoreHelper.reservationSlotMillisForExpiry(resDoc) ?: return "—"
        return SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(ms))
    }

    private fun loadReservationDocForReservedGroup(
        groupIds: List<String>,
        onLoaded: (DocumentSnapshot) -> Unit,
        onMissing: () -> Unit,
    ) {
        val direct = activeReservationDocForGroup(groupIds)
        if (direct != null) {
            onLoaded(direct)
            return
        }
        var rid: String? = null
        for (tid in groupIds) {
            val r = tableReservationIds[tid]?.trim().orEmpty()
            if (r.isNotEmpty()) {
                rid = r
                break
            }
        }
        if (rid.isNullOrEmpty()) {
            onMissing()
            return
        }
        db.collection(ReservationFirestoreHelper.COLLECTION).document(rid).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) onLoaded(snap) else onMissing()
            }
            .addOnFailureListener { onMissing() }
    }

    private fun showDineInReservedArrivalDialog(tappedTableId: String, groupIds: List<String>) {
        loadReservationDocForReservedGroup(
            groupIds,
            onLoaded = { resDoc ->
                if (!ReservationFirestoreHelper.isReservationActiveForList(resDoc)) {
                    Toast.makeText(this, getString(R.string.dine_in_reserved_error, "Not active"), Toast.LENGTH_LONG).show()
                    return@loadReservationDocForReservedGroup
                }
                val view = LayoutInflater.from(this).inflate(R.layout.dialog_dine_in_reserved_arrival, null, false)
                val txtDetails = view.findViewById<TextView>(R.id.txtReservedArrivalDetails)
                val party = when (val n = resDoc.get("partySize")) {
                    is Number -> n.toInt()
                    else -> 0
                }.coerceAtLeast(1)
                val name = reservationCustomerDisplayName(resDoc)
                val timeStr = formatReservationTimeForDialog(resDoc)
                txtDetails.text = buildString {
                    append(getString(R.string.dine_in_reserved_label_customer))
                    append(": ")
                    append(name)
                    append("\n")
                    append(getString(R.string.dine_in_reserved_label_party))
                    append(": ")
                    append(party)
                    append("\n")
                    append(getString(R.string.dine_in_reserved_label_time))
                    append(": ")
                    append(timeStr)
                }
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.dine_in_reserved_dialog_title)
                    .setView(view)
                    .create()
                val rid = resDoc.id
                fun setBusy(busy: Boolean) {
                    view.findViewById<TextView>(R.id.btnSeatGuest).isEnabled = !busy
                    view.findViewById<TextView>(R.id.btnCancelReservation).isEnabled = !busy
                    view.findViewById<TextView>(R.id.btnCloseReservedDialog).isEnabled = !busy
                }
                view.findViewById<TextView>(R.id.btnCloseReservedDialog).setOnClickListener { dialog.dismiss() }
                view.findViewById<TextView>(R.id.btnSeatGuest).setOnClickListener {
                    setBusy(true)
                    ReservationFirestoreHelper.seatReservedDineInFloorInTransaction(
                        db = db,
                        reservationId = rid,
                        employeeName = employeeName,
                        batchId = batchId,
                        onSuccess = { orderId ->
                            dialog.dismiss()
                            val primary = resDoc.getString("tableId")?.trim().orEmpty().ifEmpty { tappedTableId }
                            navigateToExistingOrder(primary, orderId)
                        },
                        onFailure = { e ->
                            setBusy(false)
                            Toast.makeText(
                                this,
                                getString(R.string.dine_in_reserved_error, e.message ?: ""),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
                view.findViewById<TextView>(R.id.btnCancelReservation).setOnClickListener {
                    setBusy(true)
                    ReservationFirestoreHelper.cancelReservedDineInOnFloorInTransaction(
                        db = db,
                        reservationId = rid,
                        onSuccess = {
                            dialog.dismiss()
                            Toast.makeText(this, R.string.dine_in_reserved_cancelled, Toast.LENGTH_SHORT).show()
                            applyOccupiedState()
                        },
                        onFailure = { e ->
                            setBusy(false)
                            Toast.makeText(
                                this,
                                getString(R.string.dine_in_reserved_error, e.message ?: ""),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
                dialog.show()
            },
            onMissing = {
                Toast.makeText(this, getString(R.string.dine_in_reserved_error, "No reservation"), Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun navigateToExistingOrder(tableId: String, orderId: String) {
        val sectionName = tableSections[tableId]?.takeIf { it.isNotBlank() } ?: ""
        val groupIds = resolvedJoinedGroup(tableId)
        val displayName = if (groupIds.size > 1) {
            groupIds.joinToString(" · ") { tableNames[it] ?: "Table" }
        } else {
            tableNames[tableId] ?: "Table"
        }
        val intent = Intent(this, MenuActivity::class.java)
        intent.putExtra("batchId", batchId)
        intent.putExtra("employeeName", employeeName)
        intent.putExtra("orderType", "DINE_IN")
        intent.putExtra("tableId", tableId)
        intent.putExtra("tableLayoutId", if (useTableLayouts) activeLayoutId else "")
        intent.putExtra("tableName", displayName)
        intent.putExtra("sectionId", sectionName)
        intent.putExtra("sectionName", sectionName)
        intent.putExtra("ORDER_ID", orderId)
        startActivity(intent)
        finish()
    }

    private fun navigateToMenu(tableId: String, tableName: String, guestCount: Int, guestNames: List<String>) {
        val sectionName = tableSections[tableId]?.takeIf { it.isNotBlank() } ?: ""
        val sectionId = sectionName
        val joined = operationalPartyTableIds(tableId)
        val joinedForOrder = if (joined.size > 1) joined else null

        if (intent.getBooleanExtra("SELECT_TABLE_ONLY", false)) {
            val result = Intent().apply {
                putExtra("tableId", tableId)
                putExtra("tableName", tableName)
                putExtra("sectionId", sectionId)
                putExtra("sectionName", sectionName)
                putExtra("tableLayoutId", if (useTableLayouts) activeLayoutId else "")
                putExtra("guestCount", guestCount)
                putStringArrayListExtra("guestNames", ArrayList(guestNames))
                if (!joinedForOrder.isNullOrEmpty()) {
                    putStringArrayListExtra("joinedTableIds", ArrayList(joinedForOrder))
                }
            }
            setResult(RESULT_OK, result)
            finish()
            return
        }

        val layoutIdForOrder = if (useTableLayouts && activeLayoutId.isNotBlank()) activeLayoutId else null
        orderEngine.ensureOrder(
            currentOrderId = null,
            employeeName = employeeName,
            orderType = "DINE_IN",
            tableId = tableId,
            tableLayoutId = layoutIdForOrder,
            joinedTableIds = joinedForOrder,
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
                intent.putExtra("tableLayoutId", layoutIdForOrder ?: "")
                intent.putExtra("tableName", tableName)
                intent.putExtra("sectionId", sectionId)
                intent.putExtra("sectionName", sectionName)
                intent.putExtra("guestCount", guestCount)
                intent.putStringArrayListExtra("guestNames", ArrayList(guestNames))
                intent.putExtra("ORDER_ID", orderId)
                if (!joinedForOrder.isNullOrEmpty()) {
                    intent.putStringArrayListExtra("joinedTableIds", ArrayList(joinedForOrder))
                }
                startActivity(intent)
                finish()
            },
            onFailure = { e ->
                Toast.makeText(this, "Failed to create order: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun showGuestCountDialog(tableId: String) {
        val joined = operationalPartyTableIds(tableId)
        val name = if (joined.size > 1) {
            joined.joinToString(" + ") { tableNames[it] ?: "Table" }
        } else {
            tableNames[tableId] ?: "Table"
        }
        val maxSeats = joined.sumOf { tableSeats[it] ?: 4 }

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
            this, ReceiptEmailKeypadDialog.KeypadVariant.GUEST_NAME,
            keyMinHeightDp = 30f, keyMarginDp = 1.5f,
            keyTextSizeSp = 13.5f, keyTextSizeCompactSp = 11f,
            panelPaddingHorizontalDp = 5f, panelPaddingVerticalDp = 6f,
        ) { token -> activeNameEdit?.let { ReceiptEmailKeypadDialog.insertAtCaret(it, token) } }
        guestNameKeypadHost.addView(keypad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun wireGuestNameField(editText: EditText) {
            editText.inputType = InputType.TYPE_NULL
            editText.showSoftInputOnFocus = false
            editText.isCursorVisible = true
            editText.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    activeNameEdit = v as EditText
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    scrollFieldIntoView(guestDialogContentScroll, v as EditText)
                }
            }
            editText.setOnClickListener {
                activeNameEdit = editText
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
                scrollFieldIntoView(guestDialogContentScroll, editText)
            }
        }

        fun updateNameInputs() {
            val cur = nameInputs.size
            if (guestCount > cur) {
                for (i in cur until guestCount) {
                    val et = EditText(this).apply {
                        hint = "Guest ${i + 1} name"
                        setPadding(32, 24, 32, 24)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 12 }
                    }
                    wireGuestNameField(et)
                    guestNamesContainer.addView(et)
                    nameInputs.add(et)
                }
            } else if (guestCount < cur) {
                for (i in guestCount until cur) {
                    val removed = nameInputs.removeAt(nameInputs.lastIndex)
                    guestNamesContainer.removeView(removed)
                    if (activeNameEdit === removed) {
                        activeNameEdit = nameInputs.lastOrNull()
                        activeNameEdit?.requestFocus()
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
        btnMinus.setOnClickListener { if (guestCount > 1) { guestCount--; updateDisplay() } }
        btnPlus.setOnClickListener { if (guestCount < maxSeats) { guestCount++; updateDisplay() } }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        fun confirmStartOrder() {
            val guestNames = nameInputs.map { it.text.toString().trim() }
            imm.hideSoftInputFromWindow(dialogView.windowToken, 0)
            dialog.dismiss()
            navigateToMenu(tableId, name, guestCount, guestNames)
        }

        btnGuestDialogCancel.setOnClickListener { imm.hideSoftInputFromWindow(dialogView.windowToken, 0); dialog.dismiss() }
        btnGuestDialogStart.setOnClickListener { confirmStartOrder() }

        dialog.setOnShowListener {
            dialog.window?.let { win ->
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                val h = resources.displayMetrics.heightPixels
                win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (h * 0.92).toInt())
            }
            nameInputs.firstOrNull()?.let { first ->
                activeNameEdit = first
                first.post { first.requestFocus(); imm.hideSoftInputFromWindow(first.windowToken, 0) }
            }
        }
        dialog.show()
    }

    /**
     * Clears [status] on table doc(s) when they still read OCCUPIED but no OPEN Dine-In order is linked
     * in [occupiedTableData] (stale occupancy).
     */
    private fun showClearStaleTableOccupancyDialog(groupIds: List<String>) {
        val tableName = if (groupIds.size > 1) {
            groupIds.joinToString(" · ") { tableNames[it] ?: "Table" }
        } else {
            tableNames[groupIds.firstOrNull().orEmpty()] ?: "Table"
        }
        val layoutId = if (useTableLayouts && activeLayoutId.isNotBlank()) activeLayoutId else null
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.table_clear_stale_occupied_title, tableName))
            .setMessage(R.string.table_clear_stale_occupied_message)
            .setPositiveButton(R.string.table_clear_stale_occupied_confirm) { _, _ ->
                TableFirestoreHelper.clearDineInJoinedTablesStatus(db, groupIds, layoutId)
                Toast.makeText(this, R.string.table_clear_stale_occupied_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeoccupyDialog(tableId: String, info: OccupiedTableInfo) {
        val groupIds = resolvedJoinedGroup(tableId)
        val tableName = if (groupIds.size > 1) {
            groupIds.joinToString(" · ") { tableNames[it] ?: "Table" }
        } else {
            tableNames[tableId] ?: "Table"
        }
        AlertDialog.Builder(this)
            .setTitle("Free Up $tableName?")
            .setMessage("This will set the table to available and delete the open order.\n\nAre you sure?")
            .setPositiveButton("Yes, Free Table") { _, _ -> deleteOrderAndFreeTable(info.orderId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteOrderAndFreeTable(orderId: String) {
        val orderRef = db.collection("Orders").document(orderId)
        orderRef.get().addOnSuccessListener { orderSnap ->
            val tid = orderSnap.getString("tableId")?.trim().orEmpty()
            val layoutId = orderSnap.getString("tableLayoutId")
            @Suppress("UNCHECKED_CAST")
            val jr = orderSnap.get("joinedTableIds") as? List<*>
            val joined = jr?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }?.distinct()
            val toClear = when {
                !joined.isNullOrEmpty() && joined.size > 1 -> joined
                tid.isNotEmpty() -> listOf(tid)
                else -> emptyList()
            }

            orderRef.collection("items").get().addOnSuccessListener { itemsSnap ->
                val batch = db.batch()
                for (item in itemsSnap.documents) batch.delete(item.reference)
                batch.delete(orderRef)
                batch.commit().addOnSuccessListener {
                    if (toClear.isNotEmpty()) {
                        TableFirestoreHelper.clearDineInJoinedTablesStatus(db, toClear, layoutId)
                    }
                    Toast.makeText(this, "Table freed and order deleted", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener { e ->
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun scrollFieldIntoView(scroll: ScrollView, field: View) {
        scroll.post {
            var y = 0; var v: View? = field
            while (v != null) { val p = v.parent; if (p === scroll) break; y += v.top; v = p as? View }
            val slack = (resources.displayMetrics.density * 12f).toInt()
            val bottomVis = scroll.scrollY + scroll.height - scroll.paddingBottom
            val fieldBot = y + field.height
            if (fieldBot > bottomVis - slack) scroll.smoothScrollTo(0, fieldBot - scroll.height + scroll.paddingBottom + slack)
            else { val topVis = scroll.scrollY + scroll.paddingTop; if (y < topVis + slack) scroll.smoothScrollTo(0, (y - slack).coerceAtLeast(0)) }
        }
    }
}
