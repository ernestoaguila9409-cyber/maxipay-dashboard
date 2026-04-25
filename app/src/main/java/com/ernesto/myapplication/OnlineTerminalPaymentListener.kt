package com.ernesto.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for web-triggered card payment requests ([OnlineTerminalPaymentRequestHelper.COLLECTION])
 * and notifies staff to open [PaymentActivity] so the customer can pay on the Dejavoo (SPIn).
 *
 * Card data is never sent from the web — only order id, number, and amount.
 */
object OnlineTerminalPaymentListener {

    private const val TAG = "OnlineTermPayListen"
    private const val CHANNEL_ID = "online_terminal_payment"
    private const val NOTIF_BASE_ID = 71_000
    private const val MAX_REQUEST_AGE_MS = 15 * 60_000L
    private const val NOTIFY_THROTTLE_MS = 90_000L

    private var registration: ListenerRegistration? = null
    private val lastNotifyMs = ConcurrentHashMap<String, Long>()

    fun start(context: Context) {
        if (registration != null) return
        ensureChannel(context.applicationContext)
        val app = context.applicationContext
        val db = FirebaseFirestore.getInstance()
        registration = db.collection(OnlineTerminalPaymentRequestHelper.COLLECTION)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.w(TAG, "listener: ${e.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val now = System.currentTimeMillis()
                for (doc in snap.documents) {
                    if (doc.getString("status") != "pending") {
                        lastNotifyMs.remove(doc.id)
                        continue
                    }
                    val ts = doc.getTimestamp("requestedAt") ?: continue
                    val age = now - ts.toDate().time
                    if (age > MAX_REQUEST_AGE_MS || age < -120_000L) continue

                    val prev = lastNotifyMs[doc.id] ?: 0L
                    if (now - prev < NOTIFY_THROTTLE_MS) continue
                    lastNotifyMs[doc.id] = now

                    val orderNumber = doc.getLong("orderNumber") ?: 0L
                    val totalCents = doc.getLong("totalInCents") ?: 0L
                    postNotification(app, doc.id, orderNumber, totalCents)
                }
            }
        Log.d(TAG, "Started listener")
    }

    fun stop() {
        registration?.remove()
        registration = null
        lastNotifyMs.clear()
        Log.d(TAG, "Stopped listener")
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Online terminal payments",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when a web customer requests card payment on the Dejavoo terminal."
        }
        mgr.createNotificationChannel(ch)
    }

    private fun postNotification(app: Context, orderId: String, orderNumber: Long, totalCents: Long) {
        val dollars = totalCents / 100.0
        val amt = "$" + String.format(Locale.US, "%.2f", dollars)
        val title = "Web order — card payment"
        val text =
            "Order #$orderNumber · $amt — tap to open checkout (SPIn / Dejavoo; keyed entry on terminal if supported)"

        val launch = Intent(app, PaymentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ORDER_ID", orderId)
        }
        val reqCode = (orderId.hashCode() and 0x7FFF0000) xor NOTIF_BASE_ID
        val pi = PendingIntent.getActivity(
            app,
            reqCode,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(app).notify(reqCode, notification)
        } catch (ex: SecurityException) {
            Log.w(TAG, "Notification not posted (permission?): ${ex.message}")
        }
    }
}
