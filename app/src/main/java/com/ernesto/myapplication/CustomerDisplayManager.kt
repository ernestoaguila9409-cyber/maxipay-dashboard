package com.ernesto.myapplication

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

enum class DisplayState {
    IDLE,
    ORDER,
    TIP,
    PAYMENT,
    PROCESSING,
    SUCCESS,
    RECEIPT_OPTIONS,
    EMAIL_INPUT,
    DECLINED
}

object CustomerDisplayManager {

    private const val TAG = "CustomerDisplay"

    private var presentation: CustomerDisplayPresentation? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentState: DisplayState = DisplayState.IDLE
    private var businessName: String = ""
    private var orderItems: List<CustomerOrderLine> = emptyList()
    private var orderTotalCents: Long = 0L
    private var orderSummary: OrderSummaryInfo = OrderSummaryInfo()
    private var tipTotalCents: Long = 0L
    private var tipBaseCents: Long = 0L
    private var tipBaseLabel: String = ""
    private var tipPresets: List<Int> = emptyList()
    private var tipShowCustom: Boolean = false
    private var onTipSelected: ((Long) -> Unit)? = null
    private var paymentTotalCents: Long = 0L
    private var paymentIsCash: Boolean = false
    private var declinedMessage: String = ""
    private var paymentSuccessInfo: PaymentSuccessInfo? = null
    private var onReceiptOptionFromCustomer: ((ReceiptOption) -> Unit)? = null
    private var onEmailSubmittedFromCustomer: ((String) -> Unit)? = null
    private var onEmailCancelledFromCustomer: (() -> Unit)? = null

    // ── Display detection ───────────────────────────────────────────

    private fun getSecondaryDisplay(activity: Activity): Display? {
        val dm = activity.getSystemService(Activity.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return displays.firstOrNull()
    }

    private fun ensurePresentation(activity: Activity): CustomerDisplayPresentation? {
        val current = presentation
        if (current != null && current.isShowing) {
            return current
        }

        val display = getSecondaryDisplay(activity) ?: run {
            Log.d(TAG, "No secondary display found")
            return null
        }

        return try {
            val p = CustomerDisplayPresentation(activity, display)
            p.show()
            presentation = p
            Log.d(TAG, "Presentation shown on display: ${display.name}")
            p
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show presentation", e)
            null
        }
    }

    /**
     * Attach to an activity and restore the current state on the customer display.
     * Call from onResume() of every activity that participates in the POS flow.
     */
    fun attach(activity: Activity) {
        val p = ensurePresentation(activity) ?: return
        restoreState(p)
    }

    private fun restoreState(p: CustomerDisplayPresentation) {
        when (currentState) {
            DisplayState.IDLE -> p.showIdle(businessName)
            DisplayState.ORDER -> p.showOrder(businessName, orderItems, orderTotalCents, orderSummary)
            DisplayState.TIP -> p.showTipScreen(
                businessName, tipTotalCents, tipBaseCents, tipBaseLabel, tipPresets, tipShowCustom, onTipSelected
            )
            DisplayState.PAYMENT -> {
                if (paymentIsCash) p.showCashPayment(businessName, paymentTotalCents)
                else p.showPaymentWaiting(businessName, paymentTotalCents)
            }
            DisplayState.PROCESSING -> p.showProcessing()
            DisplayState.SUCCESS -> {
                val info = paymentSuccessInfo ?: PaymentSuccessInfo(false, paymentTotalCents)
                p.showPaymentApproved(info, showReceiptChoice = false, onReceiptOption = null)
            }
            DisplayState.RECEIPT_OPTIONS -> {
                val info = paymentSuccessInfo ?: PaymentSuccessInfo(false, paymentTotalCents)
                p.showPaymentApproved(info, showReceiptChoice = true, onReceiptOption = onReceiptOptionFromCustomer)
            }
            DisplayState.EMAIL_INPUT -> {
                p.showEmailInput(
                    onSubmit = { email -> onEmailSubmittedFromCustomer?.invoke(email) },
                    onCancel = { onEmailCancelledFromCustomer?.invoke() }
                )
            }
            DisplayState.DECLINED -> p.showDeclined(declinedMessage)
        }
    }

    // ── IDLE ────────────────────────────────────────────────────────

    fun setIdle(activity: Activity, name: String) {
        businessName = name
        currentState = DisplayState.IDLE
        val p = ensurePresentation(activity) ?: return
        p.showIdle(name)
    }

    // ── ORDER ───────────────────────────────────────────────────────

    fun updateOrder(
        activity: Activity,
        name: String,
        items: List<CustomerOrderLine>,
        totalCents: Long,
        summary: OrderSummaryInfo = OrderSummaryInfo()
    ) {
        businessName = name
        orderItems = items
        orderTotalCents = totalCents
        orderSummary = summary
        currentState = DisplayState.ORDER
        val p = ensurePresentation(activity) ?: return
        p.showOrder(name, items, totalCents, summary)
    }

    // ── TIP ─────────────────────────────────────────────────────────

    fun showTipScreen(
        activity: Activity,
        name: String,
        totalCents: Long,
        baseCents: Long,
        baseLabel: String,
        presets: List<Int>,
        showCustomTip: Boolean,
        onTipSelected: ((Long) -> Unit)? = null
    ) {
        businessName = name
        tipTotalCents = totalCents
        tipBaseCents = baseCents
        tipBaseLabel = baseLabel
        tipPresets = presets
        tipShowCustom = showCustomTip
        this.onTipSelected = onTipSelected
        currentState = DisplayState.TIP
        val p = ensurePresentation(activity) ?: return
        p.showTipScreen(name, totalCents, baseCents, baseLabel, presets, showCustomTip, onTipSelected)
    }

    // ── PAYMENT ─────────────────────────────────────────────────────

    fun showPaymentWaiting(activity: Activity, name: String, totalCents: Long) {
        businessName = name
        paymentTotalCents = totalCents
        paymentIsCash = false
        currentState = DisplayState.PAYMENT
        val p = ensurePresentation(activity) ?: return
        p.showPaymentWaiting(name, totalCents)
    }

    fun showCashPayment(activity: Activity, name: String, totalCents: Long) {
        businessName = name
        paymentTotalCents = totalCents
        paymentIsCash = true
        currentState = DisplayState.PAYMENT
        val p = ensurePresentation(activity) ?: return
        p.showCashPayment(name, totalCents)
    }

    // ── PROCESSING ──────────────────────────────────────────────────

    fun showProcessing(activity: Activity) {
        currentState = DisplayState.PROCESSING
        val p = ensurePresentation(activity) ?: return
        p.showProcessing()
    }

    // ── SUCCESS ─────────────────────────────────────────────────────

    fun setPaymentSuccessInfo(info: PaymentSuccessInfo) {
        paymentSuccessInfo = info
        paymentTotalCents = info.amountChargedCents
    }

    fun getPaymentSuccessInfo(): PaymentSuccessInfo? = paymentSuccessInfo

    fun clearPaymentSuccessInfo() {
        paymentSuccessInfo = null
    }

    /**
     * Customer display: payment approved (cash shows tendered / change when applicable).
     */
    fun showPaymentApproved(activity: Activity, info: PaymentSuccessInfo) {
        paymentSuccessInfo = info
        paymentTotalCents = info.amountChargedCents
        currentState = DisplayState.SUCCESS
        onReceiptOptionFromCustomer = null
        val p = ensurePresentation(activity) ?: return
        p.showPaymentApproved(info, showReceiptChoice = false, onReceiptOption = null)
    }

    /** Legacy: generic success without cash breakdown. */
    fun showSuccess(activity: Activity, totalCents: Long) {
        val info = PaymentSuccessInfo(isCash = false, amountChargedCents = totalCents)
        showPaymentApproved(activity, info)
    }

    /**
     * Receipt phase on customer display: same approval summary + receipt choice buttons.
     */
    fun showReceiptOptionsOnCustomerDisplay(
        activity: Activity,
        onOption: (ReceiptOption) -> Unit
    ) {
        val info = paymentSuccessInfo ?: return
        currentState = DisplayState.RECEIPT_OPTIONS
        onReceiptOptionFromCustomer = onOption
        val p = ensurePresentation(activity) ?: return
        p.showPaymentApproved(info, showReceiptChoice = true, onReceiptOption = onOption)
    }

    fun clearReceiptOptionCallback() {
        onReceiptOptionFromCustomer = null
    }

    /**
     * Email input on customer display: shows QWERTY keyboard for the customer to type their email.
     */
    fun showEmailInputOnCustomerDisplay(
        activity: Activity,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        currentState = DisplayState.EMAIL_INPUT
        onEmailSubmittedFromCustomer = onSubmit
        onEmailCancelledFromCustomer = onCancel
        val p = ensurePresentation(activity) ?: return
        p.showEmailInput(onSubmit = onSubmit, onCancel = onCancel)
    }

    fun clearEmailInputCallbacks() {
        onEmailSubmittedFromCustomer = null
        onEmailCancelledFromCustomer = null
    }

    /**
     * Show success briefly, then transition to IDLE after [delayMs].
     */
    fun showSuccessThenIdle(activity: Activity, totalCents: Long, delayMs: Long = 3000L) {
        showSuccess(activity, totalCents)
        handler.postDelayed({
            setIdle(activity, businessName)
        }, delayMs)
    }

    // ── DECLINED ────────────────────────────────────────────────────

    fun showDeclined(activity: Activity, message: String = "Please try again") {
        declinedMessage = message
        currentState = DisplayState.DECLINED
        val p = ensurePresentation(activity) ?: return
        p.showDeclined(message)
    }

    /**
     * Show declined briefly, then revert to payment waiting after [delayMs].
     */
    fun showDeclinedThenPayment(activity: Activity, message: String, delayMs: Long = 3000L) {
        showDeclined(activity, message)
        handler.postDelayed({
            showPaymentWaiting(activity, businessName, paymentTotalCents)
        }, delayMs)
    }

    /**
     * Show declined briefly, then revert to order summary after [delayMs].
     */
    fun showDeclinedThenOrder(activity: Activity, message: String, delayMs: Long = 3000L) {
        showDeclined(activity, message)
        handler.postDelayed({
            currentState = DisplayState.ORDER
            val p = ensurePresentation(activity) ?: return@postDelayed
            p.showOrder(businessName, orderItems, orderTotalCents, orderSummary)
        }, delayMs)
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /**
     * Dismiss the presentation. Use sparingly — prefer keeping it alive
     * and switching to IDLE instead.
     */
    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss presentation", e)
        }
        presentation = null
    }

    fun getState(): DisplayState = currentState

    /** True when a secondary customer presentation is showing (email/receipt flows can use it). */
    fun hasCustomerDisplayAttached(): Boolean {
        val p = presentation ?: return false
        return try {
            p.isShowing
        } catch (_: Exception) {
            false
        }
    }
}
