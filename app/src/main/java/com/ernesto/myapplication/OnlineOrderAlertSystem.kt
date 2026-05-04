package com.ernesto.myapplication

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Wires Firestore new-document detection, chime + optional vibration, FIFO queue, and dialog UX.
 * Call [attach] from [AppCompatActivity.onCreate] (once per activity instance).
 *
 * **Firestore path / fields:** Defaults follow your spec (`source == ONLINE`, `status == OPEN`).
 * This codebase often uses collection `"Orders"` and `orderSource` — pass constructor args to match.
 */
class OnlineOrderAlertSystem(
    private val activity: AppCompatActivity,
    private val firestore: FirebaseFirestore,
    private val getEmployeeName: () -> String,
    private val ordersCollectionPath: String = OnlineOrderAlertFirestoreListener.DEFAULT_ORDERS_COLLECTION,
    private val sourceField: String = OnlineOrderAlertFirestoreListener.DEFAULT_SOURCE_FIELD,
    private val sourceValue: String = OnlineOrderAlertFirestoreListener.DEFAULT_SOURCE_VALUE,
    private val statusField: String = OnlineOrderAlertFirestoreListener.DEFAULT_STATUS_FIELD,
    private val statusValue: String = OnlineOrderAlertFirestoreListener.DEFAULT_STATUS_VALUE,
    private val vibrateOnNewOrder: Boolean = true,
) {

    private val soundPlayer = NewOrderSoundPlayer(activity)
    private val queue = OnlineOrderAlertQueue()
    private val dialogPresenter = OnlineOrderAlertDialogPresenter(activity)

    private val firestoreListener = OnlineOrderAlertFirestoreListener(
        firestore = firestore,
        ordersCollectionPath = ordersCollectionPath,
        sourceField = sourceField,
        sourceValue = sourceValue,
        statusField = statusField,
        statusValue = statusValue,
        onNewOnlineOpenOrder = { payload ->
            activity.runOnUiThread {
                handleNewOrderOnMainThread(payload)
            }
        },
    )

    private val cleanupObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            activity.lifecycle.removeObserver(firestoreListener)
            activity.lifecycle.removeObserver(this)
            dialogPresenter.dismissCurrent()
            queue.releaseBusy()
            soundPlayer.release()
        }
    }

    fun attach() {
        activity.lifecycle.addObserver(firestoreListener)
        activity.lifecycle.addObserver(cleanupObserver)
    }

    private fun handleNewOrderOnMainThread(payload: OnlineOrderAlertPayload) {
        if (activity.isFinishing || activity.isDestroyed) return
        soundPlayer.playNewOrderChime()
        if (vibrateOnNewOrder) {
            vibrateShort()
        }
        if (!queue.offer(payload)) return
        tryShowNextFromQueue()
    }

    private fun tryShowNextFromQueue() {
        if (activity.isFinishing || activity.isDestroyed) return
        val next = queue.acquireNextOrNull() ?: return
        val shown = dialogPresenter.show(
            payload = next,
            onViewOrder = { orderId ->
                OnlineOrderAwaitingStaffReviewDialog.showFromOrderId(
                    activity,
                    firestore,
                    orderId,
                    getEmployeeName(),
                )
            },
            onFullyDismissed = {
                queue.releaseBusy()
                tryShowNextFromQueue()
            },
        )
        if (!shown) {
            queue.rollbackAfterFailedShow(next)
            tryShowNextFromQueue()
        }
    }

    private fun vibrateShort() {
        val duration = 140L
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vib = activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
                if (vib?.hasVibrator() == true) {
                    vib.vibrate(
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val v = activity.getSystemService(Vibrator::class.java)
                if (v?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(
                            VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(duration)
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}
