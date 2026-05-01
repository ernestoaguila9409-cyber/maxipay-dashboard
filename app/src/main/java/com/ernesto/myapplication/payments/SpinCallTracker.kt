package com.ernesto.myapplication.payments

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether any SPIn API call (Sale, Void, Auth, etc.) is currently in
 * flight so that background probes ([PaymentTerminalReachabilitySync]) can
 * skip their tick and avoid colliding with a real transaction on the terminal.
 */
object SpinCallTracker {

    private val inFlightCount = AtomicInteger(0)

    val isInFlight: Boolean get() = inFlightCount.get() > 0

    fun beginCall() { inFlightCount.incrementAndGet() }

    fun endCall() { inFlightCount.updateAndGet { maxOf(it - 1, 0) } }
}
