package com.ernesto.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Routes post-payment receipt behavior based on [ReceiptPrintingConfig].
 */
object ReceiptPostPaymentFlow {

    const val EXTRA_AUTO_PRINT_ON_OPEN = "AUTO_PRINT_ON_OPEN"

    fun launchAfterOrderPaid(
        activity: Activity,
        orderId: String,
        customerEmail: String? = null,
        finishCaller: Boolean = true,
    ) {
        when (ReceiptPrintingConfig.getMode(activity)) {
            ReceiptPrintingConfig.MODE_DO_NOT_PRINT -> {
                navigateToMain(activity)
                if (finishCaller) activity.finish()
            }
            ReceiptPrintingConfig.MODE_AUTO_PRINT_AND_ASK -> {
                val intent = receiptOptionsIntent(activity, orderId, customerEmail, autoPrintOnOpen = true)
                activity.startActivity(intent)
                if (finishCaller) activity.finish()
            }
            else -> {
                val intent = receiptOptionsIntent(activity, orderId, customerEmail, autoPrintOnOpen = false)
                activity.startActivity(intent)
                if (finishCaller) activity.finish()
            }
        }
    }

    fun receiptOptionsIntent(
        context: Context,
        orderId: String,
        customerEmail: String? = null,
        autoPrintOnOpen: Boolean = false,
    ): Intent =
        Intent(context, ReceiptOptionsActivity::class.java).apply {
            putExtra("ORDER_ID", orderId)
            if (!customerEmail.isNullOrBlank()) putExtra("CUSTOMER_EMAIL", customerEmail)
            putExtra(EXTRA_AUTO_PRINT_ON_OPEN, autoPrintOnOpen)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun navigateToMain(activity: Activity) {
        CustomerDisplayManager.clearPaymentSuccessInfo()
        CustomerDisplayManager.clearReceiptOptionCallback()
        CustomerDisplayManager.clearEmailInputCallbacks()
        CustomerDisplayManager.setIdle(activity, ReceiptSettings.load(activity).businessName)
        val intent = Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
    }
}
