package com.ernesto.myapplication

import java.util.ArrayDeque

/**
 * FIFO queue for [OnlineOrderAlertPayload] with a single active slot ([busy]).
 * [offer] rejects duplicates already waiting; the coordinator should also skip the
 * document currently on screen.
 */
class OnlineOrderAlertQueue {

    private val deque = ArrayDeque<OnlineOrderAlertPayload>()
    private val lock = Any()

    @Volatile
    var busy: Boolean = false
        private set

    fun offer(payload: OnlineOrderAlertPayload): Boolean {
        synchronized(lock) {
            if (deque.any { it.orderId == payload.orderId }) return false
            deque.addLast(payload)
            return true
        }
    }

    fun acquireNextOrNull(): OnlineOrderAlertPayload? {
        synchronized(lock) {
            if (busy) return null
            val next = deque.pollFirst() ?: return null
            busy = true
            return next
        }
    }

    fun releaseBusy() {
        synchronized(lock) {
            busy = false
        }
    }

    /**
     * Restores a payload that was [acquireNextOrNull] but could not be shown (e.g. activity finishing).
     */
    fun rollbackAfterFailedShow(payload: OnlineOrderAlertPayload) {
        synchronized(lock) {
            busy = false
            deque.addFirst(payload)
        }
    }

    fun pendingCount(): Int {
        synchronized(lock) {
            return deque.size
        }
    }
}
