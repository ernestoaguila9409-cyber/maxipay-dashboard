package com.ernesto.kds.data

/**
 * Settings/kds document — same fields as the MaxiPay web KDS settings page.
 *
 * When [showTimers] is true, ticket body background ages: white until
 * [ticketYellowAfterMinutes], yellow until [ticketRedAfterMinutes], then red
 * (inclusive minute boundaries, same as legacy 5 / 10). When timers are off, the
 * body stays white; order-type header colors are unchanged.
 */
data class KdsDisplaySettings(
    val showTimers: Boolean = true,
    val orderTypeColorsEnabled: Boolean = true,
    val gridColumns: Int = 3,
    val ticketYellowAfterMinutes: Int = DEFAULT_TICKET_YELLOW_AFTER_MINUTES,
    val ticketRedAfterMinutes: Int = DEFAULT_TICKET_RED_AFTER_MINUTES,
) {
    companion object {
        const val DEFAULT_TICKET_YELLOW_AFTER_MINUTES = 5
        const val DEFAULT_TICKET_RED_AFTER_MINUTES = 10
        const val MAX_TICKET_URGENCY_MINUTES = 24 * 60

        /** Clamp and ensure red is not before yellow (Firestore / dashboard validation). */
        fun normalized(
            yellow: Int,
            red: Int,
        ): Pair<Int, Int> {
            var y = yellow.coerceIn(0, MAX_TICKET_URGENCY_MINUTES)
            var r = red.coerceIn(0, MAX_TICKET_URGENCY_MINUTES)
            if (r < y) r = y
            return y to r
        }
    }
}
