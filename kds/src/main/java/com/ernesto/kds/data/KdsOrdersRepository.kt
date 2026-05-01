package com.ernesto.kds.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Reads the same **Orders** collection and **items** subcollection as the POS app.
 * Kitchen workflow updates **per-line `kdsStatus`** (and optional `kdsStartedAt`) so split tablets
 * (food vs drinks) do not share one order-level `kitchenStatus`. POS status stays **OPEN** until close.
 *
 * KDS visibility follows **Settings/printing** (same as kitchen LAN printers): trigger mode
 * ([KdsPrintingConfig] data class) and item filter (all items vs by routing label / Printers collection).
 */
class KdsOrdersRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    /**
     * Real-time stream of kitchen orders with line items, sorted by `createdAt` ascending.
     * Respects printing trigger (send vs payment vs first event), item filter (all vs label),
     * excludes kitchenStatus **READY**, and includes **COMPLETED** tickets still in the kitchen queue.
     *
     * @param kdsDeviceDocId When non-blank, tickets whose [FIELD_KDS_CARD_CLAIMS] map assigns this
     * card to another device are hidden (first KDS to press Start keeps the ticket).
     */
    fun observeKitchenOrders(kdsDeviceDocId: String = ""): Flow<List<Order>> = callbackFlow {
        val filterDeviceId = kdsDeviceDocId.trim()
        val lock = Any()
        var printingConfig = KdsPrintingConfig.DEFAULT
        var kitchenLabelNorms = emptySet<String>()
        var lastOrdersSnapshot: QuerySnapshot? = null

        fun emitKitchenOrders() {
            lateinit var snapshot: QuerySnapshot
            lateinit var config: KdsPrintingConfig
            lateinit var labelNorms: Set<String>
            synchronized(lock) {
                val cur = lastOrdersSnapshot ?: return
                snapshot = cur
                config = printingConfig
                labelNorms = kitchenLabelNorms
            }

            if (snapshot.documents.isEmpty()) {
                trySend(emptyList())
                return
            }

            val docs = snapshot.documents.filter { doc: DocumentSnapshot ->
                if (!kdsEligiblePosStatus(doc)) return@filter false
                val kitchen = doc.getString("kitchenStatus")?.trim()?.uppercase().orEmpty()
                if (kitchen == "READY") return@filter false
                if (doc.getBoolean("awaitingStaffConfirmOrder") == true &&
                    doc.getString("orderSource")?.trim() == "online_ordering"
                ) {
                    return@filter false
                }
                val isOnline = isOnlineOrder(doc)
                if (!isOnline && !orderReleasedToKitchen(doc, config.printTriggerMode)) return@filter false
                true
            }
            if (docs.isEmpty()) {
                trySend(emptyList())
                return
            }

            @Suppress("UNCHECKED_CAST")
            val tasks: List<Task<QuerySnapshot>> = docs.map { doc: DocumentSnapshot ->
                doc.reference.collection(COLLECTION_ITEMS).get()
            }
            Tasks.whenAllComplete(tasks)
                .addOnSuccessListener {
                    val built = mutableListOf<Order>()
                    for (i in docs.indices) {
                        val task = tasks[i]
                        if (!task.isSuccessful) continue
                        val qs = task.result ?: continue
                        built.addAll(
                            parsePosOrder(docs[i], qs.documents, config, labelNorms, filterDeviceId),
                        )
                    }
                    built.sortWith(
                        compareBy<Order> { it.createdAt?.time ?: Long.MAX_VALUE }
                            .thenBy { it.id },
                    )
                    trySend(built)
                }
                .addOnFailureListener {
                    trySend(emptyList())
                }
        }

        val regPrinting = KdsPrintingConfig.printingDocument(db)
            .addSnapshotListener { snap: DocumentSnapshot?, _: FirebaseFirestoreException? ->
                synchronized(lock) {
                    printingConfig = KdsPrintingConfig.fromSnapshot(snap)
                }
                emitKitchenOrders()
            }

        val regPrinters = db.collection(KdsKitchenRoutingLabels.collectionPath())
            .addSnapshotListener { snap: QuerySnapshot?, _: FirebaseFirestoreException? ->
                synchronized(lock) {
                    kitchenLabelNorms = KdsKitchenRoutingLabels.normalizedLabelKeys(snap)
                }
                emitKitchenOrders()
            }

        val regOrders = db.collection(COLLECTION_ORDERS)
            .addSnapshotListener { snapshot: QuerySnapshot?, error: FirebaseFirestoreException? ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                synchronized(lock) {
                    lastOrdersSnapshot = snapshot
                }
                emitKitchenOrders()
            }

        awaitClose {
            regPrinting.remove()
            regPrinters.remove()
            regOrders.remove()
        }
    }

    /** Dashboard tile color keys (dine_in, to_go, bar) from Settings/dashboard. */
    fun observeDashboardColorKeys(): Flow<Map<String, String>> = callbackFlow {
        val listener = db.collection("Settings").document("dashboard")
            .addSnapshotListener { snapshot: DocumentSnapshot?, _: FirebaseFirestoreException? ->
                val raw = snapshot?.get("modules")
                trySend(parseDashboardColorKeys(raw))
            }
        awaitClose { listener.remove() }
    }

    /** KDS layout prefs from Settings/kds (web dashboard “Display settings”). */
    fun observeKdsDisplaySettings(): Flow<KdsDisplaySettings> = callbackFlow {
        val listener = db.collection("Settings").document("kds")
            .addSnapshotListener { snapshot: DocumentSnapshot?, _: FirebaseFirestoreException? ->
                if (snapshot == null || !snapshot.exists()) {
                    trySend(KdsDisplaySettings())
                    return@addSnapshotListener
                }
                val cols = when ((snapshot.get("gridColumns") as? Number)?.toLong()) {
                    3L -> 3
                    null -> 3
                    else -> 2
                }
                val rawYellow = (snapshot.get("ticketYellowAfterMinutes") as? Number)?.toInt()
                val rawRed = (snapshot.get("ticketRedAfterMinutes") as? Number)?.toInt()
                val yellow = rawYellow ?: KdsDisplaySettings.DEFAULT_TICKET_YELLOW_AFTER_MINUTES
                val red = rawRed ?: KdsDisplaySettings.DEFAULT_TICKET_RED_AFTER_MINUTES
                val (yNorm, rNorm) = KdsDisplaySettings.normalized(yellow, red)
                trySend(
                    KdsDisplaySettings(
                        showTimers = !snapshot.contains("showTimers") || snapshot.getBoolean("showTimers") == true,
                        orderTypeColorsEnabled = !snapshot.contains("orderTypeColorsEnabled") ||
                            snapshot.getBoolean("orderTypeColorsEnabled") == true,
                        gridColumns = cols,
                        ticketYellowAfterMinutes = yNorm,
                        ticketRedAfterMinutes = rNorm,
                    ),
                )
            }
        awaitClose { listener.remove() }
    }

    /** KDS device IDs that should show all online orders regardless of menu assignment. */
    fun observeOnlineRoutingKdsDeviceIds(): Flow<Set<String>> = callbackFlow {
        val listener = db.collection("Settings").document("onlineOrdering")
            .addSnapshotListener { snap: DocumentSnapshot?, _: FirebaseFirestoreException? ->
                if (snap == null || !snap.exists()) {
                    trySend(emptySet())
                    return@addSnapshotListener
                }
                val raw = snap.get("onlineRoutingKdsDeviceIds")
                val ids = when (raw) {
                    is List<*> -> raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }.toSet()
                    else -> emptySet()
                }
                trySend(ids)
            }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    /**
     * `assignedCategoryIds` and `assignedItemIds` on the paired KDS device document.
     * Both empty = no filter (all kitchen orders).
     */
    fun observeKdsDeviceMenuAssignment(deviceDocId: String): Flow<KdsMenuAssignment> = callbackFlow {
        if (deviceDocId.isBlank()) {
            trySend(KdsMenuAssignment())
            awaitClose { }
            return@callbackFlow
        }
        val listener = db.collection(KdsDevicePresence.KDS_DEVICES_COLLECTION)
            .document(deviceDocId)
            .addSnapshotListener { snap: DocumentSnapshot?, _: FirebaseFirestoreException? ->
                fun parseIdList(field: String): Set<String> {
                    val raw = snap?.get(field) ?: return emptySet()
                    return when (raw) {
                        is List<*> -> raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }.toSet()
                        else -> emptySet()
                    }
                }
                trySend(
                    KdsMenuAssignment(
                        categoryIds = parseIdList("assignedCategoryIds"),
                        itemIds = parseIdList("assignedItemIds"),
                    ),
                )
            }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    /**
     * Each menu item id → category placements (`categoryIds` if set, else `categoryId`).
     * Matches dashboard “placement” for multi-category items.
     */
    fun observeMenuItemCategoryPlacements(): Flow<Map<String, Set<String>>> = callbackFlow {
        val listener = db.collection(COLLECTION_MENU_ITEMS)
            .addSnapshotListener { snapshot: QuerySnapshot?, _: FirebaseFirestoreException? ->
                val map = LinkedHashMap<String, Set<String>>()
                val documents: List<DocumentSnapshot> = snapshot?.documents ?: emptyList()
                var i = 0
                while (i < documents.size) {
                    val doc = documents[i]
                    i++
                    val catId = doc.getString("categoryId")?.trim().orEmpty()
                    val rawList = doc.get("categoryIds") as? List<*>
                    val catIds = rawList?.mapNotNull { it as? String }
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    val placements = when {
                        catIds.isNotEmpty() -> catIds.toSet()
                        catId.isNotEmpty() -> setOf(catId)
                        else -> emptySet()
                    }
                    map[doc.id] = placements
                }
                trySend(map)
            }
        awaitClose { listener.remove() }
    }

    /**
     * Per-device UI text at `kds_devices/{deviceId}/settings/ui` (dashboard collection name).
     */
    fun observeKdsTextSettings(deviceDocId: String): Flow<KdsTextSettings> = callbackFlow {
        val id = deviceDocId.trim()
        if (id.isEmpty()) {
            trySend(KdsTextSettings.Default)
            awaitClose { }
            return@callbackFlow
        }
        val ref = db.collection(KdsDevicePresence.KDS_DEVICES_COLLECTION)
            .document(id)
            .collection(SUBCOLLECTION_SETTINGS)
            .document(DOCUMENT_UI_SETTINGS)
        val reg = ref.addSnapshotListener { snap: DocumentSnapshot?, _: FirebaseFirestoreException? ->
            trySend(parseKdsTextSettings(snap?.data))
        }
        awaitClose { reg.remove() }
    }.distinctUntilChanged()

    suspend fun saveKdsTextSettings(deviceDocId: String, settings: KdsTextSettings) {
        val id = deviceDocId.trim()
        if (id.isEmpty()) return
        val ref = db.collection(KdsDevicePresence.KDS_DEVICES_COLLECTION)
            .document(id)
            .collection(SUBCOLLECTION_SETTINGS)
            .document(DOCUMENT_UI_SETTINGS)
        ref.set(settings.coerce().toFirestoreMap()).await()
    }

    suspend fun updateOrderStatus(
        orderId: String,
        kitchenStatus: String,
        lineDocIds: List<String> = emptyList(),
        /** For READY: line doc id → quantity that is now fully covered (from KDS line model). */
        readyCoverQtyByLineId: Map<String, Long> = emptyMap(),
        /** KDS device doc id (`kds_devices/{id}`) claiming this card on Start; cleared on Ready when [kdsClaimCardKey] is set. */
        kdsClaimDeviceId: String? = null,
        /** [Order.cardKey] — used with Start to claim, with Ready to release claim. */
        kdsClaimCardKey: String? = null,
    ) {
        val upper = kitchenStatus.uppercase(Locale.US)
        val orderRef = db.collection(COLLECTION_ORDERS).document(orderId)
        val ids = lineDocIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        val claimDevice = kdsClaimDeviceId?.trim().orEmpty()
        val claimCard = kdsClaimCardKey?.trim().orEmpty()
        Log.d(
            TAG,
            "updateOrderStatus: orderId=$orderId upper=$upper lines=${ids.size} " +
                "claimDevice='$claimDevice' claimCard='$claimCard'",
        )
        runCatching {
            db.runTransaction { tx ->
                val orderSnap = tx.get(orderRef)
                if (!orderSnap.exists()) return@runTransaction null

                val lineSnaps = ids.map { trimmed ->
                    trimmed to tx.get(orderRef.collection(COLLECTION_ITEMS).document(trimmed))
                }

                val orderUpdates = mutableMapOf<String, Any>()

                if (upper == "PREPARING" && claimDevice.isNotEmpty() && claimCard.isNotEmpty()) {
                    val claims = parseCardClaims(orderSnap).toMutableMap()
                    val existing = claims[claimCard]?.trim().orEmpty()
                    Log.d(TAG, "PREPARING claim check: existing='$existing' device='$claimDevice'")
                    if (existing.isNotEmpty() && existing != claimDevice) {
                        throw FirebaseFirestoreException(
                            "CLAIMED_BY_OTHER_KDS",
                            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
                            null,
                        )
                    }
                    if (existing != claimDevice) {
                        claims[claimCard] = claimDevice
                        orderUpdates[FIELD_KDS_CARD_CLAIMS] = claims
                        Log.d(TAG, "Writing claim: $claims")
                    }
                }

                if (upper == "READY" && claimCard.isNotEmpty()) {
                    val claims = parseCardClaims(orderSnap).toMutableMap()
                    claims.remove(claimCard)
                    if (claims.isEmpty()) {
                        orderUpdates[FIELD_KDS_CARD_CLAIMS] = FieldValue.delete()
                    } else {
                        orderUpdates[FIELD_KDS_CARD_CLAIMS] = claims
                    }
                }

                for ((trimmed, snap) in lineSnaps) {
                    if (!snap.exists()) continue
                    @Suppress("UNCHECKED_CAST")
                    val batchRaw = snap.get(FIELD_KDS_SEND_BATCHES) as? List<*>
                    val updates = if (!batchRaw.isNullOrEmpty()) {
                        buildItemUpdatesWithSendBatches(batchRaw, upper)
                    } else {
                        buildItemUpdatesLegacy(upper, readyCoverQtyByLineId[trimmed] ?: 0L)
                    }
                    tx.update(snap.reference, updates)
                }

                orderUpdates[FIELD_UPDATED_AT] = FieldValue.serverTimestamp()
                tx.update(orderRef, orderUpdates)
                null
            }.await()
        }.onFailure { e ->
            Log.w(TAG, "updateOrderStatus failed for $orderId ($upper)", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "KdsOrdersRepo"
        private const val SUBCOLLECTION_SETTINGS = "settings"
        private const val DOCUMENT_UI_SETTINGS = "ui"

        private const val COLLECTION_ORDERS = "Orders"
        private const val COLLECTION_ITEMS = "items"
        private const val COLLECTION_MENU_ITEMS = "MenuItems"
        private const val FIELD_POS_STATUS = "status"
        private const val FIELD_KITCHEN_STATUS = "kitchenStatus"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_KITCHEN_STARTED_AT = "kitchenStartedAt"
        private const val FIELD_LAST_KITCHEN_SENT_AT = "lastKitchenSentAt"
        private const val FIELD_KITCHEN_CHITS_PRINTED_AT = "kitchenChitsPrintedAt"
        /** Map: [Order.cardKey] → `kds_devices` document id. First KDS to Start owns the ticket for other tablets. */
        private const val FIELD_KDS_CARD_CLAIMS = "kdsCardClaims"
        /** Mirrors POS `OrderLineKdsStatus.FIELD` — per-line KDS / kitchen workflow. */
        private const val FIELD_KDS_LINE_STATUS = "kdsStatus"
        private const val FIELD_KDS_STARTED_AT = "kdsStartedAt"
        /** Units on this line last marked READY on KDS; used with batch `quantity` for follow-up sends. */
        private const val FIELD_KDS_READY_COVERS_QTY = "kdsReadyCoversQty"

        /** POS `OrderLineKdsStatus.FIELD_KDS_SEND_BATCHES` — per kitchen send on the line. */
        private const val FIELD_KDS_SEND_BATCHES = "kdsSendBatches"

        private const val FIELD_KDS_BATCH_STARTED_AT = "batchStartedAt"

        private const val SUBFIELD_SENT_AT = "sentAt"
        private const val SUBFIELD_BATCH_QUANTITY = "quantity"
        private const val SUBFIELD_BATCH_ID = "batchId"

        private fun normKdsStatus(s: Any?): String =
            s?.toString()?.trim()?.uppercase(Locale.US).orEmpty()

        private fun batchSentAtMillis(m: Map<*, *>): Long {
            return when (val s = m[SUBFIELD_SENT_AT]) {
                is Timestamp -> s.toDate().time
                is Date -> s.time
                else -> 0L
            }
        }

        private fun buildItemUpdatesLegacy(upper: String, readyQ: Long): Map<String, Any> {
            val lineUpdates = mutableMapOf<String, Any>(
                FIELD_KDS_LINE_STATUS to upper,
            )
            if (upper == "PREPARING") {
                lineUpdates[FIELD_KDS_STARTED_AT] = Timestamp.now()
            }
            if (upper == "READY" && readyQ > 0L) {
                lineUpdates[FIELD_KDS_READY_COVERS_QTY] = readyQ
            }
            return lineUpdates
        }

        @Suppress("UNCHECKED_CAST")
        private fun buildItemUpdatesWithSendBatches(
            batchRaw: List<*>,
            upper: String,
        ): Map<String, Any> {
            val working = batchRaw.mapNotNull { el ->
                val m = el as? Map<*, *> ?: return@mapNotNull null
                LinkedHashMap<String, Any>().apply {
                    for ((k, v) in m) {
                        if (k is String && v != null) {
                            put(k, v)
                        }
                    }
                }
            }.sortedWith(compareBy { batchSentAtMillis(it) }).toMutableList()

            for (m in working) {
                if (normKdsStatus(m[FIELD_KDS_LINE_STATUS]).isEmpty()) {
                    m[FIELD_KDS_LINE_STATUS] = "SENT"
                }
            }

            when (upper) {
                "PREPARING" -> {
                    // Allow the next SENT batch to go PREPARING while an earlier batch is still PREPARING
                    // so follow-up kitchen sends can be STARTed on their own KDS card.
                    val idx = working.indexOfFirst {
                        val t = normKdsStatus(it[FIELD_KDS_LINE_STATUS])
                        t.isEmpty() || t == "SENT"
                    }
                    if (idx >= 0) {
                        working[idx][FIELD_KDS_LINE_STATUS] = "PREPARING"
                        working[idx][FIELD_KDS_BATCH_STARTED_AT] = Timestamp.now()
                    }
                }
                "READY" -> {
                    val idxP = working.indexOfFirst { normKdsStatus(it[FIELD_KDS_LINE_STATUS]) == "PREPARING" }
                    val target = if (idxP >= 0) {
                        idxP
                    } else {
                        working.indexOfFirst { normKdsStatus(it[FIELD_KDS_LINE_STATUS]) == "SENT" }
                    }
                    if (target >= 0) {
                        working[target][FIELD_KDS_LINE_STATUS] = "READY"
                        working[target].remove(FIELD_KDS_BATCH_STARTED_AT)
                    }
                }
            }

            val readyCoverSum = working.sumOf { m ->
                if (normKdsStatus(m[FIELD_KDS_LINE_STATUS]) == "READY") {
                    (m[SUBFIELD_BATCH_QUANTITY] as? Number)?.toLong() ?: 0L
                } else {
                    0L
                }
            }

            val aggregate = when {
                working.isNotEmpty() && working.all { normKdsStatus(it[FIELD_KDS_LINE_STATUS]) == "READY" } -> "READY"
                working.any { normKdsStatus(it[FIELD_KDS_LINE_STATUS]) == "PREPARING" } -> "PREPARING"
                else -> "SENT"
            }

            val out = mutableMapOf(
                FIELD_KDS_SEND_BATCHES to working,
                FIELD_KDS_LINE_STATUS to aggregate,
                FIELD_KDS_READY_COVERS_QTY to readyCoverSum,
            )
            val prepTimes = working.mapNotNull { m ->
                if (normKdsStatus(m[FIELD_KDS_LINE_STATUS]) == "PREPARING") {
                    m[FIELD_KDS_BATCH_STARTED_AT] as? Timestamp
                } else {
                    null
                }
            }
            if (prepTimes.isEmpty()) {
                out[FIELD_KDS_STARTED_AT] = FieldValue.delete()
            } else {
                out[FIELD_KDS_STARTED_AT] = prepTimes.minByOrNull { it.seconds } ?: Timestamp.now()
            }
            return out
        }

        internal fun isOnlineOrder(doc: DocumentSnapshot): Boolean {
            val orderSource = doc.getString("orderSource")?.trim().orEmpty()
            if (orderSource.isNotBlank()) return true
            val rawType = doc.getString("orderType")?.trim().orEmpty()
            return rawType.equals("UBER_EATS", ignoreCase = true) ||
                rawType.equals("ONLINE_PICKUP", ignoreCase = true)
        }

        internal fun orderReleasedToKitchen(doc: DocumentSnapshot, triggerMode: String): Boolean {
            val lastSend = doc.getTimestamp(FIELD_LAST_KITCHEN_SENT_AT) != null
            val chits = doc.getTimestamp(FIELD_KITCHEN_CHITS_PRINTED_AT) != null
            return when (triggerMode) {
                KdsPrintingConfig.ON_SEND -> lastSend
                KdsPrintingConfig.ON_PAYMENT -> chits
                else -> lastSend || chits
            }
        }

        private fun parseCardClaims(doc: DocumentSnapshot): Map<String, String> {
            @Suppress("UNCHECKED_CAST")
            val raw = doc.get(FIELD_KDS_CARD_CLAIMS) as? Map<*, *> ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((k, v) in raw) {
                val ks = k?.toString()?.trim().orEmpty()
                val vs = v?.toString()?.trim().orEmpty()
                if (ks.isNotEmpty() && vs.isNotEmpty()) {
                    out[ks] = vs
                }
            }
            return out
        }

        internal fun kdsEligiblePosStatus(doc: DocumentSnapshot): Boolean {
            val st = doc.getString(FIELD_POS_STATUS)?.trim()?.uppercase().orEmpty()
            if (st == "OPEN") return true
            if (st == "COMPLETED") {
                val k = doc.getString(FIELD_KITCHEN_STATUS)?.trim()?.uppercase().orEmpty()
                return k != "READY"
            }
            return false
        }

        fun parsePosOrder(
            doc: DocumentSnapshot,
            lineDocs: List<DocumentSnapshot>,
            printing: KdsPrintingConfig,
            kitchenLabelNorms: Set<String>,
            kdsDeviceDocIdForClaimFilter: String = "",
        ): List<Order> {
            if (!doc.exists()) return emptyList()
            val id = doc.id
            val orderNum = doc.getLong("orderNumber") ?: 0L
            val tableName = doc.getString("tableName")?.trim().orEmpty()
                .ifEmpty { doc.getString("tableId")?.trim().orEmpty() }
                .ifEmpty {
                    if (orderNum > 0L) "#$orderNum" else "Order"
                }
            val customerName = doc.getString("customerName")?.trim().orEmpty()
            val orderSource = doc.getString("orderSource")?.trim().orEmpty()
            val rawOrderType = doc.getString("orderType")?.trim().orEmpty()
            val isOnlineOrder = orderSource.isNotBlank() ||
                rawOrderType.equals("UBER_EATS", ignoreCase = true) ||
                rawOrderType.equals("ONLINE_PICKUP", ignoreCase = true)
            val posStatus = doc.getString(FIELD_POS_STATUS)?.trim()?.uppercase().orEmpty()
            val kitchen = doc.getString(FIELD_KITCHEN_STATUS)?.trim()?.uppercase().orEmpty()
            val displayStatus = when {
                kitchen == "PREPARING" || kitchen == "READY" -> kitchen
                posStatus == "OPEN" -> "OPEN"
                else -> posStatus.ifEmpty { "OPEN" }
            }
            val createdAt = doc.getTimestamp("createdAt")?.toDate()
            val lastKitchenSentAt = doc.getTimestamp(FIELD_LAST_KITCHEN_SENT_AT)?.toDate()
            val kitchenStartedAtFromOrder = doc.getTimestamp(FIELD_KITCHEN_STARTED_AT)?.toDate()
            val rawItems = lineDocs.flatMap { parseLineItems(it) }
            val items = when (printing.printItemFilterMode) {
                KdsPrintingConfig.ALL_ITEMS -> rawItems
                else -> rawItems.filter { line ->
                    val pl = line.printerLabel.trim()
                    if (pl.isEmpty()) return@filter false
                    KdsKitchenRoutingLabels.normalizeLabelKey(pl) in kitchenLabelNorms
                }
            }
            if (items.isEmpty()) return emptyList()
            val itemsForDisplay = KdsLineWorkflow.linesOmittingKitchenReady(items)
            if (itemsForDisplay.isEmpty()) return emptyList()
            val cardClaims = parseCardClaims(doc)
            return splitPosOrderIntoKdsCards(
                id = id,
                tableName = tableName,
                customerName = customerName,
                displayStatus = displayStatus,
                createdAt = createdAt,
                lastKitchenSentAt = lastKitchenSentAt,
                kitchenStartedAtFromOrder = kitchenStartedAtFromOrder,
                itemsForDisplay = itemsForDisplay,
                orderType = normalizeOrderType(doc.getString("orderType")),
                orderNumber = orderNum,
                cardClaims = cardClaims,
                kdsDeviceDocId = kdsDeviceDocIdForClaimFilter.trim(),
                isOnlineOrder = isOnlineOrder,
            )
        }

        /**
         * When a follow-up kitchen send arrives while an earlier send is still PREPARING, show two cards
         * so the queue does not look like duplicated lines on the active ticket.
         */
        private fun splitPosOrderIntoKdsCards(
            id: String,
            tableName: String,
            customerName: String,
            displayStatus: String,
            createdAt: Date?,
            lastKitchenSentAt: Date?,
            kitchenStartedAtFromOrder: Date?,
            itemsForDisplay: List<OrderItem>,
            orderType: String,
            orderNumber: Long,
            cardClaims: Map<String, String> = emptyMap(),
            kdsDeviceDocId: String = "",
            isOnlineOrder: Boolean = false,
        ): List<Order> {
            fun normSt(s: String) = s.trim().uppercase(Locale.US)
            val preparing = itemsForDisplay.filter { normSt(it.kdsStatus) == "PREPARING" }
            val queued = itemsForDisplay.filter { normSt(it.kdsStatus) != "PREPARING" }
            fun buildCard(items: List<OrderItem>, cardKey: String): Order? {
                if (items.isEmpty()) return null
                if (kdsDeviceDocId.isNotEmpty()) {
                    val holder = cardClaims[cardKey]?.trim().orEmpty()
                    if (holder.isNotEmpty() && holder != kdsDeviceDocId) {
                        Log.d(
                            TAG,
                            "buildCard: hide cardKey=$cardKey holder=$holder me=$kdsDeviceDocId",
                        )
                        return null
                    }
                }
                val (cardStatus, prepAnchor) = KdsLineWorkflow.deriveCardStateForPrinterFilteredItems(
                    items = items,
                    orderKitchenStartedAt = kitchenStartedAtFromOrder,
                    legacyDisplayStatus = displayStatus,
                )
                if (cardStatus.equals("READY", ignoreCase = true)) return null
                return Order(
                    id = id,
                    cardKey = cardKey,
                    tableName = tableName,
                    customerName = customerName,
                    status = cardStatus,
                    createdAt = createdAt,
                    lastKitchenSentAt = lastKitchenSentAt,
                    kitchenStartedAt = prepAnchor,
                    items = items,
                    orderType = orderType,
                    orderNumber = orderNumber,
                    isOnlineOrder = isOnlineOrder,
                )
            }
            return when {
                preparing.isNotEmpty() && queued.isNotEmpty() -> {
                    listOfNotNull(
                        buildCard(preparing, "${id}#kds_prep"),
                        buildCard(queued, "${id}#kds_queue"),
                    )
                }
                else -> listOfNotNull(buildCard(itemsForDisplay, id))
            }
        }

        private fun normalizeOrderType(raw: String?): String {
            val t = raw?.trim()?.uppercase()?.replace(' ', '_') ?: return "DINE_IN"
            return when (t) {
                "DINE_IN", "DINEIN" -> "DINE_IN"
                "TO_GO", "TAKEOUT", "TAKE_OUT" -> "TO_GO"
                "BAR", "BAR_TAB" -> "BAR"
                "UBER_EATS" -> "UBER_EATS"
                "ONLINE_PICKUP" -> "ONLINE_PICKUP"
                else -> "DINE_IN"
            }
        }

        /**
         * One [OrderItem] per Firestore line (legacy), or one per [FIELD_KDS_SEND_BATCHES] entry when present
         * so the KDS can split active vs queued sends onto separate cards.
         */
        private fun parseLineItems(doc: DocumentSnapshot): List<OrderItem> {
            if (!doc.exists()) return emptyList()
            val name = doc.getString("name")?.trim().orEmpty()
            if (name.isEmpty()) return emptyList()
            val itemId = doc.getString("itemId")?.trim().orEmpty()
            val printerLabel = doc.getString("printerLabel")?.trim().orEmpty()
            val modsRaw = doc.get("modifiers") as? List<*> ?: emptyList<Any>()
            val modifierLines = modsRaw.mapNotNull { it as? Map<*, *> }.flatMap { formatModifierLines(it) }
            @Suppress("UNCHECKED_CAST")
            val batchRaw = doc.get(FIELD_KDS_SEND_BATCHES) as? List<*>
            if (batchRaw.isNullOrEmpty()) {
                val qty = (doc.getLong("quantity") ?: 0L).toInt().coerceAtLeast(1)
                val kdsStatus = doc.getString(FIELD_KDS_LINE_STATUS)?.trim().orEmpty()
                val kdsStartedAt = doc.getTimestamp(FIELD_KDS_STARTED_AT)?.toDate()
                val kdsReadyCoversQty = doc.getLong(FIELD_KDS_READY_COVERS_QTY) ?: 0L
                return listOf(
                    OrderItem(
                        name = name,
                        quantity = qty,
                        modifierLines = modifierLines,
                        itemId = itemId,
                        printerLabel = printerLabel,
                        lineDocId = doc.id,
                        kdsStatus = kdsStatus,
                        kdsStartedAt = kdsStartedAt,
                        kdsReadyCoversQty = kdsReadyCoversQty,
                    ),
                )
            }
            val batches = batchRaw.mapNotNull { it as? Map<*, *> }
                .sortedBy { batchSentAtMillis(it) }
            val out = mutableListOf<OrderItem>()
            for (m in batches) {
                val qty = (m[SUBFIELD_BATCH_QUANTITY] as? Number)?.toLong()?.toInt()?.coerceAtLeast(1) ?: continue
                val st = normKdsStatus(m[FIELD_KDS_LINE_STATUS]).ifEmpty { "SENT" }
                val batchStarted = m[FIELD_KDS_BATCH_STARTED_AT] as? Timestamp
                val batchSent = when (val s = m[SUBFIELD_SENT_AT]) {
                    is Timestamp -> s.toDate()
                    is Date -> s
                    else -> null
                }
                val batchId = m[SUBFIELD_BATCH_ID]?.toString()?.trim().orEmpty()
                out.add(
                    OrderItem(
                        name = name,
                        quantity = qty,
                        modifierLines = modifierLines,
                        itemId = itemId,
                        printerLabel = printerLabel,
                        lineDocId = doc.id,
                        kdsBatchId = batchId,
                        batchSentAt = batchSent,
                        kdsStatus = st,
                        kdsStartedAt = batchStarted?.toDate(),
                        kdsReadyCoversQty = 0L,
                    ),
                )
            }
            return out
        }

        private fun removeModifierDisplayLine(name: String): String {
            val t = name.trim()
            if (t.isEmpty()) return t
            val u = t.uppercase()
            if (u.startsWith("NO ") || u == "NO") return t
            return "No $t"
        }

        @Suppress("UNCHECKED_CAST")
        private fun formatModifierLines(m: Map<*, *>): List<OrderModifierLine> {
            val map = m as Map<String, Any?>
            val name = map["name"]?.toString()?.trim().orEmpty()
            val action = map["action"]?.toString()?.trim()?.uppercase().orEmpty().ifEmpty { "ADD" }
            val isRemove = action == "REMOVE"
            val line = when {
                isRemove && name.isNotEmpty() -> OrderModifierLine(removeModifierDisplayLine(name), true)
                name.isNotEmpty() -> OrderModifierLine(name, false)
                else -> null
            }
            val children = (map["children"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.flatMap { formatModifierLines(it) }
                .orEmpty()
            return listOfNotNull(line) + children
        }
    }
}
