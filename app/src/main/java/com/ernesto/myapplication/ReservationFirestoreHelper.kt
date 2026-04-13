package com.ernesto.myapplication

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object ReservationFirestoreHelper {

    const val COLLECTION = "Reservations"

    /** Links the booking to [CustomerFirestoreHelper.COLLECTION]; required on new reservations. */
    const val FIELD_CUSTOMER_ID = "customerId"

    /**
     * Optional UI anchor(s) for [ReservationMappingActivity]: top-left of each dragged table /
     * merged block, normalized to canvas size at booking (0–1). Same encoding as the picker
     * result extra: `tableId|normX|normY` segments separated by `;`.
     */
    const val FIELD_RESERVATION_MAP_UI_NORMS_V1 = "reservationMapUiNormsV1"

    /** Parses [FIELD_RESERVATION_MAP_UI_NORMS_V1] / picker encoding: `id|nx|ny` segments separated by `;`. */
    fun parseReservationMapUiNormsV1(raw: String?): Map<String, Pair<Float, Float>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, Pair<Float, Float>>()
        for (segment in raw.split(";")) {
            val s = segment.trim()
            if (s.isEmpty()) continue
            val parts = s.split("|")
            if (parts.size != 3) continue
            val id = parts[0].trim()
            if (id.isEmpty()) continue
            val nx = parts[1].trim().toFloatOrNull() ?: continue
            val ny = parts[2].trim().toFloatOrNull() ?: continue
            out[id] = Pair(nx, ny)
        }
        return out
    }

    private const val TABLE_LAYOUTS_COLLECTION = "tableLayouts"

    /** Matches web [MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES] — one week, minutes. */
    private const val MAX_RESERVATION_GRACE_MINUTES = 7 * 24 * 60

    private val terminalReservationStatuses = setOf("EXPIRED", "CANCELLED", "COMPLETED", "SEATED")

    /** Active reservations (shown in the list); missing [status] counts as active (legacy docs). */
    fun isReservationActiveForList(doc: DocumentSnapshot): Boolean {
        val st = doc.getString("status")?.trim()?.uppercase().orEmpty()
        return st.isEmpty() || st !in terminalReservationStatuses
    }

    /**
     * **Reservations → Closed** tab: only guests who were actually seated from Dine-In
     * ([status] == [SEATED]). Expired, cancelled, completed-without-seat, etc. are excluded.
     */
    fun isReservationClosedTabEligible(doc: DocumentSnapshot): Boolean {
        if (!doc.exists()) return false
        val st = doc.getString("status")?.trim()?.uppercase().orEmpty()
        return st == "SEATED"
    }

    /** Profile / lists: ACTIVE, COMPLETED (incl. seated/expired), or CANCELLED. */
    fun reservationStatusForCustomerProfile(doc: DocumentSnapshot): String {
        val st = doc.getString("status")?.trim()?.uppercase().orEmpty()
        return when (st) {
            "CANCELLED" -> "CANCELLED"
            "COMPLETED", "SEATED", "EXPIRED" -> "COMPLETED"
            else -> "ACTIVE"
        }
    }

    private fun clearReservationHoldOnTableUpdate(): Map<String, Any> = mapOf(
        "status" to FieldValue.delete(),
        "reservationId" to FieldValue.delete(),
        "joinedTableIds" to FieldValue.delete(),
        FIELD_RESERVATION_MAP_UI_NORMS_V1 to FieldValue.delete(),
        "updatedAt" to Date(),
    )

    private const val STALE_RESERVATION_WITHOUT_SLOT_MS = 48L * 3600L * 1000L

    fun graceAfterSlotMsFromLayoutSnapshot(layoutSnap: DocumentSnapshot): Long {
        if (!layoutSnap.exists()) return 0L
        val raw = layoutSnap.get("reservationGraceAfterSlotMinutes")
        val v = (raw as? Number)?.toLong() ?: 0L
        val clamped = v.coerceIn(0L, MAX_RESERVATION_GRACE_MINUTES.toLong())
        return clamped * 60_000L
    }

    /**
     * Dine-In floor only: treat tables as reserved starting this many minutes **before**
     * [reservationTime]. Field on layout doc: `reservationHoldStartsMinutesBeforeSlot`.
     * Same minute cap as [graceAfterSlotMsFromLayoutSnapshot].
     */
    fun holdStartsBeforeSlotMsFromLayoutSnapshot(layoutSnap: DocumentSnapshot): Long {
        if (!layoutSnap.exists()) return 0L
        val raw = layoutSnap.get("reservationHoldStartsMinutesBeforeSlot")
        val v = (raw as? Number)?.toLong() ?: 0L
        val clamped = v.coerceIn(0L, MAX_RESERVATION_GRACE_MINUTES.toLong())
        return clamped * 60_000L
    }

    private fun readGraceMsForReservation(
        tx: Transaction,
        db: FirebaseFirestore,
        resSnap: DocumentSnapshot,
        fallbackLayoutId: String?,
    ): Long {
        val fromRes = resSnap.getString("tableLayoutId")?.trim().orEmpty()
        val lid = fromRes.ifEmpty { fallbackLayoutId?.trim().orEmpty() }
        if (lid.isEmpty()) return 0L
        val layoutSnap = tx.get(db.collection(TABLE_LAYOUTS_COLLECTION).document(lid))
        return graceAfterSlotMsFromLayoutSnapshot(layoutSnap)
    }

    private fun createdAtMillis(doc: DocumentSnapshot): Long? =
        doc.getTimestamp("createdAt")?.toDate()?.time
            ?: doc.getDate("createdAt")?.time

    /**
     * Best-effort millis for when the reservation slot was / is (for legacy docs: [whenText] only).
     */
    fun reservationSlotMillisForExpiry(doc: DocumentSnapshot): Long? {
        coerceReservationTimeMillis(doc)?.let { return it }
        parseWhenTextToSlotMillis(doc)?.let { return it }
        return null
    }

    private fun coerceReservationTimeMillis(doc: DocumentSnapshot): Long? {
        doc.getTimestamp("reservationTime")?.toDate()?.time?.let { return it }
        doc.getDate("reservationTime")?.time?.let { return it }
        when (val raw = doc.get("reservationTime")) {
            is Timestamp -> return raw.toDate().time
            is Date -> return raw.time
            is Number -> {
                val v = raw.toLong()
                return if (v > 1_000_000_000_000L) v else v * 1000L
            }
        }
        return null
    }

    private val dayNameToCalendarDay = listOf(
        "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY,
        "tuesday" to Calendar.TUESDAY,
        "saturday" to Calendar.SATURDAY,
        "friday" to Calendar.FRIDAY,
        "monday" to Calendar.MONDAY,
        "sunday" to Calendar.SUNDAY,
    ).sortedByDescending { it.first.length }

    private val timeInWhenText: Pattern =
        Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", Pattern.CASE_INSENSITIVE)

    private fun parseWhenTextToSlotMillis(doc: DocumentSnapshot): Long? {
        val whenText = doc.getString("whenText")?.trim().orEmpty()
        if (whenText.isEmpty()) return null
        val createdMs = createdAtMillis(doc) ?: return null

        val fmts = arrayOf(
            "EEE, MMM d, yyyy h:mm a",
            "EEEE, MMM d, yyyy h:mm a",
            "MMM d, yyyy h:mm a",
            "MMM d, yyyy, h:mm a",
        )
        for (pattern in fmts) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                val pos = ParsePosition(0)
                val d = sdf.parse(whenText, pos)
                val end = whenText.trimEnd().length
                if (d != null && pos.index >= end) return d.time
            } catch (_: Exception) {
            }
        }

        val lower = whenText.lowercase(Locale.US)
        val calDay = dayNameToCalendarDay.firstOrNull { (name, _) -> lower.contains(name) }?.second
            ?: return null

        val m = timeInWhenText.matcher(whenText)
        if (!m.find()) return null
        var hour = m.group(1)?.toIntOrNull() ?: return null
        val minute = m.group(2)?.toIntOrNull() ?: 0
        var ap = m.group(3)?.lowercase(Locale.US)?.replace(".", "")
        if (ap.isNullOrEmpty()) {
            if (lower.contains("pm") && hour in 1..11) ap = "pm"
            else if (lower.contains("am") && hour == 12) ap = "am"
        }
        when (ap) {
            "pm" -> if (hour in 1..11) hour += 12
            "am" -> if (hour == 12) hour = 0
        }

        val anchor = Calendar.getInstance().apply { timeInMillis = createdMs }
        val slot = Calendar.getInstance().apply {
            timeInMillis = createdMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (slot.get(Calendar.DAY_OF_WEEK) != calDay) {
            slot.add(Calendar.DAY_OF_MONTH, 1)
        }
        slot.set(Calendar.HOUR_OF_DAY, hour)
        slot.set(Calendar.MINUTE, minute)
        if (!slot.after(anchor)) {
            slot.add(Calendar.WEEK_OF_YEAR, 1)
        }
        return slot.timeInMillis
    }

    /**
     * True if the reservation hold should be released: [graceAfterSlotMs] extends the slot
     * (table stays RESERVED until slot + grace). Legacy no-slot docs ignore grace.
     */
    fun isReservationHoldExpired(resSnap: DocumentSnapshot, graceAfterSlotMs: Long = 0L): Boolean {
        val slot = reservationSlotMillisForExpiry(resSnap)
        val now = System.currentTimeMillis()
        if (slot != null) return now > slot + graceAfterSlotMs
        val created = createdAtMillis(resSnap) ?: return false
        return now - created > STALE_RESERVATION_WITHOUT_SLOT_MS
    }

    /**
     * Cheap pre-check before running a release transaction: slot time has passed (grace applied
     * inside the transaction) or stale legacy doc without a parseable slot.
     */
    fun mightTriggerExpiredReservationRelease(doc: DocumentSnapshot): Boolean {
        val st = doc.getString("status")?.trim()?.uppercase().orEmpty()
        if (st in terminalReservationStatuses) return false
        val slot = reservationSlotMillisForExpiry(doc)
        if (slot != null) return slot < System.currentTimeMillis()
        val created = createdAtMillis(doc) ?: return false
        return System.currentTimeMillis() - created > STALE_RESERVATION_WITHOUT_SLOT_MS
    }

    /**
     * If the table is RESERVED for an expired (or missing / already-ended) reservation,
     * clears the hold and marks the reservation [EXPIRED]. Returns a blocking dine-in
     * status after the sweep: [OCCUPIED], [RESERVED] (still active), or "" (free).
     *
     * Firestore requires **all [tx.get] reads before any writes** — reads are batched first.
     */
    fun sweepExpiredReservationHoldInTransaction(
        tx: Transaction,
        db: FirebaseFirestore,
        tableRef: DocumentReference,
        tableSnap: DocumentSnapshot,
        /** When the reservation doc has no [tableLayoutId], use this (e.g. sweep from layout tables). */
        fallbackLayoutIdForGrace: String? = null,
    ): String {
        val st = tableSnap.getString("status")?.trim()?.uppercase().orEmpty()
        if (st == "OCCUPIED") return "OCCUPIED"
        if (st != "RESERVED") return ""

        val rid = tableSnap.getString("reservationId")?.trim().orEmpty()
        val resRef = if (rid.isNotEmpty()) db.collection(COLLECTION).document(rid) else null
        val resSnap = resRef?.let { tx.get(it) }

        var graceMs = 0L
        var resStatus = ""
        var joinedPairs: List<Pair<DocumentReference, DocumentSnapshot>> = emptyList()
        if (rid.isNotEmpty() && resSnap != null && resSnap.exists()) {
            resStatus = resSnap.getString("status")?.trim()?.uppercase().orEmpty()
            if (resStatus !in terminalReservationStatuses) {
                graceMs = readGraceMsForReservation(tx, db, resSnap, fallbackLayoutIdForGrace)
                if (isReservationHoldExpired(resSnap, graceMs)) {
                    val primaryTid = resSnap.getString("tableId")?.trim().orEmpty().ifEmpty { tableSnap.id }
                    val layoutFromRes = resSnap.getString("tableLayoutId")
                    joinedPairs = TableJoinGroupFirestore.parseJoinedIds(resSnap, primaryTid).map { tid ->
                        val tRef = TableFirestoreHelper.tableRef(db, tid, layoutFromRes)
                        tRef to tx.get(tRef)
                    }
                }
            }
        }

        if (rid.isEmpty()) {
            tx.update(tableRef, clearReservationHoldOnTableUpdate())
            return ""
        }
        if (resSnap == null || !resSnap.exists()) {
            tx.update(tableRef, clearReservationHoldOnTableUpdate())
            return ""
        }
        if (resStatus in terminalReservationStatuses) {
            tx.update(tableRef, clearReservationHoldOnTableUpdate())
            return ""
        }
        if (!isReservationHoldExpired(resSnap, graceMs)) {
            return "RESERVED"
        }
        for ((tRef, tSnap) in joinedPairs) {
            val tst = tSnap.getString("status")?.trim()?.uppercase().orEmpty()
            val rOn = tSnap.getString("reservationId")?.trim().orEmpty()
            if (tst == "RESERVED" && rOn == rid) {
                tx.update(tRef, clearReservationHoldOnTableUpdate())
            }
        }
        tx.update(
            resRef!!,
            mapOf(
                "status" to "EXPIRED",
                "updatedAt" to Date(),
            ),
        )
        return ""
    }

    /**
     * Clears expired / orphan [RESERVED] holds for [allTableIds] in one read phase + write phase.
     * Throws if any table is [OCCUPIED] or still actively [RESERVED] after the sweep.
     */
    private fun sweepExpiredHoldsAndAssertTablesFreeForCreate(
        tx: Transaction,
        db: FirebaseFirestore,
        allTableIds: List<String>,
        tableLayoutId: String?,
    ) {
        val layoutForSweep = tableLayoutId?.trim()?.takeIf { it.isNotEmpty() }
        val tableRefs = allTableIds.map { TableFirestoreHelper.tableRef(db, it, tableLayoutId) }
        val tableSnaps = tableRefs.map { tx.get(it) }
        for (snap in tableSnaps) {
            if (!snap.exists()) {
                throw FirebaseFirestoreException(
                    "Table not found",
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )
            }
            val st = snap.getString("status")?.trim()?.uppercase().orEmpty()
            if (st == "OCCUPIED") {
                throw FirebaseFirestoreException(
                    "Table is not available",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                )
            }
        }

        val rids = tableSnaps.mapNotNull { snap ->
            if (snap.getString("status")?.trim()?.uppercase().orEmpty() != "RESERVED") return@mapNotNull null
            snap.getString("reservationId")?.trim().orEmpty().takeIf { it.isNotEmpty() }
        }.distinct()

        val resSnapByRid = rids.associateWith { rid -> tx.get(db.collection(COLLECTION).document(rid)) }

        val layoutIdSet = mutableSetOf<String>()
        for (rid in rids) {
            val rs = resSnapByRid[rid] ?: continue
            if (!rs.exists()) continue
            val rst = rs.getString("status")?.trim()?.uppercase().orEmpty()
            if (rst in terminalReservationStatuses) continue
            val lid = rs.getString("tableLayoutId")?.trim().orEmpty().ifEmpty { layoutForSweep ?: "" }
            if (lid.isNotEmpty()) layoutIdSet.add(lid)
        }
        if (layoutForSweep != null) layoutIdSet.add(layoutForSweep)

        val layoutSnapById = layoutIdSet.associateWith { lid ->
            tx.get(db.collection(TABLE_LAYOUTS_COLLECTION).document(lid))
        }

        fun graceMsForReservation(rs: DocumentSnapshot): Long {
            val from = rs.getString("tableLayoutId")?.trim().orEmpty()
            val lid = from.ifEmpty { layoutForSweep ?: "" }
            if (lid.isEmpty()) return 0L
            val ls = layoutSnapById[lid] ?: return 0L
            return graceAfterSlotMsFromLayoutSnapshot(ls)
        }

        val expiredRids = rids.filter { rid ->
            val rs = resSnapByRid[rid] ?: return@filter false
            rs.exists() &&
                rs.getString("status")?.trim()?.uppercase().orEmpty() !in terminalReservationStatuses &&
                isReservationHoldExpired(rs, graceMsForReservation(rs))
        }.toSet()

        val joinedSnapById = tableRefs.mapIndexed { i, ref -> ref.id to tableSnaps[i] }.toMap(mutableMapOf())
        for (rid in expiredRids) {
            val rs = resSnapByRid[rid]!!
            val primaryTid = rs.getString("tableId")?.trim().orEmpty().ifEmpty { allTableIds.first() }
            val layoutFromRes = rs.getString("tableLayoutId")
            for (tid in TableJoinGroupFirestore.parseJoinedIds(rs, primaryTid)) {
                if (tid in joinedSnapById) continue
                val tRef = TableFirestoreHelper.tableRef(db, tid, layoutFromRes)
                joinedSnapById[tid] = tx.get(tRef)
            }
        }

        val clearedIds = mutableSetOf<String>()
        val now = Date()

        for (rid in expiredRids) {
            val rs = resSnapByRid[rid]!!
            val primaryTid = rs.getString("tableId")?.trim().orEmpty().ifEmpty { allTableIds.first() }
            val layoutFromRes = rs.getString("tableLayoutId")
            for (tid in TableJoinGroupFirestore.parseJoinedIds(rs, primaryTid)) {
                val tSnap = joinedSnapById[tid] ?: continue
                val tst = tSnap.getString("status")?.trim()?.uppercase().orEmpty()
                val rOn = tSnap.getString("reservationId")?.trim().orEmpty()
                if (tst == "RESERVED" && rOn == rid) {
                    val tRef = TableFirestoreHelper.tableRef(db, tid, layoutFromRes)
                    tx.update(tRef, clearReservationHoldOnTableUpdate())
                    clearedIds.add(tid)
                }
            }
            tx.update(
                db.collection(COLLECTION).document(rid),
                mapOf(
                    "status" to "EXPIRED",
                    "updatedAt" to now,
                ),
            )
        }

        for (i in tableRefs.indices) {
            val ref = tableRefs[i]
            if (ref.id in clearedIds) continue
            val snap = tableSnaps[i]
            if (snap.getString("status")?.trim()?.uppercase().orEmpty() != "RESERVED") continue
            val rid = snap.getString("reservationId")?.trim().orEmpty()
            if (rid.isEmpty()) {
                tx.update(ref, clearReservationHoldOnTableUpdate())
                clearedIds.add(ref.id)
                continue
            }
            if (rid in expiredRids) continue
            val rs = resSnapByRid[rid] ?: continue
            if (!rs.exists() || rs.getString("status")?.trim()?.uppercase().orEmpty() in terminalReservationStatuses) {
                tx.update(ref, clearReservationHoldOnTableUpdate())
                clearedIds.add(ref.id)
            }
        }

        for (i in tableRefs.indices) {
            val ref = tableRefs[i]
            if (ref.id in clearedIds) continue
            if (tableSnaps[i].getString("status")?.trim()?.uppercase().orEmpty() == "RESERVED") {
                throw FirebaseFirestoreException(
                    "Table is not available",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                )
            }
        }
    }

    /**
     * For each table doc in a snapshot that looks RESERVED, runs a transaction to drop
     * the hold when the linked reservation time has passed.
     */
    fun sweepExpiredHoldsForTableDocuments(
        db: FirebaseFirestore,
        snapshot: QuerySnapshot,
        tableLayoutId: String?,
    ) {
        for (doc in snapshot.documents) {
            if (doc.getString("status")?.trim()?.uppercase() != "RESERVED") continue
            val tableId = doc.id
            val fallbackLayout = tableLayoutId?.trim()?.takeIf { it.isNotEmpty() }
            db.runTransaction { tx ->
                val tableRef = TableFirestoreHelper.tableRef(db, tableId, tableLayoutId)
                val fresh = tx.get(tableRef)
                sweepExpiredReservationHoldInTransaction(tx, db, tableRef, fresh, fallbackLayout)
                null
            }
        }
    }

    /** @see mightTriggerExpiredReservationRelease */
    fun shouldReleaseHoldForReservationDoc(doc: DocumentSnapshot): Boolean =
        mightTriggerExpiredReservationRelease(doc)

    /**
     * When [reservationTime] is in the past, clears RESERVED on the linked table (if it
     * still points at this reservation) and sets reservation status to [EXPIRED].
     */
    fun releaseHoldForExpiredReservationIfNeeded(db: FirebaseFirestore, reservationId: String) {
        val rid = reservationId.trim()
        if (rid.isEmpty()) return
        db.runTransaction { tx ->
            val resRef = db.collection(COLLECTION).document(rid)
            val resSnap = tx.get(resRef)
            if (!resSnap.exists()) return@runTransaction null

            val resStatus = resSnap.getString("status")?.trim()?.uppercase().orEmpty()
            if (resStatus in terminalReservationStatuses) return@runTransaction null

            val graceMs = readGraceMsForReservation(tx, db, resSnap, null)
            if (!isReservationHoldExpired(resSnap, graceMs)) return@runTransaction null

            val primaryTable = resSnap.getString("tableId")?.trim().orEmpty()
            val layoutId = resSnap.getString("tableLayoutId")
            if (primaryTable.isNotEmpty()) {
                for (tidClear in TableJoinGroupFirestore.parseJoinedIds(resSnap, primaryTable)) {
                    val tableRef = TableFirestoreHelper.tableRef(db, tidClear, layoutId)
                    val tableSnap = tx.get(tableRef)
                    val tst = tableSnap.getString("status")?.trim()?.uppercase().orEmpty()
                    val ridOnTable = tableSnap.getString("reservationId")?.trim().orEmpty()
                    if (tst == "RESERVED" && ridOnTable == rid) {
                        tx.update(tableRef, clearReservationHoldOnTableUpdate())
                    }
                }
            }

            tx.update(
                resRef,
                mapOf(
                    "status" to "EXPIRED",
                    "updatedAt" to Date(),
                ),
            )
            null
        }
    }

    /**
     * Creates reservation + updates table(s) in one transaction.
     * Pre-checks for an open DINE_IN order on each table (reduces races vs. status-only).
     *
     * @param joinedTableIdsForReservation Additional table doc ids in the same join group (same shape); primary is [tableId].
     */
    fun createReservationWithTable(
        db: FirebaseFirestore,
        tableId: String,
        tableLayoutId: String?,
        tableName: String,
        guestName: String,
        phone: String,
        partySize: Int,
        notes: String,
        reservationTime: Timestamp,
        employeeName: String,
        customerId: String,
        joinedTableIdsForReservation: List<String> = emptyList(),
        reservationMapUiNormsV1: String? = null,
        onSuccess: (reservationId: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val tid = tableId.trim()
        if (tid.isEmpty()) {
            onFailure(IllegalArgumentException("Table is required"))
            return
        }
        val extras = joinedTableIdsForReservation.map { it.trim() }.filter { it.isNotEmpty() && it != tid }
        val allTableIds = (listOf(tid) + extras).distinct().sorted()
        val openTasks = allTableIds.map { tableLoopId ->
            db.collection("Orders")
                .whereEqualTo("tableId", tableLoopId)
                .whereEqualTo("status", "OPEN")
                .whereEqualTo("orderType", "DINE_IN")
                .limit(1)
                .get()
        }
        Tasks.whenAllComplete(openTasks)
            .addOnSuccessListener {
                for (t in openTasks) {
                    if (!t.isSuccessful) {
                        onFailure(t.exception ?: Exception("Failed to check table availability"))
                        return@addOnSuccessListener
                    }
                    val qs = t.result
                    if (qs != null && !qs.isEmpty) {
                        onFailure(Exception("Table is not available"))
                        return@addOnSuccessListener
                    }
                }
                val resRef = db.collection(COLLECTION).document()
                db.runTransaction { tx ->
                    sweepExpiredHoldsAndAssertTablesFreeForCreate(tx, db, allTableIds, tableLayoutId)
                    val cid = customerId.trim()
                    if (cid.isEmpty()) {
                        throw FirebaseFirestoreException(
                            "customerId is required",
                            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                        )
                    }
                    val reservation = hashMapOf<String, Any>(
                        "tableId" to tid,
                        "tableName" to tableName,
                        "guestName" to guestName,
                        "phone" to phone,
                        "partySize" to partySize,
                        "notes" to notes,
                        "reservationTime" to reservationTime,
                        "employeeName" to employeeName,
                        FIELD_CUSTOMER_ID to cid,
                        "createdAt" to Date(),
                        "updatedAt" to Date(),
                    )
                    val lid = tableLayoutId?.trim().orEmpty()
                    if (lid.isNotEmpty()) reservation["tableLayoutId"] = lid
                    if (allTableIds.size > 1) {
                        reservation["joinedTableIds"] = allTableIds
                    }
                    val mapNorms = reservationMapUiNormsV1?.trim().orEmpty()
                    if (mapNorms.isNotEmpty()) {
                        reservation[FIELD_RESERVATION_MAP_UI_NORMS_V1] = mapNorms
                    }
                    tx.set(resRef, reservation)
                    val now = Date()
                    for (tableLoopId in allTableIds) {
                        val tr = TableFirestoreHelper.tableRef(db, tableLoopId, tableLayoutId)
                        val tableUpdate = mutableMapOf<String, Any>(
                            "status" to "RESERVED",
                            "reservationId" to resRef.id,
                            "updatedAt" to now,
                        )
                        if (allTableIds.size > 1) {
                            tableUpdate["joinedTableIds"] = allTableIds
                        }
                        if (mapNorms.isNotEmpty()) {
                            tableUpdate[FIELD_RESERVATION_MAP_UI_NORMS_V1] = mapNorms
                        }
                        tx.update(tr, tableUpdate)
                    }
                    resRef.id
                }.addOnSuccessListener { id -> onSuccess(id) }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /**
     * User-initiated cancel: sets reservation [status] to [CANCELLED] and frees every table
     * that still points at this reservation ([reservationId] match). Tables in [RESERVED]
     * get a full hold clear (including [joinedTableIds]). Other statuses only have the
     * reservation linkage removed so an active dine-in order is not wiped.
     */
    fun cancelReservation(
        db: FirebaseFirestore,
        reservationId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val rid = reservationId.trim()
        if (rid.isEmpty()) {
            onFailure(IllegalArgumentException("Invalid reservation"))
            return
        }
        db.runTransaction { tx ->
            val resRef = db.collection(COLLECTION).document(rid)
            val resSnap = tx.get(resRef)
            if (!resSnap.exists()) {
                throw FirebaseFirestoreException(
                    "Reservation not found",
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )
            }
            val resStatus = resSnap.getString("status")?.trim()?.uppercase().orEmpty()
            if (resStatus in terminalReservationStatuses) {
                throw FirebaseFirestoreException(
                    "Reservation is already ended",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                )
            }
            val primary = resSnap.getString("tableId")?.trim().orEmpty()
            val layoutId = resSnap.getString("tableLayoutId")
            // Firestore: all reads before any writes — read every table doc first, then update.
            val tableReads = mutableListOf<Pair<DocumentReference, DocumentSnapshot>>()
            if (primary.isNotEmpty()) {
                for (tid in TableJoinGroupFirestore.parseJoinedIds(resSnap, primary)) {
                    val tRef = TableFirestoreHelper.tableRef(db, tid, layoutId)
                    tableReads.add(tRef to tx.get(tRef))
                }
            }
            for ((tRef, tSnap) in tableReads) {
                val rOn = tSnap.getString("reservationId")?.trim().orEmpty()
                if (rOn != rid) continue
                val tst = tSnap.getString("status")?.trim()?.uppercase().orEmpty()
                if (tst == "RESERVED") {
                    tx.update(tRef, clearReservationHoldOnTableUpdate())
                } else {
                    tx.update(
                        tRef,
                        mapOf(
                            "reservationId" to FieldValue.delete(),
                            "joinedTableIds" to FieldValue.delete(),
                            FIELD_RESERVATION_MAP_UI_NORMS_V1 to FieldValue.delete(),
                            "updatedAt" to Date(),
                        ),
                    )
                }
            }
            tx.update(
                resRef,
                mapOf(
                    "status" to "CANCELLED",
                    "updatedAt" to Date(),
                ),
            )
            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    private fun reservationPartySize(resSnap: DocumentSnapshot): Int {
        val n = resSnap.get("partySize")
        return when (n) {
            is Number -> n.toInt().coerceAtLeast(1)
            else -> 1
        }
    }

    private fun tableIdsForReservationSnapshot(resSnap: DocumentSnapshot): List<String> {
        val primary = resSnap.getString("tableId")?.trim().orEmpty()
        if (primary.isEmpty()) return emptyList()
        return TableJoinGroupFirestore.parseJoinedIds(resSnap, primary)
    }

    /**
     * Dine-In floor staff: seat the party in **one** Firestore transaction (order number + order doc +
     * reservation [SEATED] + tables [OCCUPIED] with [reservationId] cleared).
     * Open-order checks run immediately before the transaction (queries cannot run inside a transaction).
     */
    fun seatReservedDineInFloorInTransaction(
        db: FirebaseFirestore,
        reservationId: String,
        employeeName: String,
        batchId: String?,
        onSuccess: (orderId: String) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val rid = reservationId.trim()
        if (rid.isEmpty()) {
            onFailure(IllegalArgumentException("Invalid reservation"))
            return
        }
        val resRef = db.collection(COLLECTION).document(rid)
        resRef.get()
            .addOnSuccessListener { resSnap ->
                if (!resSnap.exists()) {
                    onFailure(Exception("Reservation not found"))
                    return@addOnSuccessListener
                }
                val st = resSnap.getString("status")?.trim()?.uppercase().orEmpty()
                if (st.isNotEmpty() && st in terminalReservationStatuses) {
                    onFailure(Exception("Reservation is no longer active"))
                    return@addOnSuccessListener
                }
                val tableIds = tableIdsForReservationSnapshot(resSnap)
                if (tableIds.isEmpty()) {
                    onFailure(Exception("Reservation has no table"))
                    return@addOnSuccessListener
                }
                val layoutId = resSnap.getString("tableLayoutId")?.trim().orEmpty()
                val openTasks = tableIds.map { tid ->
                    db.collection("Orders")
                        .whereEqualTo("tableId", tid)
                        .whereEqualTo("status", "OPEN")
                        .whereEqualTo("orderType", "DINE_IN")
                        .limit(1)
                        .get()
                }
                Tasks.whenAllComplete(openTasks)
                    .addOnSuccessListener {
                        for (t in openTasks) {
                            if (!t.isSuccessful) {
                                onFailure(t.exception ?: Exception("Failed to verify table"))
                                return@addOnSuccessListener
                            }
                            val qs = t.result
                            if (qs != null && !qs.isEmpty) {
                                onFailure(Exception("A table already has an open order"))
                                return@addOnSuccessListener
                            }
                        }
                        db.runTransaction { tx ->
                            val resLive = tx.get(resRef)
                            if (!resLive.exists()) {
                                throw FirebaseFirestoreException(
                                    "Reservation not found",
                                    FirebaseFirestoreException.Code.NOT_FOUND,
                                )
                            }
                            val stLive = resLive.getString("status")?.trim()?.uppercase().orEmpty()
                            if (stLive.isNotEmpty() && stLive in terminalReservationStatuses) {
                                throw FirebaseFirestoreException(
                                    "Reservation is no longer active",
                                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                                )
                            }
                            val primary = resLive.getString("tableId")?.trim().orEmpty()
                            if (primary.isEmpty()) {
                                throw FirebaseFirestoreException(
                                    "Invalid reservation",
                                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                                )
                            }
                            val ids = TableJoinGroupFirestore.parseJoinedIds(resLive, primary)
                            val lid = resLive.getString("tableLayoutId")?.trim().orEmpty()
                            val lidForRef = lid.takeUnless { it.isEmpty() }
                            val tableReads = ids.map { tid ->
                                val tRef = TableFirestoreHelper.tableRef(db, tid, lidForRef)
                                tRef to tx.get(tRef)
                            }
                            for ((tRef, tSnap) in tableReads) {
                                if (!tSnap.exists()) {
                                    throw FirebaseFirestoreException(
                                        "Table missing",
                                        FirebaseFirestoreException.Code.NOT_FOUND,
                                    )
                                }
                                val tst = tSnap.getString("status")?.trim()?.uppercase().orEmpty()
                                val rOn = tSnap.getString("reservationId")?.trim().orEmpty()
                                if (tst != "RESERVED" || rOn != rid) {
                                    throw FirebaseFirestoreException(
                                        "Table is not reserved for this booking",
                                        FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                                    )
                                }
                            }
                            val counterRef = db.collection("Counters").document("orderNumber")
                            val cSnap = tx.get(counterRef)
                            val nextNum = (cSnap.getLong("current") ?: 0L) + 1L
                            tx.set(counterRef, hashMapOf("current" to nextNum))
                            val orderRef = db.collection("Orders").document()
                            val orderId = orderRef.id
                            val now = Date()
                            val party = reservationPartySize(resLive)
                            val guestName = resLive.getString("guestName")?.trim().orEmpty()
                                .ifEmpty { resLive.getString("customerName")?.trim().orEmpty() }
                            val guestNames = if (guestName.isNotEmpty()) listOf(guestName) else emptyList()
                            val tableName = resLive.getString("tableName")?.trim().orEmpty()
                            val section = tableReads.firstOrNull()?.second?.getString("section")?.trim().orEmpty()
                            val orderMap = hashMapOf<String, Any>(
                                "orderNumber" to nextNum,
                                "employeeName" to employeeName.trim(),
                                "status" to "OPEN",
                                "createdAt" to now,
                                "updatedAt" to now,
                                "totalInCents" to 0L,
                                "totalPaidInCents" to 0L,
                                "remainingInCents" to 0L,
                                "orderType" to "DINE_IN",
                                "itemsCount" to 0L,
                                "tableId" to primary,
                                "guestCount" to party,
                            )
                            if (lid.isNotEmpty()) orderMap["tableLayoutId"] = lid
                            if (ids.size > 1) orderMap["joinedTableIds"] = ids
                            if (tableName.isNotEmpty()) orderMap["tableName"] = tableName
                            if (section.isNotEmpty()) {
                                orderMap["sectionId"] = section
                                orderMap["sectionName"] = section
                            }
                            if (guestNames.isNotEmpty()) orderMap["guestNames"] = guestNames
                            if (guestName.isNotEmpty()) orderMap["customerName"] = guestName
                            val phone = resLive.getString("phone")?.trim().orEmpty()
                            if (phone.isNotEmpty()) orderMap["customerPhone"] = phone
                            val cid = resLive.getString(FIELD_CUSTOMER_ID)?.trim().orEmpty()
                            if (cid.isNotEmpty()) orderMap[FIELD_CUSTOMER_ID] = cid
                            val email = resLive.getString("customerEmail")?.trim().orEmpty()
                            if (email.isNotEmpty()) orderMap["customerEmail"] = email
                            val bid = batchId?.trim().orEmpty()
                            if (bid.isNotEmpty()) orderMap["batchId"] = bid
                            tx.set(orderRef, orderMap)
                            tx.update(
                                resRef,
                                mapOf(
                                    "status" to "SEATED",
                                    "seatedOrderId" to orderId,
                                    "updatedAt" to now,
                                ),
                            )
                            for ((tRef, _) in tableReads) {
                                tx.update(
                                    tRef,
                                    mapOf(
                                        "status" to "OCCUPIED",
                                        "dineInOrderId" to orderId,
                                        "reservationId" to FieldValue.delete(),
                                        "updatedAt" to now,
                                    ),
                                )
                            }
                            orderId
                        }.addOnSuccessListener { oid -> onSuccess(oid) }
                            .addOnFailureListener { e -> onFailure(e) }
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /**
     * Dine-In floor staff: cancel hold and mark linked tables [AVAILABLE] in **one** transaction.
     */
    fun cancelReservedDineInOnFloorInTransaction(
        db: FirebaseFirestore,
        reservationId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val rid = reservationId.trim()
        if (rid.isEmpty()) {
            onFailure(IllegalArgumentException("Invalid reservation"))
            return
        }
        db.runTransaction { tx ->
            val resRef = db.collection(COLLECTION).document(rid)
            val resSnap = tx.get(resRef)
            if (!resSnap.exists()) {
                throw FirebaseFirestoreException(
                    "Reservation not found",
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )
            }
            val resStatus = resSnap.getString("status")?.trim()?.uppercase().orEmpty()
            if (resStatus.isNotEmpty() && resStatus in terminalReservationStatuses) {
                throw FirebaseFirestoreException(
                    "Reservation is already ended",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                )
            }
            val primary = resSnap.getString("tableId")?.trim().orEmpty()
            val layoutId = resSnap.getString("tableLayoutId")
            val tableReads = mutableListOf<Pair<DocumentReference, DocumentSnapshot>>()
            if (primary.isNotEmpty()) {
                for (tid in TableJoinGroupFirestore.parseJoinedIds(resSnap, primary)) {
                    val tRef = TableFirestoreHelper.tableRef(db, tid, layoutId)
                    tableReads.add(tRef to tx.get(tRef))
                }
            }
            val now = Date()
            for ((tRef, tSnap) in tableReads) {
                val rOn = tSnap.getString("reservationId")?.trim().orEmpty()
                if (rOn != rid) continue
                val tst = tSnap.getString("status")?.trim()?.uppercase().orEmpty()
                if (tst == "RESERVED") {
                    tx.update(
                        tRef,
                        mapOf(
                            "status" to "AVAILABLE",
                            "reservationId" to FieldValue.delete(),
                            "joinedTableIds" to FieldValue.delete(),
                            FIELD_RESERVATION_MAP_UI_NORMS_V1 to FieldValue.delete(),
                            "dineInOrderId" to FieldValue.delete(),
                            "updatedAt" to now,
                        ),
                    )
                } else {
                    tx.update(
                        tRef,
                        mapOf(
                            "reservationId" to FieldValue.delete(),
                            "joinedTableIds" to FieldValue.delete(),
                            FIELD_RESERVATION_MAP_UI_NORMS_V1 to FieldValue.delete(),
                            "updatedAt" to now,
                        ),
                    )
                }
            }
            tx.update(
                resRef,
                mapOf(
                    "status" to "CANCELLED",
                    "updatedAt" to now,
                ),
            )
            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }
}
