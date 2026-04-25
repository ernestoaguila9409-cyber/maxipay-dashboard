package com.ernesto.myapplication

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ReservationActivity : AppCompatActivity() {

    private data class ReservationRow(
        val id: String,
        val guestName: String,
        val tableName: String,
        val partyCount: Int,
        val reservationWhen: String,
        val bookedLine: String,
        val sortTime: Long,
        val canLongPressCancel: Boolean,
    )

    private class ReservationAdapter(
        private val onRowClick: (ReservationRow) -> Unit,
        private val onRowLongClick: (ReservationRow) -> Unit,
    ) : RecyclerView.Adapter<ReservationAdapter.VH>() {
        private val items = mutableListOf<ReservationRow>()

        fun submitList(list: List<ReservationRow>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reservation_row, parent, false)
            return VH(v, onRowClick, onRowLongClick)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        fun isEmpty(): Boolean = items.isEmpty()

        override fun getItemCount(): Int = items.size

        class VH(
            itemView: View,
            private val onRowClick: (ReservationRow) -> Unit,
            private val onRowLongClick: (ReservationRow) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val txtGuest = itemView.findViewById<TextView>(R.id.txtGuest)
            private val txtTable = itemView.findViewById<TextView>(R.id.txtTable)
            private val rowWhen = itemView.findViewById<View>(R.id.rowReservationWhen)
            private val txtReservationWhen = itemView.findViewById<TextView>(R.id.txtReservationWhen)
            private val rowParty = itemView.findViewById<View>(R.id.rowParty)
            private val txtParty = itemView.findViewById<TextView>(R.id.txtParty)
            private val rowBooked = itemView.findViewById<View>(R.id.rowBooked)
            private val txtBooked = itemView.findViewById<TextView>(R.id.txtBooked)

            fun bind(row: ReservationRow) {
                val ctx = itemView.context
                itemView.setOnClickListener { onRowClick(row) }
                itemView.setOnLongClickListener {
                    onRowLongClick(row)
                    true
                }
                txtGuest.text = row.guestName.ifBlank { ctx.getString(R.string.reservation_guest_placeholder) }
                txtTable.text = row.tableName.ifBlank { ctx.getString(R.string.reservation_table_placeholder) }

                if (row.reservationWhen.isNotBlank()) {
                    rowWhen.visibility = View.VISIBLE
                    txtReservationWhen.text = row.reservationWhen
                } else {
                    rowWhen.visibility = View.GONE
                }

                if (row.partyCount > 0) {
                    rowParty.visibility = View.VISIBLE
                    txtParty.text = if (row.partyCount == 1) {
                        ctx.getString(R.string.reservation_guests_one)
                    } else {
                        ctx.getString(R.string.reservation_guests_many, row.partyCount)
                    }
                } else {
                    rowParty.visibility = View.GONE
                }

                if (row.bookedLine.isNotBlank()) {
                    rowBooked.visibility = View.VISIBLE
                    txtBooked.text = row.bookedLine
                } else {
                    rowBooked.visibility = View.GONE
                }
            }
        }
    }

    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: ReservationAdapter
    private lateinit var recyclerReservations: RecyclerView
    private lateinit var reservationEmptyState: View
    private var txtReservationEmptyTitle: TextView? = null
    private var txtReservationEmptySubtitle: TextView? = null
    private var reservationsListener: ListenerRegistration? = null
    private var lastReservationsSnapshot: QuerySnapshot? = null
    private val reservationLayoutGraceListeners = mutableMapOf<String, ListenerRegistration>()

    /**
     * Firestore snapshots only fire when data changes — after [reservationTime] + layout grace,
     * nothing writes by itself, so we must periodically re-run expiry against the last snapshot.
     */
    private val expirySweepHandler = Handler(Looper.getMainLooper())
    private val expirySweepIntervalMs = 30_000L
    private val expirySweepRunnable = object : Runnable {
        override fun run() {
            sweepExpiredReservationsIfNeeded()
            expirySweepHandler.postDelayed(this, expirySweepIntervalMs)
        }
    }

    /** When false, list shows active bookings; when true, ended / cancelled / expired. */
    private var showClosedReservations: Boolean = false

    private var employeeName: String = ""

    private var pendingTableId: String = ""
    private var pendingTableName: String = ""
    private var pendingTableLayoutId: String? = null
    /** All table doc ids in the selected join group (includes [pendingTableId]). */
    private var pendingJoinedTableIds: List<String> = emptyList()
    /**
     * Encoded selection units for re-opening [ReservationTableSelectionActivity]
     * (same format as [ReservationTableSelectionActivity.EXTRA_SELECTION_GROUP_SPECS]).
     */
    private var pendingSelectionGroupSpecs: List<String> = emptyList()
    /** From [ReservationTableSelectionActivity.EXTRA_SCREEN_POSITION_OVERRIDES]; reapplied when re-opening the picker. */
    private var pendingScreenPositionOverridesEncoded: String = ""
    /** Normalized map anchors for Firestore ([ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1]). */
    private var pendingMapUiNormsV1: String = ""
    private var dialogTableLabel: TextView? = null

    private val tablePickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            pendingTableId = data.getStringExtra("tableId") ?: ""
            pendingTableName = data.getStringExtra("tableName") ?: ""
            pendingTableLayoutId = data.getStringExtra("tableLayoutId")?.takeIf { it.isNotBlank() }
            pendingJoinedTableIds = data.getStringArrayListExtra("joinedTableIds")?.toList().orEmpty()
            pendingSelectionGroupSpecs =
                data.getStringArrayListExtra(ReservationTableSelectionActivity.EXTRA_SELECTION_GROUP_SPECS)
                    ?.toList()
                    .orEmpty()
            pendingScreenPositionOverridesEncoded =
                data.getStringExtra(ReservationTableSelectionActivity.EXTRA_SCREEN_POSITION_OVERRIDES).orEmpty()
            pendingMapUiNormsV1 =
                data.getStringExtra(ReservationFirestoreHelper.FIELD_RESERVATION_MAP_UI_NORMS_V1).orEmpty()
            val label = if (pendingTableName.isNotBlank()) pendingTableName else pendingTableId
            dialogTableLabel?.text = "Table: $label"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation)

        employeeName = intent.getStringExtra("employeeName") ?: ""

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = ReservationAdapter(
            onRowClick = { row ->
                startActivity(
                    Intent(this, ReservationMappingActivity::class.java).apply {
                        putExtra(ReservationMappingActivity.EXTRA_RESERVATION_ID, row.id)
                    },
                )
            },
            onRowLongClick = { row ->
                if (!row.canLongPressCancel) {
                    Toast.makeText(
                        this@ReservationActivity,
                        R.string.reservation_long_press_inactive,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    confirmCancelReservation(row)
                }
            },
        )
        reservationEmptyState = findViewById(R.id.reservationEmptyState)
        txtReservationEmptyTitle = findViewById(R.id.txtReservationEmptyTitle)
        txtReservationEmptySubtitle = findViewById(R.id.txtReservationEmptySubtitle)
        recyclerReservations = findViewById(R.id.recyclerReservations)
        recyclerReservations.apply {
            layoutManager = LinearLayoutManager(this@ReservationActivity)
            adapter = this@ReservationActivity.adapter
        }

        findViewById<RadioGroup>(R.id.groupReservationFilter).setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioReservationsActive -> showClosedReservations = false
                R.id.radioReservationsClosed -> showClosedReservations = true
                else -> return@setOnCheckedChangeListener
            }
            lastReservationsSnapshot?.let { applyReservationListFromSnapshot(it) }
        }

        findViewById<FloatingActionButton>(R.id.fabAddReservation).setOnClickListener {
            showCreateReservationDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        listenReservations()
        sweepExpiredReservationsIfNeeded()
        expirySweepHandler.postDelayed(expirySweepRunnable, expirySweepIntervalMs)
    }

    override fun onResume() {
        super.onResume()
        sweepExpiredReservationsIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        expirySweepHandler.removeCallbacks(expirySweepRunnable)
        reservationsListener?.remove()
        reservationsListener = null
        clearReservationLayoutGraceListeners()
        lastReservationsSnapshot = null
    }

    private fun sweepExpiredReservationsIfNeeded() {
        val snap = lastReservationsSnapshot ?: return
        for (doc in snap.documents) {
            if (ReservationFirestoreHelper.shouldReleaseHoldForReservationDoc(doc)) {
                ReservationFirestoreHelper.releaseHoldForExpiredReservationIfNeeded(db, doc.id)
            }
        }
    }

    private fun clearReservationLayoutGraceListeners() {
        reservationLayoutGraceListeners.values.forEach { it.remove() }
        reservationLayoutGraceListeners.clear()
    }

    /**
     * When web updates [reservationGraceAfterSlotMinutes] on a layout, the tables subcollection
     * does not change — listen on the layout doc and re-run expiry checks against the last
     * reservations snapshot.
     */
    private fun syncReservationLayoutGraceListeners(snap: QuerySnapshot) {
        val want = snap.documents
            .mapNotNull { it.getString("tableLayoutId")?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val toRemove = reservationLayoutGraceListeners.keys - want
        for (id in toRemove) {
            reservationLayoutGraceListeners.remove(id)?.remove()
        }
        for (id in want) {
            if (reservationLayoutGraceListeners.containsKey(id)) continue
            reservationLayoutGraceListeners[id] = db.collection("tableLayouts").document(id)
                .addSnapshotListener { _, _ ->
                    lastReservationsSnapshot?.documents?.forEach { doc ->
                        if (ReservationFirestoreHelper.mightTriggerExpiredReservationRelease(doc)) {
                            ReservationFirestoreHelper.releaseHoldForExpiredReservationIfNeeded(db, doc.id)
                        }
                    }
                }
        }
    }

    private fun listenReservations() {
        reservationsListener?.remove()
        clearReservationLayoutGraceListeners()
        lastReservationsSnapshot = null
        reservationsListener = db.collection(ReservationFirestoreHelper.COLLECTION)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Toast.makeText(this, "Failed to load reservations: ${err.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                lastReservationsSnapshot = snap
                syncReservationLayoutGraceListeners(snap)
                for (doc in snap.documents) {
                    if (ReservationFirestoreHelper.shouldReleaseHoldForReservationDoc(doc)) {
                        ReservationFirestoreHelper.releaseHoldForExpiredReservationIfNeeded(db, doc.id)
                    }
                }
                applyReservationListFromSnapshot(snap)
            }
    }

    private fun applyReservationListFromSnapshot(snap: QuerySnapshot) {
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        val rows = snap.documents
            .filter { doc ->
                if (showClosedReservations) {
                    ReservationFirestoreHelper.isReservationClosedTabEligible(doc)
                } else {
                    ReservationFirestoreHelper.isReservationActiveForList(doc)
                }
            }
            .map { doc ->
                val guest = doc.getString("guestName") ?: ""
                val table = doc.getString("tableName") ?: ""
                val party = (doc.getLong("partySize") ?: 0L).toInt()
                val resTime = doc.getTimestamp("reservationTime")
                val whenLegacy = doc.getString("whenText")?.trim().orEmpty()
                val whenDisplay = resTime?.toDate()?.let { fmt.format(it) }
                    ?: whenLegacy.takeIf { it.isNotEmpty() }
                    ?: ""
                val created = doc.getDate("createdAt")
                val sortTime = (resTime?.toDate()?.time ?: created?.time) ?: 0L
                val bookedLine = if (created != null) {
                    getString(R.string.reservation_booked_line, fmt.format(created))
                } else {
                    ""
                }
                val canCancel = ReservationFirestoreHelper.isReservationActiveForList(doc)
                ReservationRow(
                    id = doc.id,
                    guestName = guest,
                    tableName = table,
                    partyCount = party,
                    reservationWhen = whenDisplay,
                    bookedLine = bookedLine,
                    sortTime = sortTime,
                    canLongPressCancel = canCancel,
                )
            }
            .sortedByDescending { it.sortTime }
        adapter.submitList(rows)
        updateEmptyState(adapter.isEmpty())
    }

    private fun confirmCancelReservation(row: ReservationRow) {
        if (!row.canLongPressCancel) {
            Toast.makeText(this, R.string.reservation_long_press_inactive, Toast.LENGTH_SHORT).show()
            return
        }
        val label = row.guestName.ifBlank { getString(R.string.reservation_guest_placeholder) }
        AlertDialog.Builder(this)
            .setTitle(R.string.reservation_cancel_title)
            .setMessage(
                "${getString(R.string.reservation_cancel_message)}\n\n$label · ${row.tableName}",
            )
            .setPositiveButton(R.string.reservation_cancel_confirm) { _, _ ->
                ReservationFirestoreHelper.cancelReservation(
                    db = db,
                    reservationId = row.id,
                    onSuccess = {
                        Toast.makeText(this, R.string.reservation_cancelled, Toast.LENGTH_SHORT).show()
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

    private fun updateEmptyState(isEmpty: Boolean) {
        reservationEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerReservations.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (showClosedReservations) {
            txtReservationEmptyTitle?.setText(R.string.reservation_list_empty_closed_title)
            txtReservationEmptySubtitle?.setText(R.string.reservation_list_empty_closed_subtitle)
        } else {
            txtReservationEmptyTitle?.setText(R.string.reservation_list_empty_title)
            txtReservationEmptySubtitle?.setText(R.string.reservation_list_empty_subtitle)
        }
    }

    private fun showCreateReservationDialog() {
        pendingTableId = ""
        pendingTableName = ""
        pendingTableLayoutId = null
        pendingJoinedTableIds = emptyList()
        pendingSelectionGroupSpecs = emptyList()
        pendingScreenPositionOverridesEncoded = ""
        pendingMapUiNormsV1 = ""

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_reservation, null)
        dialogTableLabel = dialogView.findViewById(R.id.txtSelectedTable)
        dialogTableLabel?.text = "Table: (required — tap Select table)"

        dialogView.findViewById<Button>(R.id.btnPickTable).setOnClickListener {
            val i = Intent(this, ReservationTableSelectionActivity::class.java).apply {
                when {
                    pendingSelectionGroupSpecs.isNotEmpty() -> {
                        putStringArrayListExtra(
                            ReservationTableSelectionActivity.EXTRA_SELECTION_GROUP_SPECS,
                            ArrayList(pendingSelectionGroupSpecs),
                        )
                    }
                    pendingJoinedTableIds.size >= 2 -> {
                        // Older results without specs: best-effort single joined group.
                        putStringArrayListExtra(
                            ReservationTableSelectionActivity.EXTRA_SELECTION_GROUP_SPECS,
                            ArrayList(listOf(pendingJoinedTableIds.joinToString(","))),
                        )
                    }
                    pendingJoinedTableIds.size == 1 -> {
                        putStringArrayListExtra(
                            ReservationTableSelectionActivity.EXTRA_SELECTION_GROUP_SPECS,
                            ArrayList(listOf(pendingJoinedTableIds.first())),
                        )
                    }
                }
                if (pendingScreenPositionOverridesEncoded.isNotBlank()) {
                    putExtra(
                        ReservationTableSelectionActivity.EXTRA_SCREEN_POSITION_OVERRIDES,
                        pendingScreenPositionOverridesEncoded,
                    )
                }
            }
            tablePickLauncher.launch(i)
        }

        val etGuestName = dialogView.findViewById<EditText>(R.id.etGuestName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etPhone)
        val etPartySize = dialogView.findViewById<EditText>(R.id.etPartySize)
        val etNotes = dialogView.findViewById<EditText>(R.id.etNotes)
        val keyboardPanel = dialogView.findViewById<View>(R.id.reservationKeyboardPanel)
        val recyclerCustomerSuggestions =
            dialogView.findViewById<RecyclerView>(R.id.recycler_customer_suggestions)

        val dialog = AppCompatDialog(this).apply {
            setContentView(dialogView)
            setCanceledOnTouchOutside(true)
        }
        val guestAutocomplete = ReservationGuestNameAutocomplete(
            activity = this,
            dialog = dialog,
            guestField = etGuestName,
            phoneField = etPhone,
            suggestionsRecycler = recyclerCustomerSuggestions,
            db = db,
        )
        val activityWindow = window
        val prevNavBarColor = activityWindow.navigationBarColor
        val navInsetsController = WindowInsetsControllerCompat(activityWindow, activityWindow.decorView)
        val prevLightNavBars = navInsetsController.isAppearanceLightNavigationBars
        dialog.setOnDismissListener {
            activityWindow.navigationBarColor = prevNavBarColor
            navInsetsController.isAppearanceLightNavigationBars = prevLightNavBars
            guestAutocomplete.destroy()
        }

        var selectedReservationTimestamp: Timestamp? = null
        val reservationDisplayFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun updateReservationTimeDisplay() {
            val tv = dialogView.findViewById<TextView>(R.id.reservation_time_value)
            val ts = selectedReservationTimestamp
            if (ts == null) {
                tv.setText(R.string.reservation_time_placeholder)
                tv.setTextColor(getColor(R.color.pos_secondary_text))
            } else {
                tv.text = reservationDisplayFmt.format(ts.toDate())
                tv.setTextColor(getColor(R.color.pos_primary_text))
            }
        }

        fun showTimePickerForReservation(year: Int, month: Int, dayOfMonth: Int) {
            val pickedDate = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val now = Calendar.getInstance()
            val isSameDay =
                pickedDate.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    pickedDate.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            val initialHour = if (isSameDay) now.get(Calendar.HOUR_OF_DAY) else 19
            val initialMinute = if (isSameDay) now.get(Calendar.MINUTE) else 0
            val is24h = DateFormat.is24HourFormat(this@ReservationActivity)
            ReservationNumericTimePicker.show(
                activity = this@ReservationActivity,
                title = getString(R.string.reservation_time_picker_title),
                initialHourOfDay = initialHour,
                initialMinute = initialMinute,
                is24Hour = is24h,
                onBeforeShow = {
                    guestAutocomplete.dismissPopup()
                    keyboardPanel.visibility = View.GONE
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    etGuestName.clearFocus()
                    etPhone.clearFocus()
                    etPartySize.clearFocus()
                    etNotes.clearFocus()
                    imm.hideSoftInputFromWindow(dialogView.windowToken, 0)
                    dialog.window?.decorView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
                },
                onAfterDismiss = {
                    keyboardPanel.visibility = View.VISIBLE
                },
            ) { hourOfDay, minute ->
                pickedDate.set(Calendar.HOUR_OF_DAY, hourOfDay)
                pickedDate.set(Calendar.MINUTE, minute)
                pickedDate.set(Calendar.SECOND, 0)
                pickedDate.set(Calendar.MILLISECOND, 0)
                if (pickedDate.timeInMillis < System.currentTimeMillis()) {
                    Toast.makeText(
                        this@ReservationActivity,
                        R.string.reservation_time_past_error,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@show false
                }
                selectedReservationTimestamp = Timestamp(pickedDate.time)
                updateReservationTimeDisplay()
                true
            }
        }

        fun beginReservationTimePick() {
            val today = Calendar.getInstance()
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val maxFuture = Calendar.getInstance().apply { add(Calendar.YEAR, 2) }
            DatePickerDialog(
                this@ReservationActivity,
                { _, year, month, dayOfMonth ->
                    showTimePickerForReservation(year, month, dayOfMonth)
                },
                today.get(Calendar.YEAR),
                today.get(Calendar.MONTH),
                today.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.minDate = startOfToday.timeInMillis
                datePicker.maxDate = maxFuture.timeInMillis
            }.show()
        }

        dialogView.findViewById<View>(R.id.reservation_time_row).setOnClickListener { beginReservationTimePick() }
        val btnDialogCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)
        val btnDialogSave = dialogView.findViewById<MaterialButton>(R.id.btnDialogSave)

        fun attemptSave() {
            val tid = pendingTableId.trim()
            if (tid.isEmpty()) {
                Toast.makeText(this, "Select a table", Toast.LENGTH_SHORT).show()
                return
            }
            val guestName = etGuestName.text.toString().trim()
            if (guestName.isEmpty()) {
                Toast.makeText(this, "Guest name is required", Toast.LENGTH_SHORT).show()
                return
            }
            val party = etPartySize.text.toString().trim().toIntOrNull() ?: 0
            if (party <= 0) {
                Toast.makeText(this, "Party size must be at least 1", Toast.LENGTH_SHORT).show()
                return
            }
            val resTime = selectedReservationTimestamp
            if (resTime == null) {
                Toast.makeText(this, R.string.reservation_time_required, Toast.LENGTH_SHORT).show()
                return
            }
            if (resTime.toDate().time < System.currentTimeMillis()) {
                Toast.makeText(this, R.string.reservation_time_past_error, Toast.LENGTH_SHORT).show()
                return
            }
            val phone = etPhone.text.toString().trim()
            val notes = etNotes.text.toString().trim()
            val tableDisplay = pendingTableName.ifBlank { tid }

            btnDialogSave.isEnabled = false
            val chosenCustomerId = guestAutocomplete.getSelectedCustomerId()

            fun runCreateReservation(customerId: String) {
                val joinedExtras = pendingJoinedTableIds.filter { it.isNotBlank() && it != tid }
                ReservationFirestoreHelper.createReservationWithTable(
                    db = db,
                    tableId = tid,
                    tableLayoutId = pendingTableLayoutId,
                    tableName = tableDisplay,
                    guestName = guestName,
                    phone = phone,
                    partySize = party,
                    notes = notes,
                    reservationTime = resTime,
                    employeeName = employeeName,
                    customerId = customerId,
                    joinedTableIdsForReservation = joinedExtras,
                    reservationMapUiNormsV1 = pendingMapUiNormsV1.takeIf { it.isNotBlank() },
                    onSuccess = {
                        runOnUiThread {
                            Toast.makeText(this, "Reservation saved", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    },
                    onFailure = { e ->
                        runOnUiThread {
                            btnDialogSave.isEnabled = true
                            val msg = reservationErrorMessage(e)
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }

            CustomerFirestoreHelper.ensureCustomerIdForReservation(
                db = db,
                guestName = guestName,
                phone = phone,
                preferredCustomerId = chosenCustomerId,
                onResult = { cid ->
                    runOnUiThread {
                        if (!dialog.isShowing) {
                            btnDialogSave.isEnabled = true
                            return@runOnUiThread
                        }
                        runCreateReservation(cid)
                    }
                },
                onError = { e ->
                    runOnUiThread {
                        btnDialogSave.isEnabled = true
                        Toast.makeText(
                            this,
                            e.message ?: "Could not resolve customer",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        btnDialogCancel.setOnClickListener { dialog.dismiss() }
        btnDialogSave.setOnClickListener { attemptSave() }

        dialog.setOnShowListener {
            activityWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            activityWindow.navigationBarColor = ContextCompat.getColor(this@ReservationActivity, R.color.white)
            navInsetsController.isAppearanceLightNavigationBars = true
            val maxH = (resources.displayMetrics.heightPixels * 0.92f).toInt().coerceAtLeast(400)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
            )
            guestAutocomplete.start()
            ReservationDialogKeyboardHelper(
                context = this,
                keyboardRoot = keyboardPanel,
                fields = listOf(etGuestName, etPhone, etPartySize, etNotes),
                onAnyFieldFocusChange = { et, hasFocus ->
                    guestAutocomplete.onFieldFocus(et, hasFocus)
                },
            ).start()
        }
        dialog.show()
    }

    private fun reservationErrorMessage(e: Exception): String {
        if (e is FirebaseFirestoreException) {
            when (e.code) {
                FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                    return "Table is not available"
                FirebaseFirestoreException.Code.NOT_FOUND ->
                    return "Table not found"
                else -> { }
            }
        }
        val m = e.message ?: ""
        return when {
            m.contains("Table is not available", ignoreCase = true) -> "Table is not available"
            else -> m.ifBlank { "Failed to save reservation" }
        }
    }
}
