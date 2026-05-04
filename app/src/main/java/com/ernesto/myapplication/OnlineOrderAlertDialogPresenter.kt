package com.ernesto.myapplication

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import com.ernesto.myapplication.databinding.DialogOnlineOrderAlertBinding

/**
 * Centered POS-style dialog: title, subtitle (order # + items), customer, primary action.
 * Auto-dismiss after [autoDismissMs]. Single active dialog; [dismissCurrent] is idempotent.
 */
class OnlineOrderAlertDialogPresenter(
    private val activity: AppCompatActivity,
    private val autoDismissMs: Long = 5_500L,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AppCompatDialog? = null
    private var dismissRunnable: Runnable? = null

    /**
     * @return false if the activity cannot host a dialog (caller should roll the payload back into the queue).
     */
    fun show(
        payload: OnlineOrderAlertPayload,
        onViewOrder: (orderId: String) -> Unit,
        onFullyDismissed: () -> Unit,
    ): Boolean {
        dismissCurrent()
        if (activity.isFinishing || activity.isDestroyed) {
            return false
        }

        val binding = DialogOnlineOrderAlertBinding.inflate(LayoutInflater.from(activity))
        binding.txtTitle.setText(R.string.online_order_alert_title)
        binding.txtSubtitle.text = activity.getString(
            R.string.online_order_alert_subtitle,
            payload.orderNumber,
            payload.itemCount,
        )
        binding.txtCustomerName.text = payload.customerName

        val dlg = AppCompatDialog(activity, R.style.OnlineOrderAlertDialogTheme).apply {
            setContentView(binding.root)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val maxPx = (560f * activity.resources.displayMetrics.density).toInt()
            val w = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                .coerceAtMost(maxPx)
            window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        var openDetailOnDismiss = false

        binding.btnViewOrder.setOnClickListener {
            openDetailOnDismiss = true
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null
            dlg.dismiss()
        }

        dlg.setOnDismissListener {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null
            dialog = null
            if (openDetailOnDismiss) {
                onViewOrder(payload.orderId)
            }
            onFullyDismissed()
        }

        dismissRunnable = Runnable {
            if (dialog === dlg && dlg.isShowing) {
                dlg.dismiss()
            }
        }
        handler.postDelayed(dismissRunnable!!, autoDismissMs)

        dialog = dlg
        dlg.show()
        return true
    }

    fun dismissCurrent() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        dialog?.dismiss()
        dialog = null
    }
}
