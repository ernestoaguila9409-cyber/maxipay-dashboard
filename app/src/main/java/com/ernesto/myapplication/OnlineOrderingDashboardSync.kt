package com.ernesto.myapplication

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "OnlineOrderingDash"

private enum class OpenOverride { AUTO, OPEN, CLOSED }

private data class DayHoursRow(
    val openForDay: Boolean,
    val openTime: String,
    val closeTime: String,
)

private data class ParsedOnlineOrdering(
    /** False when the Firestore doc is missing — keep the ONLINE tile visible (legacy installs). */
    val documentExists: Boolean,
    val enabled: Boolean,
    val openOverride: OpenOverride,
    val businessHoursEnforced: Boolean,
    val businessHoursTimezone: String,
    val businessHoursWeekly: List<DayHoursRow>,
) {
    companion object {
        fun defaultWhenDocMissing(): ParsedOnlineOrdering =
            ParsedOnlineOrdering(
                documentExists = false,
                enabled = true,
                openOverride = OpenOverride.AUTO,
                businessHoursEnforced = false,
                businessHoursTimezone = "America/New_York",
                businessHoursWeekly = defaultWeekly(),
            )

        /** Document exists (may be empty map). */
        fun fromExistingDocument(data: Map<String, Any>?): ParsedOnlineOrdering {
            val d = data ?: emptyMap()
            val weekly = parseWeeklyHours(d["businessHoursWeekly"])
            return ParsedOnlineOrdering(
                documentExists = true,
                enabled = d["enabled"] == true,
                openOverride = parseOverride(d["openStatusOverride"]),
                businessHoursEnforced = d["businessHoursEnforced"] == true,
                businessHoursTimezone =
                    (d["businessHoursTimezone"] as? String)?.trim()
                        ?: "America/New_York",
                businessHoursWeekly = weekly,
            )
        }

        private fun parseOverride(raw: Any?): OpenOverride {
            val s = (raw as? String)?.trim()?.uppercase(Locale.US) ?: return OpenOverride.AUTO
            return when (s) {
                "OPEN" -> OpenOverride.OPEN
                "CLOSED" -> OpenOverride.CLOSED
                else -> OpenOverride.AUTO
            }
        }

        private fun defaultWeekly(): List<DayHoursRow> =
            List(7) {
                DayHoursRow(openForDay = true, openTime = "09:00", closeTime = "21:00")
            }

        @Suppress("UNCHECKED_CAST")
        private fun parseWeeklyHours(raw: Any?): List<DayHoursRow> {
            val base = defaultWeekly()
            val list = raw as? List<*> ?: return base
            return List(7) { idx ->
                val row = list.getOrNull(idx) as? Map<String, Any?> ?: return@List base[idx]
                val def = base[idx]
                val openT = (row["openTime"] as? String)?.let { normalizeTimeHm(it) } ?: def.openTime
                val closeT = (row["closeTime"] as? String)?.let { normalizeTimeHm(it) } ?: def.closeTime
                DayHoursRow(
                    openForDay = row["openForDay"] != false,
                    openTime = openT,
                    closeTime = closeT,
                )
            }
        }
    }
}

private fun normalizeTimeHm(raw: String): String? {
    val m = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?$""").find(raw.trim()) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || min !in 0..59) return null
    return "%02d:%02d".format(Locale.US, h, min)
}

/** Maps [Calendar.DAY_OF_WEEK] to JS weekday: Sunday = 0 … Saturday = 6. */
private fun calendarDayToJsWeekday(calDay: Int): Int =
    when (calDay) {
        Calendar.SUNDAY -> 0
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 0
    }

private fun zonedWeekdayAndMinutes(nowMillis: Long, ianaId: String): Pair<Int, Int>? {
    val tzId = ianaId.trim()
    if (tzId.isEmpty()) return null
    return try {
        val tz = TimeZone.getTimeZone(tzId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
        val wd = calendarDayToJsWeekday(cal.get(Calendar.DAY_OF_WEEK))
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        wd to minutes
    } catch (_: Exception) {
        null
    }
}

private fun parseHmToMinutes(s: String): Int? {
    val n = normalizeTimeHm(s) ?: return null
    val parts = n.split(":")
    val h = parts[0].toInt()
    val m = parts[1].toInt()
    return h * 60 + m
}

private fun isWithinWeeklyBusinessHours(s: ParsedOnlineOrdering, nowMillis: Long): Boolean {
    val tz = s.businessHoursTimezone.trim()
    if (tz.isEmpty()) return false
    val zoned = zonedWeekdayAndMinutes(nowMillis, tz) ?: return false
    val (weekday, minutes) = zoned
    val row = s.businessHoursWeekly.getOrNull(weekday) ?: return false
    if (!row.openForDay) return false
    val openM = parseHmToMinutes(row.openTime) ?: return false
    val closeM = parseHmToMinutes(row.closeTime) ?: return false
    if (openM == closeM) return false
    val c = minutes
    return if (openM < closeM) {
        c in openM until closeM
    } else {
        c >= openM || c < closeM
    }
}

private fun isStoreCurrentlyOpen(s: ParsedOnlineOrdering, nowMillis: Long): Boolean {
    when (s.openOverride) {
        OpenOverride.OPEN -> return true
        OpenOverride.CLOSED -> return false
        OpenOverride.AUTO -> {
            if (!s.enabled) return false
            if (!s.businessHoursEnforced) return true
            return isWithinWeeklyBusinessHours(s, nowMillis)
        }
    }
}

/**
 * Tracks `Settings/onlineOrdering` so the main dashboard can hide the ONLINE tile when the
 * public storefront would show closed (same rules as the web dashboard).
 */
object OnlineOrderingDashboardSync {

    @Volatile
    private var parsed: ParsedOnlineOrdering = ParsedOnlineOrdering.defaultWhenDocMissing()

    private var registration: ListenerRegistration? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            notifyListeners()
            val s = parsed
            if (s.documentExists && s.businessHoursEnforced) {
                mainHandler.postDelayed(this, 60_000L)
            }
        }
    }

    fun shouldShowOnlineOrdersTile(): Boolean {
        val s = parsed
        if (!s.documentExists) return true
        return isStoreCurrentlyOpen(s, System.currentTimeMillis())
    }

    fun addListener(onChange: () -> Unit) {
        listeners.addIfAbsent(onChange)
    }

    fun removeListener(onChange: () -> Unit) {
        listeners.remove(onChange)
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try {
                l.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "listener failed", e)
            }
        }
    }

    fun start(db: FirebaseFirestore) {
        registration?.remove()
        registration =
            db.collection("Settings").document("onlineOrdering").addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "onlineOrdering listener error", err)
                    return@addSnapshotListener
                }
                parsed =
                    if (snap == null || !snap.exists()) {
                        ParsedOnlineOrdering.defaultWhenDocMissing()
                    } else {
                        ParsedOnlineOrdering.fromExistingDocument(snap.data)
                    }
                notifyListeners()
                scheduleMinuteRecheckIfNeeded()
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
        mainHandler.removeCallbacks(tickRunnable)
        parsed = ParsedOnlineOrdering.defaultWhenDocMissing()
    }

    /** When business hours are enforced, re-evaluate open/closed on the wall clock without a doc write. */
    private fun scheduleMinuteRecheckIfNeeded() {
        mainHandler.removeCallbacks(tickRunnable)
        val s = parsed
        if (s.documentExists && s.businessHoursEnforced) {
            mainHandler.postDelayed(tickRunnable, 60_000L)
        }
    }
}
