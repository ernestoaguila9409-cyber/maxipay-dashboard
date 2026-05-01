package com.ernesto.myapplication

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import java.util.TimeZone

/**
 * Edits `Settings/onlineOrdering` business-hour fields so they stay in sync with the
 * MaxiPay web dashboard (`onlineOrderingShared` / BusinessHoursSection).
 */
class OnlineOrderingBusinessHoursActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var switchEnforced: SwitchCompat
    private lateinit var spinnerTz: Spinner
    private lateinit var layoutCustomTz: LinearLayout
    private lateinit var editCustomTz: EditText
    private lateinit var containerDays: LinearLayout
    private lateinit var btnSave: MaterialButton

    private val spinnerLabels = ArrayList<String>()
    private val spinnerValues = ArrayList<String>()

    /** Firestore weekday index: 0 = Sunday … 6 = Saturday. */
    private val rowByDayIndex = mutableMapOf<Int, View>()
    private val dayDisplayOrder = intArrayOf(1, 2, 3, 4, 5, 6, 0)

    private var suppressSpinnerCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online_ordering_business_hours)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Business hours"

        switchEnforced = findViewById(R.id.switchBusinessHoursEnforced)
        spinnerTz = findViewById(R.id.spinnerTimezonePreset)
        layoutCustomTz = findViewById(R.id.layoutCustomTimezone)
        editCustomTz = findViewById(R.id.editCustomTimezone)
        containerDays = findViewById(R.id.containerDayRows)
        btnSave = findViewById(R.id.btnSave)

        buildTimezoneSpinner()
        inflateDayRows()

        btnSave.setOnClickListener { saveToFirestore() }

        spinnerTz.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressSpinnerCallback) return
                val v = spinnerValues.getOrNull(position) ?: return
                if (v == OTHER_TZ) {
                    layoutCustomTz.visibility = View.VISIBLE
                } else {
                    layoutCustomTz.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    }

    override fun onResume() {
        super.onResume()
        loadFromFirestore()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildTimezoneSpinner() {
        spinnerLabels.clear()
        spinnerValues.clear()
        for ((value, label) in TIMEZONE_PRESETS) {
            spinnerValues.add(value)
            spinnerLabels.add(label)
        }
        spinnerValues.add(OTHER_TZ)
        spinnerLabels.add("Other…")
        spinnerTz.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            spinnerLabels,
        )
    }

    private fun inflateDayRows() {
        containerDays.removeAllViews()
        rowByDayIndex.clear()
        val inflater = layoutInflater
        for (dayIdx in dayDisplayOrder) {
            val row = inflater.inflate(R.layout.item_online_ordering_day_hours, containerDays, false)
            row.findViewById<TextView>(R.id.txtDayName).text = DAY_NAMES.getValue(dayIdx)
            rowByDayIndex[dayIdx] = row
            containerDays.addView(row)
        }
    }

    private fun loadFromFirestore() {
        db.collection(SETTINGS).document(ONLINE_ORDERING_DOC)
            .get()
            .addOnSuccessListener { snap ->
                val enforced = snap.getBoolean("businessHoursEnforced") == true
                switchEnforced.isChecked = enforced

                val tz = snap.getString("businessHoursTimezone")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "America/New_York"
                applyTimezoneToSpinner(tz)

                @Suppress("UNCHECKED_CAST")
                val weekly = snap.get("businessHoursWeekly") as? List<Map<String, Any>>
                val parsed = parseWeeklyFromFirestore(weekly)
                for (i in 0 until 7) {
                    applyDayRow(i, parsed[i])
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Could not load: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun applyTimezoneToSpinner(tz: String) {
        suppressSpinnerCallback = true
        val ix = spinnerValues.indexOf(tz)
        if (ix >= 0) {
            spinnerTz.setSelection(ix)
            layoutCustomTz.visibility = View.GONE
            editCustomTz.setText("")
        } else {
            spinnerTz.setSelection(spinnerValues.lastIndex)
            layoutCustomTz.visibility = View.VISIBLE
            editCustomTz.setText(tz)
        }
        suppressSpinnerCallback = false
    }

    private fun applyDayRow(dayIndex: Int, row: DayRow) {
        val v = rowByDayIndex[dayIndex] ?: return
        v.findViewById<SwitchCompat>(R.id.switchOpenForDay).isChecked = row.openForDay
        v.findViewById<EditText>(R.id.editOpenTime).setText(row.openTime)
        v.findViewById<EditText>(R.id.editCloseTime).setText(row.closeTime)
    }

    private fun readDayRow(dayIndex: Int): DayRow? {
        val v = rowByDayIndex[dayIndex] ?: return null
        val openForDay = v.findViewById<SwitchCompat>(R.id.switchOpenForDay).isChecked
        val openRaw = v.findViewById<EditText>(R.id.editOpenTime).text?.toString()?.trim().orEmpty()
        val closeRaw = v.findViewById<EditText>(R.id.editCloseTime).text?.toString()?.trim().orEmpty()
        val openT = normalizeTimeHm(openRaw) ?: return null
        val closeT = normalizeTimeHm(closeRaw) ?: return null
        return DayRow(openForDay, openT, closeT)
    }

    private fun selectedTimeZoneId(): String? {
        val pos = spinnerTz.selectedItemPosition
        val v = spinnerValues.getOrNull(pos) ?: return null
        return if (v == OTHER_TZ) {
            editCustomTz.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
        } else {
            v
        }
    }

    private fun saveToFirestore() {
        val tz = selectedTimeZoneId()
        if (tz.isNullOrBlank()) {
            Toast.makeText(this, "Select or enter a time zone.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isValidIanaTimeZone(tz)) {
            Toast.makeText(this, "Unknown time zone ID. Use an IANA name like America/Chicago.", Toast.LENGTH_LONG).show()
            return
        }

        val weeklyMaps = ArrayList<Map<String, Any>>(7)
        for (i in 0 until 7) {
            val row = readDayRow(i)
            if (row == null) {
                Toast.makeText(
                    this,
                    "Invalid time on ${DAY_NAMES[i]} (use HH:mm, e.g. 09:00).",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            weeklyMaps.add(
                mapOf(
                    "openForDay" to row.openForDay,
                    "openTime" to row.openTime,
                    "closeTime" to row.closeTime,
                ),
            )
        }

        val enforced = switchEnforced.isChecked
        val payload = hashMapOf<String, Any>(
            "businessHoursEnforced" to enforced,
            "businessHoursTimezone" to tz,
            "businessHoursWeekly" to weeklyMaps,
        )

        db.collection(SETTINGS).document(ONLINE_ORDERING_DOC)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "Saved — web dashboard will see the same hours.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private data class DayRow(
        val openForDay: Boolean,
        val openTime: String,
        val closeTime: String,
    )

    companion object {
        private const val SETTINGS = "Settings"
        private const val ONLINE_ORDERING_DOC = "onlineOrdering"
        private const val OTHER_TZ = "__OTHER__"

        private val TIMEZONE_PRESETS = listOf(
            "America/New_York" to "US — Eastern",
            "America/Chicago" to "US — Central",
            "America/Denver" to "US — Mountain",
            "America/Phoenix" to "US — Arizona",
            "America/Los_Angeles" to "US — Pacific",
            "America/Anchorage" to "US — Alaska",
            "Pacific/Honolulu" to "US — Hawaii",
            "America/Toronto" to "Canada — Eastern",
            "America/Vancouver" to "Canada — Pacific",
            "Europe/London" to "UK",
            "Europe/Paris" to "Central Europe",
            "UTC" to "UTC",
            "Asia/Dubai" to "Gulf",
            "Asia/Tokyo" to "Japan",
            "Australia/Sydney" to "Australia — Sydney",
        )

        private val DAY_NAMES = mapOf(
            0 to "Sunday",
            1 to "Monday",
            2 to "Tuesday",
            3 to "Wednesday",
            4 to "Thursday",
            5 to "Friday",
            6 to "Saturday",
        )

        private val validTzIds: Set<String> by lazy { TimeZone.getAvailableIDs().toSet() }

        private fun normalizeTimeHm(raw: String): String? {
            val m = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?$""").find(raw.trim()) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || min !in 0..59) return null
            return "%02d:%02d".format(Locale.US, h, min)
        }

        private fun defaultWeekly(): List<DayRow> =
            List(7) { DayRow(openForDay = true, openTime = "09:00", closeTime = "21:00") }

        private fun parseWeeklyFromFirestore(raw: List<Map<String, Any>>?): List<DayRow> {
            val base = defaultWeekly().toMutableList()
            if (raw == null) return base
            for (i in 0 until 7) {
                val row = raw.getOrNull(i) ?: continue
                val openForDay = row["openForDay"] != false
                val openT = (row["openTime"] as? String)?.let { normalizeTimeHm(it) }
                val closeT = (row["closeTime"] as? String)?.let { normalizeTimeHm(it) }
                base[i] = DayRow(
                    openForDay = openForDay,
                    openTime = openT ?: base[i].openTime,
                    closeTime = closeT ?: base[i].closeTime,
                )
            }
            return base
        }

        private fun isValidIanaTimeZone(tz: String): Boolean {
            val t = tz.trim()
            if (t.isEmpty()) return false
            return validTzIds.contains(t)
        }
    }
}
