package com.volt.maximobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.volt.shared.data.OrderModifier
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Advances multi-course kitchen firing after the configured delay:
 *
 * - **KDS online** ([KdsActiveCache.hasOnlineKds]): waits until every line in the current course
 *   is marked KDS READY, then waits each course's `delayAfterReadySeconds`
 *   before printing the next course.
 * - **Printer-only** (no KDS online): treats the kitchen ticket print for that course as complete,
 *   writes [courseReadyAt], and starts the same delay from that moment (first course from
 *   [activate] after [MenuActivity] print success; later courses from print commit in
 *   [fireNextCourse]).
 *
 * Lifecycle: [activate] starts monitoring or schedules the next course;
 * [deactivate] stops all monitoring. Only one order is tracked at a time.
 */
object CourseFiringProgressionHandler {

    private const val TAG = "CourseFiring"
    private val handler = Handler(Looper.getMainLooper())

    private var activeOrderId: String? = null
    private var currentCourseId: String? = null
    private var itemsListener: ListenerRegistration? = null
    private var pendingFireRunnable: Runnable? = null

    /** When true, progression waits for KDS READY on line items; when false, after kitchen print. */
    private fun useReadyBasedProgression(): Boolean = KdsActiveCache.hasOnlineKds

    fun activate(context: Context, db: FirebaseFirestore, orderId: String, firedCourseId: String) {
        deactivate()
        activeOrderId = orderId
        currentCourseId = firedCourseId
        if (useReadyBasedProgression()) {
            writeCourseState(db, orderId, firedCourseId, fired = true)
            attachListener(context, db, orderId, firedCourseId)
        } else {
            writeCourseState(db, orderId, firedCourseId, fired = true, ready = true)
            val next = CourseFiringCache.nextCourseAfter(firedCourseId)
            if (next == null) {
                activeOrderId = null
                currentCourseId = null
                return
            }
            scheduleFireNext(context, db, orderId, firedCourseId, next.delayAfterReadySeconds * 1000L)
        }
    }

    fun deactivate() {
        itemsListener?.remove()
        itemsListener = null
        pendingFireRunnable?.let { handler.removeCallbacks(it) }
        pendingFireRunnable = null
        activeOrderId = null
        currentCourseId = null
    }

    /**
     * Resumes monitoring after app restart by checking Firestore state.
     * Call from the activity that loads a dine-in order with course firing active.
     */
    fun resumeIfNeeded(context: Context, db: FirebaseFirestore, orderId: String) {
        if (!CourseFiringCache.enabled || CourseFiringCache.courses.isEmpty()) return

        val orderRef = MerchantFirestore.col("Orders").document(orderId)
        orderRef.get().addOnSuccessListener { orderDoc ->
            if (!orderDoc.exists()) return@addOnSuccessListener
            if (orderDoc.getBoolean("courseFiringActive") != true) return@addOnSuccessListener

            @Suppress("UNCHECKED_CAST")
            val firedAt = orderDoc.get("courseFiredAt") as? Map<String, Any> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val readyAt = orderDoc.get("courseReadyAt") as? Map<String, Any> ?: emptyMap()

            val lastFiredCourse = CourseFiringCache.courses
                .filter { firedAt.containsKey(it.id) }
                .maxByOrNull { it.order }
                ?: return@addOnSuccessListener

            if (readyAt.containsKey(lastFiredCourse.id)) {
                val nextCourse = CourseFiringCache.nextCourseAfter(lastFiredCourse.id)
                if (nextCourse != null && !firedAt.containsKey(nextCourse.id)) {
                    val readyTimestamp = readyAt[lastFiredCourse.id]
                    val readyMs = when (readyTimestamp) {
                        is Timestamp -> readyTimestamp.toDate().time
                        is Date -> readyTimestamp.time
                        else -> System.currentTimeMillis()
                    }
                    val elapsed = System.currentTimeMillis() - readyMs
                    val delayMs = (nextCourse.delayAfterReadySeconds * 1000L) - elapsed
                    activeOrderId = orderId
                    currentCourseId = lastFiredCourse.id
                    if (delayMs <= 0) {
                        fireNextCourse(context, db, orderId, lastFiredCourse.id)
                    } else {
                        scheduleFireNext(context, db, orderId, lastFiredCourse.id, delayMs)
                    }
                }
            } else {
                activeOrderId = orderId
                currentCourseId = lastFiredCourse.id
                if (useReadyBasedProgression()) {
                    attachListener(context, db, orderId, lastFiredCourse.id)
                } else {
                    val nextCourse = CourseFiringCache.nextCourseAfter(lastFiredCourse.id)
                    if (nextCourse == null || firedAt.containsKey(nextCourse.id)) {
                        return@addOnSuccessListener
                    }
                    val firedTimestamp = firedAt[lastFiredCourse.id]
                    val anchorMs = when (firedTimestamp) {
                        is Timestamp -> firedTimestamp.toDate().time
                        is Date -> firedTimestamp.time
                        else -> System.currentTimeMillis()
                    }
                    val elapsed = System.currentTimeMillis() - anchorMs
                    val delayMs = (nextCourse.delayAfterReadySeconds * 1000L) - elapsed
                    if (delayMs <= 0) {
                        fireNextCourse(context, db, orderId, lastFiredCourse.id)
                    } else {
                        scheduleFireNext(context, db, orderId, lastFiredCourse.id, delayMs)
                    }
                }
            }
        }
    }

    private fun attachListener(context: Context, db: FirebaseFirestore, orderId: String, courseId: String) {
        if (!useReadyBasedProgression()) return
        itemsListener?.remove()
        val firstCourseId = CourseFiringCache.firstCourseId()
        val itemsRef = MerchantFirestore.col("Orders").document(orderId).collection("items")
        itemsListener = itemsRef.addSnapshotListener { snapshot, err ->
            if (err != null || snapshot == null) return@addSnapshotListener
            if (activeOrderId != orderId || currentCourseId != courseId) return@addSnapshotListener

            val courseItems = snapshot.documents.filter { doc ->
                val docCourse = doc.getString("courseId")?.trim().orEmpty()
                val effective = docCourse.ifEmpty { firstCourseId.orEmpty() }
                effective == courseId
            }
            if (courseItems.isEmpty()) return@addSnapshotListener

            val allReady = courseItems.all { doc ->
                val qty = doc.getLong("quantity") ?: 0L
                if (qty <= 0) return@all true
                val kdsStatus = doc.getString(OrderLineKdsStatus.FIELD)
                    ?.trim()?.uppercase(Locale.US).orEmpty()
                kdsStatus == OrderLineKdsStatus.READY
            }

            if (allReady) {
                itemsListener?.remove()
                itemsListener = null
                writeCourseState(db, orderId, courseId, ready = true)
                val nextCourse = CourseFiringCache.nextCourseAfter(courseId)
                if (nextCourse != null) {
                    val delayMs = nextCourse.delayAfterReadySeconds * 1000L
                    if (delayMs <= 0) {
                        fireNextCourse(context, db, orderId, courseId)
                    } else {
                        scheduleFireNext(context, db, orderId, courseId, delayMs)
                    }
                } else {
                    deactivate()
                }
            }
        }
    }

    private fun scheduleFireNext(context: Context, db: FirebaseFirestore, orderId: String, currentCourse: String, delayMs: Long) {
        pendingFireRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { fireNextCourse(context, db, orderId, currentCourse) }
        pendingFireRunnable = runnable
        handler.postDelayed(runnable, delayMs)
        Log.d(TAG, "Scheduled next course fire in ${delayMs}ms for order $orderId after course $currentCourse")
    }

    private fun fireNextCourse(context: Context, db: FirebaseFirestore, orderId: String, afterCourseId: String) {
        pendingFireRunnable = null
        val nextCourse = CourseFiringCache.nextCourseAfter(afterCourseId) ?: run {
            deactivate()
            return
        }
        Log.d(TAG, "Firing course ${nextCourse.id} for order $orderId")

        val orderRef = MerchantFirestore.col("Orders").document(orderId)
        orderRef.get().addOnSuccessListener { orderDoc ->
            if (!orderDoc.exists()) { deactivate(); return@addOnSuccessListener }

            orderRef.collection("items").get().addOnSuccessListener { itemsSnap ->
                val firstCourse = CourseFiringCache.firstCourseId()
                val courseItems = itemsSnap.documents.filter { doc ->
                    val docCourse = doc.getString("courseId")?.trim().orEmpty()
                    val effective = docCourse.ifEmpty { firstCourse.orEmpty() }
                    effective == nextCourse.id
                }
                if (courseItems.isEmpty()) {
                    writeCourseState(db, orderId, nextCourse.id, fired = true)
                    writeCourseState(db, orderId, nextCourse.id, ready = true)
                    val afterNext = CourseFiringCache.nextCourseAfter(nextCourse.id)
                    if (afterNext != null) {
                        currentCourseId = nextCourse.id
                        fireNextCourse(context, db, orderId, nextCourse.id)
                    } else {
                        deactivate()
                    }
                    return@addOnSuccessListener
                }

                val sent = KitchenPrintHelper.effectiveSentWithLegacyOrderMap(orderDoc, itemsSnap)
                val lineItems = mutableListOf<KitchenTicketLineInput>()
                val qtyUpdates = mutableMapOf<String, Int>()

                for (doc in courseItems) {
                    val qty = (doc.getLong("quantity") ?: 1L).toInt()
                    if (qty <= 0) continue
                    val lineKey = doc.id
                    val sentQty = sent[lineKey] ?: 0
                    val delta = qty - sentQty
                    if (delta <= 0) continue
                    val name = doc.getString("name") ?: continue
                    val mods = parseModifiersFromDoc(doc.get("modifiers"))
                    val label = MenuItemRoutingLabel.fromOrderLineDoc(doc)
                    lineItems.add(KitchenTicketLineInput(delta, name, mods, label, null))
                    qtyUpdates[lineKey] = qty
                }

                if (lineItems.isEmpty()) {
                    markCourseKitchenCompleteAndContinue(context, db, orderId, nextCourse.id)
                    return@addOnSuccessListener
                }

                KitchenPrintHelper.printKitchenTickets(context, orderId, lineItems) { notesPrintedByIp ->
                    val merged = KitchenPrintHelper.kitchenSentByLineFromOrder(orderDoc).toMutableMap()
                    qtyUpdates.forEach { (k, v) -> merged[k] = v }
                    val update = mutableMapOf<String, Any>(
                        KitchenPrintHelper.KITCHEN_SENT_BY_LINE_MAP_FIELD to merged,
                        "lastKitchenSentAt" to Date(),
                    )
                    if (notesPrintedByIp != null) {
                        update[KitchenPrintHelper.KITCHEN_NOTES_LAST_PRINTED_BY_PRINTER_IP] = notesPrintedByIp
                    }
                    val wb = db.batch()
                    wb.update(orderRef, update)

                    val prevSent = KitchenPrintHelper.effectiveSentWithLegacyOrderMap(orderDoc, itemsSnap)
                    val kitchenSendGroupId = UUID.randomUUID().toString()
                    for ((lineKey, newHigh) in qtyUpdates) {
                        val prev = prevSent[lineKey] ?: 0
                        val delta2 = newHigh - prev
                        if (delta2 > 0) {
                            val entry = hashMapOf<String, Any>(
                                OrderLineKdsStatus.BATCH_SUBFIELD_ID to UUID.randomUUID().toString(),
                                OrderLineKdsStatus.BATCH_SUBFIELD_SEND_GROUP_ID to kitchenSendGroupId,
                                "sentAt" to Timestamp.now(),
                                "quantity" to delta2.toLong(),
                                OrderLineKdsStatus.BATCH_SUBFIELD_KDS_STATUS to OrderLineKdsStatus.SENT,
                            )
                            wb.update(
                                orderRef.collection("items").document(lineKey),
                                mapOf(OrderLineKdsStatus.FIELD_KDS_SEND_BATCHES to FieldValue.arrayUnion(entry)),
                            )
                        }
                    }
                    wb.commit().addOnSuccessListener {
                        markCourseKitchenCompleteAndContinue(context, db, orderId, nextCourse.id)
                    }
                }
            }
        }
    }

    /**
     * Called after a course's kitchen ticket is committed (or there was nothing new to print but
     * the course is considered advanced). KDS mode: wait for READY on this course; printer mode:
     * start delay for the following course.
     */
    private fun markCourseKitchenCompleteAndContinue(
        context: Context,
        db: FirebaseFirestore,
        orderId: String,
        completedCourseId: String,
    ) {
        if (useReadyBasedProgression()) {
            writeCourseState(db, orderId, completedCourseId, fired = true)
            currentCourseId = completedCourseId
            attachListener(context, db, orderId, completedCourseId)
        } else {
            writeCourseState(db, orderId, completedCourseId, fired = true, ready = true)
            val afterNext = CourseFiringCache.nextCourseAfter(completedCourseId)
            if (afterNext != null) {
                activeOrderId = orderId
                currentCourseId = completedCourseId
                scheduleFireNext(
                    context,
                    db,
                    orderId,
                    completedCourseId,
                    afterNext.delayAfterReadySeconds * 1000L,
                )
            } else {
                deactivate()
            }
        }
    }

    private fun writeCourseState(db: FirebaseFirestore, orderId: String, courseId: String, fired: Boolean = false, ready: Boolean = false) {
        val updates = mutableMapOf<String, Any>("courseFiringActive" to true)
        if (fired) updates["courseFiredAt.$courseId"] = Timestamp.now()
        if (ready) updates["courseReadyAt.$courseId"] = Timestamp.now()
        MerchantFirestore.col("Orders").document(orderId).update(updates)
            .addOnFailureListener { Log.w(TAG, "writeCourseState failed", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseModifiersFromDoc(raw: Any?): List<OrderModifier> {
        val list = raw as? List<*> ?: return emptyList()
        val out = mutableListOf<OrderModifier>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val name = map["name"]?.toString() ?: continue
            val action = map["action"]?.toString() ?: "ADD"
            val price = (map["price"] as? Number)?.toDouble() ?: 0.0
            val groupId = map["groupId"]?.toString() ?: ""
            val groupName = map["groupName"]?.toString() ?: ""
            val children = parseModifiersFromDoc(map["children"])
            out.add(OrderModifier(name, action, price, groupId, groupName, children))
        }
        return out
    }
}
