package com.ernesto.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.engine.MoneyUtils
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomerProfileActivity : AppCompatActivity() {

    companion object {
        private const val STATE_RESERVATION_HISTORY_EXPANDED = "state_reservation_history_expanded"
        private const val STATE_ORDER_HISTORY_EXPANDED = "state_order_history_expanded"
    }

    private val db = FirebaseFirestore.getInstance()

    private lateinit var txtProfileName: TextView
    private lateinit var txtProfilePhone: TextView
    private lateinit var txtProfileEmail: TextView
    private lateinit var txtLifetimeSpend: TextView
    private lateinit var txtVisits: TextView
    private lateinit var txtLastVisit: TextView
    private lateinit var txtEmptyOrders: TextView
    private lateinit var recyclerOrderHistory: RecyclerView
    private lateinit var txtReservationHistoryTitle: TextView
    private lateinit var txtEmptyAllReservations: TextView
    private lateinit var txtUpcomingReservationsHeader: TextView
    private lateinit var txtEmptyUpcomingReservations: TextView
    private lateinit var recyclerUpcomingReservations: RecyclerView
    private lateinit var txtPastReservationsHeader: TextView
    private lateinit var txtEmptyPastReservations: TextView
    private lateinit var recyclerPastReservations: RecyclerView
    private lateinit var headerReservationHistory: View
    private lateinit var groupReservationHistoryBody: View
    private lateinit var imgReservationHistoryChevron: ImageView
    private lateinit var headerOrderHistory: View
    private lateinit var groupOrderHistoryBody: View
    private lateinit var imgOrderHistoryChevron: ImageView

    private var reservationHistoryExpanded = false
    private var orderHistoryExpanded = false

    private val upcomingReservationAdapter = CustomerReservationHistoryAdapter()
    private val pastReservationAdapter = CustomerReservationHistoryAdapter()

    private val orderHistoryAdapter = OrderHistoryAdapter { orderId ->
        val intent = Intent(this, OrderDetailActivity::class.java)
        intent.putExtra("orderId", orderId)
        startActivity(intent)
    }

    private var customerId: String = ""
    private var customerName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            reservationHistoryExpanded = it.getBoolean(STATE_RESERVATION_HISTORY_EXPANDED, false)
            orderHistoryExpanded = it.getBoolean(STATE_ORDER_HISTORY_EXPANDED, false)
        }
        setContentView(R.layout.activity_customer_profile)

        customerId = intent.getStringExtra("customerId") ?: ""
        customerName = intent.getStringExtra("customerName") ?: ""

        if (customerId.isBlank()) {
            Toast.makeText(this, "Invalid customer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        txtProfileName = findViewById(R.id.txtProfileName)
        txtProfilePhone = findViewById(R.id.txtProfilePhone)
        txtProfileEmail = findViewById(R.id.txtProfileEmail)
        txtLifetimeSpend = findViewById(R.id.txtLifetimeSpend)
        txtVisits = findViewById(R.id.txtVisits)
        txtLastVisit = findViewById(R.id.txtLastVisit)
        txtEmptyOrders = findViewById(R.id.txtEmptyOrders)
        recyclerOrderHistory = findViewById(R.id.recyclerOrderHistory)
        txtReservationHistoryTitle = findViewById(R.id.txtReservationHistoryTitle)
        txtEmptyAllReservations = findViewById(R.id.txtEmptyAllReservations)
        txtUpcomingReservationsHeader = findViewById(R.id.txtUpcomingReservationsHeader)
        txtEmptyUpcomingReservations = findViewById(R.id.txtEmptyUpcomingReservations)
        recyclerUpcomingReservations = findViewById(R.id.recyclerUpcomingReservations)
        txtPastReservationsHeader = findViewById(R.id.txtPastReservationsHeader)
        txtEmptyPastReservations = findViewById(R.id.txtEmptyPastReservations)
        recyclerPastReservations = findViewById(R.id.recyclerPastReservations)
        headerReservationHistory = findViewById(R.id.headerReservationHistory)
        groupReservationHistoryBody = findViewById(R.id.groupReservationHistoryBody)
        imgReservationHistoryChevron = findViewById(R.id.imgReservationHistoryChevron)
        headerOrderHistory = findViewById(R.id.headerOrderHistory)
        groupOrderHistoryBody = findViewById(R.id.groupOrderHistoryBody)
        imgOrderHistoryChevron = findViewById(R.id.imgOrderHistoryChevron)

        applyReservationHistoryExpanded()
        applyOrderHistoryExpanded()

        headerReservationHistory.setOnClickListener {
            reservationHistoryExpanded = !reservationHistoryExpanded
            applyReservationHistoryExpanded()
        }
        headerOrderHistory.setOnClickListener {
            orderHistoryExpanded = !orderHistoryExpanded
            applyOrderHistoryExpanded()
        }

        recyclerOrderHistory.layoutManager = LinearLayoutManager(this)
        recyclerOrderHistory.adapter = orderHistoryAdapter

        recyclerUpcomingReservations.layoutManager = LinearLayoutManager(this)
        recyclerUpcomingReservations.adapter = upcomingReservationAdapter
        recyclerPastReservations.layoutManager = LinearLayoutManager(this)
        recyclerPastReservations.adapter = pastReservationAdapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        loadCustomerInfo()
        loadReservationHistory()
        loadOrderHistory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESERVATION_HISTORY_EXPANDED, reservationHistoryExpanded)
        outState.putBoolean(STATE_ORDER_HISTORY_EXPANDED, orderHistoryExpanded)
    }

    private fun applyReservationHistoryExpanded() {
        groupReservationHistoryBody.visibility =
            if (reservationHistoryExpanded) View.VISIBLE else View.GONE
        imgReservationHistoryChevron.rotation = if (reservationHistoryExpanded) 180f else 0f
    }

    private fun applyOrderHistoryExpanded() {
        groupOrderHistoryBody.visibility = if (orderHistoryExpanded) View.VISIBLE else View.GONE
        imgOrderHistoryChevron.rotation = if (orderHistoryExpanded) 180f else 0f
    }

    private fun loadCustomerInfo() {
        db.collection("Customers").document(customerId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                val nameField = doc.getString("name") ?: ""
                val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                    "$firstName $lastName".trim()
                } else {
                    nameField
                }
                val phone = doc.getString("phone") ?: ""
                val email = doc.getString("email") ?: ""

                txtProfileName.text = fullName.ifBlank { "Customer" }

                if (phone.isNotBlank()) {
                    txtProfilePhone.text = formatPhone(phone)
                    txtProfilePhone.visibility = View.VISIBLE
                }

                if (email.isNotBlank()) {
                    txtProfileEmail.text = email
                    txtProfileEmail.visibility = View.VISIBLE
                }
            }
    }

    private fun loadOrderHistory() {
        db.collection("Orders")
            .whereEqualTo("customerId", customerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val orders = mutableListOf<OrderHistoryItem>()
                var totalSpendCents = 0L
                var latestTimestamp: Timestamp? = null
                val dateFormat = SimpleDateFormat("MMM dd", Locale.US)

                for (doc in snap.documents) {
                    val orderNumber = doc.getLong("orderNumber") ?: 0L
                    val orderType = doc.getString("orderType") ?: ""
                    val totalInCents = doc.getLong("totalInCents") ?: 0L
                    val createdAt = doc.getTimestamp("createdAt")
                    val status = doc.getString("status") ?: ""

                    if (status != "VOIDED") {
                        totalSpendCents += totalInCents
                    }

                    if (latestTimestamp == null && createdAt != null) {
                        latestTimestamp = createdAt
                    }

                    val dateStr = if (createdAt != null) {
                        dateFormat.format(createdAt.toDate())
                    } else ""

                    orders.add(
                        OrderHistoryItem(
                            orderId = doc.id,
                            orderNumber = orderNumber,
                            orderType = orderType,
                            totalInCents = totalInCents,
                            dateStr = dateStr
                        )
                    )
                }

                txtLifetimeSpend.text = MoneyUtils.centsToDisplay(totalSpendCents)
                txtVisits.text = orders.size.toString()

                if (latestTimestamp != null) {
                    txtLastVisit.text = dateFormat.format(latestTimestamp.toDate())
                } else {
                    txtLastVisit.text = "—"
                }

                if (orders.isEmpty()) {
                    txtEmptyOrders.visibility = View.VISIBLE
                    recyclerOrderHistory.visibility = View.GONE
                } else {
                    txtEmptyOrders.visibility = View.GONE
                    recyclerOrderHistory.visibility = View.VISIBLE
                    orderHistoryAdapter.submitList(orders)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load orders: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun formatPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "${digits.take(3)}-${digits.drop(3).take(3)}-${digits.takeLast(4)}"
            digits.length == 11 && digits.startsWith("1") -> "${digits.drop(1).take(3)}-${digits.drop(4).take(3)}-${digits.takeLast(4)}"
            else -> phone
        }
    }

    private fun loadReservationHistory() {
        db.collection(ReservationFirestoreHelper.COLLECTION)
            .whereEqualTo(ReservationFirestoreHelper.FIELD_CUSTOMER_ID, customerId)
            .get()
            .addOnSuccessListener { snap ->
                val now = System.currentTimeMillis()
                val slotFmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
                val upcomingRows = mutableListOf<CustomerReservationRow>()
                val pastRows = mutableListOf<CustomerReservationRow>()

                for (doc in snap.documents) {
                    val slotMs = ReservationFirestoreHelper.reservationSlotMillisForExpiry(doc)
                    val row = reservationRowFromDoc(doc, slotFmt, slotMs) ?: continue
                    val active = ReservationFirestoreHelper.isReservationActiveForList(doc)
                    val inFuture = slotMs != null && slotMs >= now
                    val isUpcoming = active && (slotMs == null || inFuture)
                    val sortKey = slotMs ?: if (isUpcoming) Long.MAX_VALUE else Long.MIN_VALUE
                    val rowWithSort = row.copy(sortKeyMillis = sortKey)
                    if (isUpcoming) upcomingRows.add(rowWithSort) else pastRows.add(rowWithSort)
                }

                upcomingRows.sortBy { it.sortKeyMillis }
                pastRows.sortByDescending { it.sortKeyMillis }

                val upcomingDecorated = upcomingRows.mapIndexed { index, r ->
                    if (index == 0) r.copy(emphasize = true) else r.copy(emphasize = false)
                }

                val hasAny = upcomingDecorated.isNotEmpty() || pastRows.isNotEmpty()
                if (!hasAny) {
                    txtReservationHistoryTitle.visibility = View.VISIBLE
                    txtEmptyAllReservations.visibility = View.VISIBLE
                    txtUpcomingReservationsHeader.visibility = View.GONE
                    txtEmptyUpcomingReservations.visibility = View.GONE
                    recyclerUpcomingReservations.visibility = View.GONE
                    txtPastReservationsHeader.visibility = View.GONE
                    txtEmptyPastReservations.visibility = View.GONE
                    recyclerPastReservations.visibility = View.GONE
                    return@addOnSuccessListener
                }

                txtEmptyAllReservations.visibility = View.GONE
                txtReservationHistoryTitle.visibility = View.VISIBLE
                txtUpcomingReservationsHeader.visibility = View.VISIBLE
                recyclerUpcomingReservations.visibility = View.VISIBLE
                if (upcomingDecorated.isEmpty()) {
                    txtEmptyUpcomingReservations.visibility = View.VISIBLE
                    upcomingReservationAdapter.submitList(emptyList())
                } else {
                    txtEmptyUpcomingReservations.visibility = View.GONE
                    upcomingReservationAdapter.submitList(upcomingDecorated)
                }

                txtPastReservationsHeader.visibility = View.VISIBLE
                recyclerPastReservations.visibility = View.VISIBLE
                if (pastRows.isEmpty()) {
                    txtEmptyPastReservations.visibility = View.VISIBLE
                    pastReservationAdapter.submitList(emptyList())
                } else {
                    txtEmptyPastReservations.visibility = View.GONE
                    pastReservationAdapter.submitList(pastRows)
                }
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    getString(R.string.customer_profile_load_reservations_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    private fun reservationRowFromDoc(
        doc: DocumentSnapshot,
        slotFmt: SimpleDateFormat,
        slotMs: Long?,
    ): CustomerReservationRow? {
        if (!doc.exists()) return null
        val id = doc.id
        val tableNames = tableNamesForReservation(doc)
        val party = (doc.getLong("partySize") ?: 0L).toInt().coerceAtLeast(0)
        val dateDisplay = when {
            slotMs != null -> slotFmt.format(Date(slotMs))
            else -> doc.getString("whenText")?.trim().orEmpty().ifBlank { "—" }
        }
        val displayStatus = ReservationFirestoreHelper.reservationStatusForCustomerProfile(doc)
        return CustomerReservationRow(
            reservationId = id,
            tableNames = tableNames,
            partySize = party,
            dateDisplay = dateDisplay,
            displayStatus = displayStatus,
            emphasize = false,
            sortKeyMillis = 0L,
        )
    }

    private fun tableNamesForReservation(doc: DocumentSnapshot): String {
        val name = doc.getString("tableName")?.trim().orEmpty()
        if (name.isNotEmpty()) return name
        val tid = doc.getString("tableId")?.trim().orEmpty()
        return tid.ifEmpty { getString(R.string.reservation_table_placeholder) }
    }
}

data class CustomerReservationRow(
    val reservationId: String,
    val tableNames: String,
    val partySize: Int,
    val dateDisplay: String,
    val displayStatus: String,
    val emphasize: Boolean,
    val sortKeyMillis: Long,
)

class CustomerReservationHistoryAdapter :
    RecyclerView.Adapter<CustomerReservationHistoryAdapter.VH>() {

    private var items: List<CustomerReservationRow> = emptyList()

    fun submitList(list: List<CustomerReservationRow>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_customer_reservation_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val txtTables: TextView = itemView.findViewById(R.id.txtReservationTables)
        private val txtParty: TextView = itemView.findViewById(R.id.txtReservationParty)
        private val txtStatus: TextView = itemView.findViewById(R.id.txtReservationStatus)
        private val txtDate: TextView = itemView.findViewById(R.id.txtReservationDate)

        fun bind(item: CustomerReservationRow) {
            txtTables.text = item.tableNames
            if (item.partySize > 0) {
                txtParty.visibility = View.VISIBLE
                txtParty.text = itemView.context.resources.getQuantityString(
                    R.plurals.table_shape_party_of,
                    item.partySize,
                    item.partySize,
                )
            } else {
                txtParty.visibility = View.GONE
            }
            txtDate.text = item.dateDisplay
            txtStatus.text = item.displayStatus

            val bg: Int
            val fg: Int
            when (item.displayStatus) {
                "ACTIVE" -> {
                    bg = Color.parseColor("#E8F5E9")
                    fg = Color.parseColor("#2E7D32")
                }
                "CANCELLED" -> {
                    bg = Color.parseColor("#FFEBEE")
                    fg = Color.parseColor("#C62828")
                }
                else -> {
                    bg = Color.parseColor("#ECEFF1")
                    fg = Color.parseColor("#546E7A")
                }
            }
            val badge = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    12f,
                    itemView.context.resources.displayMetrics,
                )
            }
            txtStatus.background = badge
            txtStatus.setTextColor(fg)

            val d = itemView.context.resources.displayMetrics.density
            if (item.emphasize) {
                card.strokeWidth = (2f * d).toInt()
                card.strokeColor = Color.parseColor("#6A4FB3")
                card.cardElevation = 4f * d
            } else {
                card.strokeWidth = 0
                card.cardElevation = 1f * d
            }
        }
    }
}

data class OrderHistoryItem(
    val orderId: String,
    val orderNumber: Long,
    val orderType: String,
    val totalInCents: Long,
    val dateStr: String
)

class OrderHistoryAdapter(
    private val onOrderClick: (orderId: String) -> Unit
) : RecyclerView.Adapter<OrderHistoryAdapter.VH>() {

    private var items: List<OrderHistoryItem> = emptyList()

    fun submitList(list: List<OrderHistoryItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_history, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtOrderNumber: TextView = itemView.findViewById(R.id.txtOrderNumber)
        private val txtOrderType: TextView = itemView.findViewById(R.id.txtOrderType)
        private val txtOrderTotal: TextView = itemView.findViewById(R.id.txtOrderTotal)
        private val txtOrderDate: TextView = itemView.findViewById(R.id.txtOrderDate)

        fun bind(item: OrderHistoryItem) {
            txtOrderNumber.text = "#${item.orderNumber}"
            txtOrderTotal.text = MoneyUtils.centsToDisplay(item.totalInCents)
            txtOrderDate.text = item.dateStr

            val typeLabel = when (item.orderType) {
                "DINE_IN" -> "DINE IN"
                "TO_GO" -> "TO GO"
                "BAR_TAB", "BAR" -> "BAR TAB"
                else -> item.orderType
            }
            txtOrderType.text = typeLabel

            val bgColor = when (item.orderType) {
                "DINE_IN" -> "#E8F5E9"
                "TO_GO" -> "#E3F2FD"
                "BAR_TAB", "BAR" -> "#FFF3E0"
                else -> "#F5F5F5"
            }
            val textColor = when (item.orderType) {
                "DINE_IN" -> "#2E7D32"
                "TO_GO" -> "#1565C0"
                "BAR_TAB", "BAR" -> "#E65100"
                else -> "#555555"
            }

            val badge = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 12f
            }
            txtOrderType.background = badge
            txtOrderType.setTextColor(Color.parseColor(textColor))

            itemView.setOnClickListener { onOrderClick(item.orderId) }
        }
    }
}
