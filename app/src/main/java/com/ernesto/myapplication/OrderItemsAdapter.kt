package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.ernesto.myapplication.engine.DiscountDisplay
import com.ernesto.myapplication.engine.MoneyUtils
import com.ernesto.myapplication.ui.kds.KdsStatusIcon
import com.ernesto.myapplication.ui.kds.hasKdsStatusIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class OrderListItem {
    data class GuestHeader(val guestNumber: Int, val guestName: String? = null) : OrderListItem()

    /** Single line doc (unique name within its section, or sole occurrence). */
    data class Item(val doc: DocumentSnapshot) : OrderListItem()

    /**
     * Visual-only grouping: multiple Firestore line docs with the same display [name].
     * [guestNumber] is 0 when the order is not split by guest; else the guest section key.
     */
    data class NamedGroup(
        val name: String,
        val guestNumber: Int,
        val documents: List<DocumentSnapshot>,
    ) : OrderListItem() {
        fun stableKey(): String = "${guestNumber}:${name}"
    }
}

class OrderItemsAdapter(
    private val listItems: MutableList<OrderListItem>,
    private val onItemClick: ((DocumentSnapshot) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_NAMED_GROUP = 2
    }

    private val expandedGroupKeys = mutableSetOf<String>()

    /** Single Firestore line with multiple [OrderLineKdsStatus.FIELD_KDS_SEND_BATCHES] entries. */
    private val expandedBatchLineIds = mutableSetOf<String>()

    private val kdsBatchTimeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    private var refundedLineKeys: Set<String> = emptySet()
    private var refundedNameAmountKeys: Set<String> = emptySet()
    private var refundedLineKeyToEmployee: Map<String, String> = emptyMap()
    private var refundedNameAmountToEmployee: Map<String, String> = emptyMap()
    private var refundedLineKeyToDate: Map<String, String> = emptyMap()
    private var refundedNameAmountToDate: Map<String, String> = emptyMap()
    private var wholeOrderRefundEmployee: String? = null
    private var wholeOrderRefundDate: String? = null

    /** Order-level applied discounts from Firestore (each map may contain lineKey). */
    private var appliedDiscounts: List<Map<String, Any>> = emptyList()

    fun setAppliedDiscounts(discounts: List<Map<String, Any>>) {
        appliedDiscounts = discounts
        notifyDataSetChanged()
    }

    fun setRefundedKeys(
        lineKeys: Set<String>,
        nameAmountKeys: Set<String>,
        lineKeyToEmployee: Map<String, String> = emptyMap(),
        nameAmountToEmployee: Map<String, String> = emptyMap(),
        lineKeyToDate: Map<String, String> = emptyMap(),
        nameAmountToDate: Map<String, String> = emptyMap(),
        wholeOrderEmployee: String? = null,
        wholeOrderDate: String? = null,
    ) {
        refundedLineKeys = lineKeys
        refundedNameAmountKeys = nameAmountKeys
        refundedLineKeyToEmployee = lineKeyToEmployee
        refundedNameAmountToEmployee = nameAmountToEmployee
        refundedLineKeyToDate = lineKeyToDate
        refundedNameAmountToDate = nameAmountToDate
        wholeOrderRefundEmployee = wholeOrderEmployee
        wholeOrderRefundDate = wholeOrderDate
        notifyDataSetChanged()
    }

    /** Drop expansion state for groups that no longer exist (e.g. after realtime update). */
    fun retainExpandedGroupsOnly(validStableKeys: Set<String>) {
        expandedGroupKeys.retainAll(validStableKeys)
    }

    fun retainExpandedBatchLineIdsOnly(validLineIds: Set<String>) {
        expandedBatchLineIds.retainAll(validLineIds)
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val txtHeader: TextView = view.findViewById(R.id.txtGuestHeader)
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val itemNameKdsRow: View = view.findViewById(R.id.itemNameKdsRow)
        val batchChevron: TextView = view.findViewById(R.id.txtItemBatchChevron)
        val nameQty: TextView = view.findViewById(R.id.txtItemNameQty)
        val base: TextView = view.findViewById(R.id.txtItemBase)
        val modifiers: TextView = view.findViewById(R.id.txtItemModifiers)
        val discounts: TextView = view.findViewById(R.id.txtItemDiscounts)
        val lineTotal: TextView = view.findViewById(R.id.txtItemLineTotal)
        val payments: TextView = view.findViewById(R.id.txtItemPayments)
        val refundedBadge: TextView = view.findViewById(R.id.txtRefundedBadge)
        val refundedByRow: View = view.findViewById(R.id.refundedByRow)
        val refundedBy: TextView = view.findViewById(R.id.txtRefundedBy)
        val refundedDate: TextView = view.findViewById(R.id.txtRefundedDate)
        val kdsCompose: ComposeView = view.findViewById(R.id.kdsStatusCompose)
        val kdsBatchChildContainer: LinearLayout = view.findViewById(R.id.kdsBatchChildContainer)

        init {
            kdsCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
        }
    }

    class GroupVH(view: View) : RecyclerView.ViewHolder(view) {
        val headerRow: View = view.findViewById(R.id.groupHeaderRow)
        val chevron: TextView = view.findViewById(R.id.txtGroupChevron)
        val nameQty: TextView = view.findViewById(R.id.txtGroupNameQty)
        val lineTotal: TextView = view.findViewById(R.id.txtGroupLineTotal)
        val refundedBadge: TextView = view.findViewById(R.id.txtGroupRefundedBadge)
        val kdsCompose: ComposeView = view.findViewById(R.id.kdsStatusComposeGroup)
        val childContainer: LinearLayout = view.findViewById(R.id.groupChildContainer)

        init {
            kdsCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
        }
    }

    override fun getItemViewType(position: Int): Int = when (listItems[position]) {
        is OrderListItem.GuestHeader -> TYPE_HEADER
        is OrderListItem.Item -> TYPE_ITEM
        is OrderListItem.NamedGroup -> TYPE_NAMED_GROUP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_guest_header, parent, false))
            TYPE_NAMED_GROUP -> GroupVH(inflater.inflate(R.layout.item_order_item_group, parent, false))
            else -> ItemVH(inflater.inflate(R.layout.item_order_item, parent, false))
        }
    }

    override fun getItemCount() = listItems.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = listItems[position]) {
            is OrderListItem.GuestHeader -> {
                val label = if (!entry.guestName.isNullOrBlank()) entry.guestName else "Guest ${entry.guestNumber}"
                (holder as HeaderVH).txtHeader.text = "— $label —"
            }
            is OrderListItem.Item -> bindItem(holder as ItemVH, entry.doc)
            is OrderListItem.NamedGroup -> bindNamedGroup(holder as GroupVH, entry)
        }
    }

    private fun bindItem(holder: ItemVH, doc: DocumentSnapshot) {
        val ctx = holder.itemView.context
        val batches = OrderLineKdsStatus.parseKdsSendBatches(doc)
        val multiBatch = batches.size > 1

        val name = doc.getString("name")
            ?: doc.getString("itemName")
            ?: "Item"

        val qty = lineQty(doc)

        val lineInCents =
            doc.getLong("lineTotalInCents") ?: 0L

        val lineKey = doc.id
        val nameAmountKey = "$name|$lineInCents"
        val matchedByKey = lineKey in refundedLineKeys || nameAmountKey in refundedNameAmountKeys
        val isRefunded = matchedByKey || wholeOrderRefundEmployee != null
        val refundedByEmployee = refundedLineKeyToEmployee[lineKey]
            ?: refundedNameAmountToEmployee[nameAmountKey]
            ?: wholeOrderRefundEmployee
        val refundedDateStr = refundedLineKeyToDate[lineKey]
            ?: refundedNameAmountToDate[nameAmountKey]
            ?: wholeOrderRefundDate

        val lineTotalNormalColor = Color.parseColor("#1B5E20")
        val lineTotalRefundedColor = Color.parseColor("#999999")

        val kdsActive = KdsActiveCache.hasActiveKds
        val headerKdsStatus = if (kdsActive) {
            OrderLineKdsStatus.latestBatchKdsStatusForLine(doc)
                ?: doc.getString(OrderLineKdsStatus.FIELD)
        } else null
        if (kdsActive && hasKdsStatusIndicator(headerKdsStatus)) {
            holder.kdsCompose.visibility = View.VISIBLE
            holder.kdsCompose.setContent {
                KdsStatusIcon(status = headerKdsStatus) { msg ->
                    Toast.makeText(holder.itemView.context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            holder.kdsCompose.visibility = View.GONE
            holder.kdsCompose.setContent { }
        }

        holder.nameQty.text = "$name (Qty: $qty)"
        holder.lineTotal.text = "Line Total: ${MoneyUtils.centsToDisplay(lineInCents)}"

        if (isRefunded) {
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF5F5"))
            holder.nameQty.paintFlags = holder.nameQty.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.nameQty.setTextColor(Color.parseColor("#999999"))
            holder.lineTotal.paintFlags = holder.lineTotal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.lineTotal.setTextColor(lineTotalRefundedColor)
            holder.refundedBadge.visibility = View.VISIBLE

            if (!refundedByEmployee.isNullOrBlank() || !refundedDateStr.isNullOrBlank()) {
                holder.refundedByRow.visibility = View.VISIBLE
                holder.refundedBy.text = if (!refundedByEmployee.isNullOrBlank()) "Refunded by $refundedByEmployee" else ""
                holder.refundedBy.visibility = if (refundedByEmployee.isNullOrBlank()) View.GONE else View.VISIBLE
                holder.refundedDate.text = refundedDateStr ?: ""
                holder.refundedDate.visibility = if (refundedDateStr.isNullOrBlank()) View.GONE else View.VISIBLE
            } else {
                holder.refundedByRow.visibility = View.GONE
            }
        } else {
            holder.itemView.setBackgroundColor(Color.WHITE)
            holder.nameQty.paintFlags = holder.nameQty.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.nameQty.setTextColor(Color.parseColor("#222222"))
            holder.lineTotal.paintFlags = holder.lineTotal.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.lineTotal.setTextColor(lineTotalNormalColor)
            holder.refundedBadge.visibility = View.GONE
            holder.refundedByRow.visibility = View.GONE
        }

        holder.base.visibility = View.GONE

        val modsText = buildModifiersText(doc)
        if (modsText.isNullOrBlank()) {
            holder.modifiers.visibility = View.GONE
        } else {
            holder.modifiers.visibility = View.VISIBLE
            holder.modifiers.text = modsText
        }

        val lineDiscountText = buildLineDiscountsText(lineKey)
        if (lineDiscountText.isNullOrBlank()) {
            holder.discounts.visibility = View.GONE
        } else {
            holder.discounts.visibility = View.VISIBLE
            holder.discounts.text = lineDiscountText
        }

        val paymentsArray = doc.get("payments") as? List<Map<String, Any>>

        if (!paymentsArray.isNullOrEmpty()) {
            val totalsByType = mutableMapOf<String, Double>()
            var grandTotal = 0.0

            paymentsArray.forEach { payment ->
                val type = payment["type"]?.toString() ?: return@forEach
                val amount = (payment["amount"] as? Number)?.toDouble() ?: 0.0
                totalsByType[type] = (totalsByType[type] ?: 0.0) + amount
                grandTotal += amount
            }

            val lines = totalsByType.map { (type, amount) ->
                "$type: $${String.format(Locale.US, "%.2f", amount)}"
            }.toMutableList()

            lines.add("")
            lines.add("Total Collected: $${String.format(Locale.US, "%.2f", grandTotal)}")

            holder.payments.visibility = View.VISIBLE
            holder.payments.text = lines.joinToString("\n")
        } else {
            holder.payments.visibility = View.GONE
        }

        val lineId = doc.id
        if (kdsActive && multiBatch) {
            holder.batchChevron.visibility = View.VISIBLE
            val batchExpanded = lineId in expandedBatchLineIds
            holder.batchChevron.text = if (batchExpanded) "▼" else "▶"
            holder.kdsBatchChildContainer.removeAllViews()
            holder.kdsBatchChildContainer.visibility = if (batchExpanded) View.VISIBLE else View.GONE
            if (batchExpanded) {
                val density = ctx.resources.displayMetrics.density
                val padH = (16 * density).toInt()
                batches.forEachIndexed { idx, batch ->
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(padH, (4 * density).toInt(), padH, (4 * density).toInt())
                        isClickable = true
                        setOnClickListener { onItemClick?.invoke(doc) }
                    }
                    val timeLabel = if (batch.sentAtMillis > 0L) {
                        kdsBatchTimeFormat.format(Date(batch.sentAtMillis))
                    } else {
                        "—"
                    }
                    val labelText = buildString {
                        append("KDS send ${idx + 1} · Qty ${batch.quantity} · $timeLabel")
                        append("\n")
                        append(kdsStatusSummaryForLine(batch.kdsStatus))
                    }
                    row.addView(
                        TextView(ctx).apply {
                            text = labelText
                            textSize = 12f
                            setTextColor(Color.parseColor("#555555"))
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        },
                    )
                    if (kdsActive && hasKdsStatusIndicator(batch.kdsStatus)) {
                        row.addView(
                            ComposeView(ctx).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                                setViewCompositionStrategy(
                                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                                )
                                setContent {
                                    KdsStatusIcon(status = batch.kdsStatus) { msg ->
                                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                    holder.kdsBatchChildContainer.addView(row)
                }
            }
            val toggle = View.OnClickListener {
                if (lineId in expandedBatchLineIds) {
                    expandedBatchLineIds.remove(lineId)
                } else {
                    expandedBatchLineIds.add(lineId)
                }
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }
            holder.itemView.setOnClickListener(toggle)
            holder.modifiers.setOnClickListener { onItemClick?.invoke(doc) }
            holder.discounts.setOnClickListener { onItemClick?.invoke(doc) }
        } else {
            holder.batchChevron.visibility = View.GONE
            holder.kdsBatchChildContainer.visibility = View.GONE
            holder.kdsBatchChildContainer.removeAllViews()
            holder.modifiers.setOnClickListener(null)
            holder.discounts.setOnClickListener(null)
            holder.itemView.setOnClickListener {
                onItemClick?.invoke(doc)
            }
        }
    }

    private fun bindNamedGroup(holder: GroupVH, group: OrderListItem.NamedGroup) {
        val ctx = holder.itemView.context
        val key = group.stableKey()
        val expanded = key in expandedGroupKeys
        val docs = group.documents.sortedWith(
            compareBy<DocumentSnapshot>({ lineCreatedAtMillis(it) }, { it.id }),
        )
        val totalQty = docs.sumOf { lineQty(it) }
        val totalLineCents = docs.sumOf { it.getLong("lineTotalInCents") ?: 0L }

        val kdsActive = KdsActiveCache.hasActiveKds
        val kdsRaw = if (kdsActive) {
            val latestForStatus = latestDocForCollapsedKdsStatus(docs)
            OrderLineKdsStatus.latestBatchKdsStatusForLine(latestForStatus)
                ?: latestForStatus.getString(OrderLineKdsStatus.FIELD)
        } else null

        holder.nameQty.text = "${group.name} (Qty: $totalQty)"
        holder.lineTotal.text = "Line Total: ${MoneyUtils.centsToDisplay(totalLineCents)}"

        if (kdsActive && hasKdsStatusIndicator(kdsRaw)) {
            holder.kdsCompose.visibility = View.VISIBLE
            holder.kdsCompose.setContent {
                KdsStatusIcon(status = kdsRaw) { msg ->
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            holder.kdsCompose.visibility = View.GONE
            holder.kdsCompose.setContent { }
        }

        holder.chevron.text = if (expanded) "▼" else "▶"

        val anyRefunded = docs.any { doc ->
            val name = doc.getString("name") ?: doc.getString("itemName") ?: "Item"
            val cents = doc.getLong("lineTotalInCents") ?: 0L
            val lineKey = doc.id
            val nameAmountKey = "$name|$cents"
            doc.id in refundedLineKeys || nameAmountKey in refundedNameAmountKeys || wholeOrderRefundEmployee != null
        }
        if (anyRefunded) {
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF5F5"))
            holder.nameQty.paintFlags = holder.nameQty.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.nameQty.setTextColor(Color.parseColor("#999999"))
            holder.lineTotal.paintFlags = holder.lineTotal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.lineTotal.setTextColor(Color.parseColor("#999999"))
            holder.refundedBadge.visibility = View.VISIBLE
        } else {
            holder.itemView.setBackgroundColor(Color.WHITE)
            holder.nameQty.paintFlags = holder.nameQty.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.nameQty.setTextColor(Color.parseColor("#222222"))
            holder.lineTotal.paintFlags = holder.lineTotal.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.lineTotal.setTextColor(Color.parseColor("#1B5E20"))
            holder.refundedBadge.visibility = View.GONE
        }

        fun toggleExpanded() {
            if (expanded) expandedGroupKeys.remove(key) else expandedGroupKeys.add(key)
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        }

        val toggleExpand = View.OnClickListener { toggleExpanded() }
        holder.headerRow.setOnClickListener(toggleExpand)
        holder.lineTotal.setOnClickListener(toggleExpand)
        holder.refundedBadge.setOnClickListener(toggleExpand)
        holder.chevron.setOnClickListener(toggleExpand)
        holder.itemView.setOnClickListener(toggleExpand)

        holder.childContainer.removeAllViews()
        if (expanded) {
            holder.childContainer.visibility = View.VISIBLE
            val density = ctx.resources.displayMetrics.density
            val padH = (24 * density).toInt()
            val padV = (4 * density).toInt()
            for (doc in docs) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                    isClickable = true
                    setOnClickListener { onItemClick?.invoke(doc) }
                }
                val q = lineQty(doc)
                val lineKdsStatus = if (kdsActive) {
                    OrderLineKdsStatus.latestBatchKdsStatusForLine(doc)
                        ?: doc.getString(OrderLineKdsStatus.FIELD)
                } else null
                val qtyLabel = TextView(ctx).apply {
                    text = "${q}x "
                    textSize = 13f
                    setTextColor(Color.parseColor("#444444"))
                }
                row.addView(
                    qtyLabel,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                if (kdsActive) {
                    val statusLabel = TextView(ctx).apply {
                        text = kdsStatusSummaryForLine(lineKdsStatus)
                        textSize = 12f
                        setTextColor(Color.parseColor("#555555"))
                        maxLines = 2
                    }
                    row.addView(
                        statusLabel,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = (6 * density).toInt()
                        },
                    )
                    if (hasKdsStatusIndicator(lineKdsStatus)) {
                        val compose = ComposeView(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            setViewCompositionStrategy(
                                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                            )
                            setContent {
                                KdsStatusIcon(status = lineKdsStatus) { msg ->
                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        row.addView(compose)
                    }
                }
                holder.childContainer.addView(row)
            }
        } else {
            holder.childContainer.visibility = View.GONE
        }
    }

    private fun buildLineDiscountsText(lineKey: String): String? {
        val itemLevel = appliedDiscounts.filter { ad ->
            val lk = ad["lineKey"]?.toString()?.trim().orEmpty()
            lk == lineKey
        }
        if (itemLevel.isEmpty()) return null
        return itemLevel.joinToString("\n") { DiscountDisplay.formatBulletFromFirestoreMap(it) }
    }

    private fun buildModifiersText(doc: DocumentSnapshot): String? {
        val raw = doc.get("modifiers") as? List<*> ?: return null
        if (raw.isEmpty()) return null

        val lines = mutableListOf<String>()
        fun appendModifier(item: Any?, indent: String = "") {
            when (item) {
                is Map<*, *> -> {
                    val action = item["action"]?.toString() ?: "ADD"
                    val modName = item["name"]?.toString()
                        ?: item["first"]?.toString()
                        ?: return

                    lines.add(
                        if (action == "REMOVE") "${indent}\u2022 ${ModifierRemoveDisplay.cartLine(modName)}"
                        else "${indent}\u2022 $modName",
                    )
                    val children = item["children"] as? List<*>
                    children?.forEach { child -> appendModifier(child, "$indent    \u21B3 ") }
                }
                is List<*> -> {
                    if (item.isEmpty()) return
                    val modName = item[0]?.toString() ?: return
                    lines.add("${indent}\u2022 $modName")
                }
            }
        }
        raw.forEach { appendModifier(it) }

        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }
}

private fun lineQty(doc: DocumentSnapshot): Int =
    (doc.getLong("qty") ?: doc.getLong("quantity") ?: 1L).toInt().coerceAtLeast(1)

/** Firestore line `createdAt` (fallback `updatedAt`) for ordering / latest-status. */
private fun lineCreatedAtMillis(doc: DocumentSnapshot): Long {
    doc.getTimestamp("createdAt")?.toDate()?.time?.let { return it }
    doc.getDate("createdAt")?.time?.let { return it }
    doc.getTimestamp("updatedAt")?.toDate()?.time?.let { return it }
    return 0L
}

private fun latestDocForCollapsedKdsStatus(docs: List<DocumentSnapshot>): DocumentSnapshot =
    docs.maxWith(compareBy({ lineCreatedAtMillis(it) }, { it.id }))

private fun kdsStatusSummaryForLine(raw: String?): String {
    val s = raw?.trim()?.uppercase(Locale.US).orEmpty()
    return when (s) {
        OrderLineKdsStatus.SENT -> "Sent to kitchen"
        OrderLineKdsStatus.PREPARING -> "Preparing"
        OrderLineKdsStatus.READY -> "Ready"
        else -> raw?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
    }
}
