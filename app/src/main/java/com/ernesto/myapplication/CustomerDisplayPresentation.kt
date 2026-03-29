package com.ernesto.myapplication

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ernesto.myapplication.engine.MoneyUtils

class CustomerDisplayPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    private lateinit var idleContainer: LinearLayout
    private lateinit var orderContainer: LinearLayout
    private lateinit var tipContainer: LinearLayout
    private lateinit var paymentContainer: LinearLayout
    private lateinit var processingContainer: LinearLayout
    private lateinit var successContainer: ScrollView
    private lateinit var declinedContainer: LinearLayout

    private lateinit var txtIdleBusinessName: TextView

    private lateinit var txtOrderBusinessName: TextView
    private lateinit var orderItemsContainer: LinearLayout
    private lateinit var txtOrderTotal: TextView

    private lateinit var txtTipBusinessName: TextView
    private lateinit var txtCustTipTotal: TextView
    private lateinit var txtCustTipBaseLabel: TextView
    private lateinit var custPresetContainer: LinearLayout
    private lateinit var txtCustCustomLabel: TextView
    private lateinit var txtCustNoTip: TextView

    // Keypad overlay
    private lateinit var custKeypadOverlay: LinearLayout
    private lateinit var txtKeypadAmount: TextView
    private lateinit var keypadGrid: LinearLayout
    private lateinit var btnKeypadCancel: TextView
    private lateinit var btnKeypadAddTip: TextView
    private val keypadBuffer = StringBuilder()

    private lateinit var txtPayBusinessName: TextView
    private lateinit var txtPayTotal: TextView
    private lateinit var txtPayIcon: TextView
    private lateinit var txtPayMessage: TextView

    private lateinit var txtSuccessTitle: TextView
    private lateinit var txtSuccessAmountLabel: TextView
    private lateinit var txtSuccessTotal: TextView
    private lateinit var layoutCashPaymentDetail: LinearLayout
    private lateinit var layoutSuccessChange: LinearLayout
    private lateinit var txtSuccessCashTendered: TextView
    private lateinit var txtSuccessChange: TextView
    private lateinit var receiptSectionDivider: View
    private lateinit var receiptChoiceSection: LinearLayout
    private lateinit var btnCustReceiptPrint: TextView
    private lateinit var btnCustReceiptEmail: TextView
    private lateinit var btnCustReceiptSms: TextView
    private lateinit var btnCustReceiptSkip: TextView
    private lateinit var txtDeclinedMessage: TextView

    // Email input overlay
    private lateinit var custEmailOverlay: LinearLayout
    private lateinit var txtEmailDisplay: TextView
    private lateinit var emailKeyboardGrid: LinearLayout
    private lateinit var btnEmailCancel: TextView
    private lateinit var btnEmailSubmit: TextView
    private val emailBuffer = StringBuilder()
    private var emailShiftActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_customer_display)

        idleContainer = findViewById(R.id.idleContainer)
        orderContainer = findViewById(R.id.orderContainer)
        tipContainer = findViewById(R.id.tipContainer)
        paymentContainer = findViewById(R.id.paymentContainer)
        processingContainer = findViewById(R.id.processingContainer)
        successContainer = findViewById(R.id.successContainer)
        declinedContainer = findViewById(R.id.declinedContainer)

        txtIdleBusinessName = findViewById(R.id.txtIdleBusinessName)

        txtOrderBusinessName = findViewById(R.id.txtOrderBusinessName)
        orderItemsContainer = findViewById(R.id.orderItemsContainer)
        txtOrderTotal = findViewById(R.id.txtOrderTotal)

        txtTipBusinessName = findViewById(R.id.txtTipBusinessName)
        txtCustTipTotal = findViewById(R.id.txtCustTipTotal)
        txtCustTipBaseLabel = findViewById(R.id.txtCustTipBaseLabel)
        custPresetContainer = findViewById(R.id.custPresetContainer)
        txtCustCustomLabel = findViewById(R.id.txtCustCustomLabel)
        txtCustNoTip = findViewById(R.id.txtCustNoTip)

        custKeypadOverlay = findViewById(R.id.custKeypadOverlay)
        txtKeypadAmount = findViewById(R.id.txtKeypadAmount)
        keypadGrid = findViewById(R.id.keypadGrid)
        btnKeypadCancel = findViewById(R.id.btnKeypadCancel)
        btnKeypadAddTip = findViewById(R.id.btnKeypadAddTip)

        txtPayBusinessName = findViewById(R.id.txtPayBusinessName)
        txtPayTotal = findViewById(R.id.txtPayTotal)
        txtPayIcon = findViewById(R.id.txtPayIcon)
        txtPayMessage = findViewById(R.id.txtPayMessage)

        txtSuccessTitle = findViewById(R.id.txtSuccessTitle)
        txtSuccessAmountLabel = findViewById(R.id.txtSuccessAmountLabel)
        txtSuccessTotal = findViewById(R.id.txtSuccessTotal)
        layoutCashPaymentDetail = findViewById(R.id.layoutCashPaymentDetail)
        layoutSuccessChange = findViewById(R.id.layoutSuccessChange)
        txtSuccessCashTendered = findViewById(R.id.txtSuccessCashTendered)
        txtSuccessChange = findViewById(R.id.txtSuccessChange)
        receiptSectionDivider = findViewById(R.id.receiptSectionDivider)
        receiptChoiceSection = findViewById(R.id.receiptChoiceSection)
        btnCustReceiptPrint = findViewById(R.id.btnCustReceiptPrint)
        btnCustReceiptEmail = findViewById(R.id.btnCustReceiptEmail)
        btnCustReceiptSms = findViewById(R.id.btnCustReceiptSms)
        btnCustReceiptSkip = findViewById(R.id.btnCustReceiptSkip)
        txtDeclinedMessage = findViewById(R.id.txtDeclinedMessage)

        custEmailOverlay = findViewById(R.id.custEmailOverlay)
        txtEmailDisplay = findViewById(R.id.txtEmailDisplay)
        emailKeyboardGrid = findViewById(R.id.emailKeyboardGrid)
        btnEmailCancel = findViewById(R.id.btnEmailCancel)
        btnEmailSubmit = findViewById(R.id.btnEmailSubmit)

        buildKeypadGrid()
        buildEmailKeyboard()
    }

    private fun hideAll() {
        idleContainer.visibility = View.GONE
        orderContainer.visibility = View.GONE
        tipContainer.visibility = View.GONE
        paymentContainer.visibility = View.GONE
        processingContainer.visibility = View.GONE
        successContainer.visibility = View.GONE
        declinedContainer.visibility = View.GONE
        custKeypadOverlay.visibility = View.GONE
        custEmailOverlay.visibility = View.GONE
    }

    // ── IDLE ────────────────────────────────────────────────────────

    fun showIdle(businessName: String) {
        hideAll()
        txtIdleBusinessName.text = businessName
        idleContainer.visibility = View.VISIBLE
    }

    // ── ORDER ───────────────────────────────────────────────────────

    fun showOrder(
        businessName: String,
        items: List<CustomerOrderLine>,
        totalCents: Long,
        summary: OrderSummaryInfo = OrderSummaryInfo()
    ) {
        hideAll()
        txtOrderBusinessName.text = businessName
        txtOrderTotal.text = MoneyUtils.centsToDisplay(totalCents)

        orderItemsContainer.removeAllViews()

        if (items.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No items yet"
                textSize = 16f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(24), 0, dpToPx(24))
            }
            orderItemsContainer.addView(empty)
        } else {
            for ((index, line) in items.withIndex()) {
                if (index > 0) {
                    orderItemsContainer.addView(makeDivider())
                }
                orderItemsContainer.addView(buildOrderLineView(line))
            }
        }

        val hasSummary = summary.subtotalCents > 0L
                || summary.discountLines.isNotEmpty()
                || summary.taxLines.isNotEmpty()
                || summary.tipCents > 0L

        if (hasSummary) {
            orderItemsContainer.addView(makeSummaryDivider())

            if (summary.subtotalCents > 0L) {
                orderItemsContainer.addView(
                    buildSummaryRow("Subtotal", MoneyUtils.centsToDisplay(summary.subtotalCents), Color.parseColor("#CCCCCC"))
                )
            }

            for (dl in summary.discountLines) {
                orderItemsContainer.addView(
                    buildSummaryRow(dl.label, "-${MoneyUtils.centsToDisplay(dl.amountCents)}", Color.parseColor("#66BB6A"))
                )
            }

            for (tl in summary.taxLines) {
                orderItemsContainer.addView(
                    buildSummaryRow(tl.label, MoneyUtils.centsToDisplay(tl.amountCents), Color.parseColor("#B0A3D4"))
                )
            }

            if (summary.tipCents > 0L) {
                orderItemsContainer.addView(
                    buildSummaryRow("Tip", MoneyUtils.centsToDisplay(summary.tipCents), Color.parseColor("#CCCCCC"))
                )
            }
        }

        orderContainer.visibility = View.VISIBLE
    }

    private fun buildSummaryRow(label: String, value: String, color: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(2), 0, dpToPx(2))

            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(color)
            })
        }
    }

    private fun makeSummaryDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(8)
                bottomMargin = dpToPx(8)
            }
            setBackgroundColor(Color.parseColor("#2A2A40"))
        }
    }

    private fun buildOrderLineView(line: CustomerOrderLine): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameText = TextView(context).apply {
            text = if (line.quantity > 1) "${line.quantity}x  ${line.name}" else line.name
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val priceText = TextView(context).apply {
            text = MoneyUtils.centsToDisplay(line.lineTotalCents)
            textSize = 16f
            setTextColor(Color.parseColor("#B0A3D4"))
        }

        headerRow.addView(nameText)
        headerRow.addView(priceText)
        container.addView(headerRow)

        for (mod in line.modifiers) {
            val modText = TextView(context).apply {
                text = mod
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dpToPx(16), dpToPx(2), 0, 0)
            }
            container.addView(modText)
        }

        return container
    }

    private fun makeDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
            setBackgroundColor(Color.parseColor("#2A2A40"))
        }
    }

    // ── TIP ─────────────────────────────────────────────────────────

    fun showTipScreen(
        businessName: String,
        totalCents: Long,
        baseCents: Long,
        baseLabel: String,
        presets: List<Int>,
        showCustomTip: Boolean,
        onTipSelected: ((Long) -> Unit)? = null
    ) {
        hideAll()

        txtTipBusinessName.text = businessName
        txtCustTipTotal.text = "Order Total: ${MoneyUtils.centsToDisplay(totalCents)}"
        txtCustTipBaseLabel.text = baseLabel

        custPresetContainer.removeAllViews()
        val presetCards = mutableListOf<LinearLayout>()
        val cardHeight = dpToPx(80)
        val spacing = dpToPx(12)

        for (pct in presets) {
            val tipCents = roundCents(baseCents * pct / 100.0)
            val tipDisplay = MoneyUtils.centsToDisplay(tipCents)

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_cust_tip_option)
                layoutParams = LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    setMargins(spacing / 2, 0, spacing / 2, 0)
                }
                setPadding(0, dpToPx(12), 0, dpToPx(12))
                isClickable = true
                isFocusable = true
            }

            val pctLabel = TextView(context).apply {
                text = "$pct%"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }

            val amtLabel = TextView(context).apply {
                text = tipDisplay
                textSize = 14f
                setTextColor(Color.parseColor("#B0A3D4"))
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(2), 0, 0)
            }

            card.addView(pctLabel)
            card.addView(amtLabel)
            presetCards.add(card)

            card.setOnClickListener {
                for (c in presetCards) c.setBackgroundResource(R.drawable.bg_cust_tip_option)
                card.setBackgroundResource(R.drawable.bg_tip_option_selected)
                onTipSelected?.invoke(tipCents)
            }

            custPresetContainer.addView(card)
        }

        if (showCustomTip) {
            txtCustCustomLabel.visibility = View.VISIBLE
            txtCustCustomLabel.setOnClickListener {
                showKeypad(
                    onConfirm = { tipCents ->
                        hideKeypad()
                        onTipSelected?.invoke(tipCents)
                    },
                    onCancel = { hideKeypad() }
                )
            }
        } else {
            txtCustCustomLabel.visibility = View.GONE
        }

        txtCustNoTip.visibility = View.VISIBLE
        txtCustNoTip.setOnClickListener {
            for (c in presetCards) c.setBackgroundResource(R.drawable.bg_cust_tip_option)
            onTipSelected?.invoke(0L)
        }

        tipContainer.visibility = View.VISIBLE
    }

    // ── Custom Tip Keypad ────────────────────────────────────────────

    private fun buildKeypadGrid() {
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫")
        )
        val btnSize = dpToPx(64)
        val btnMargin = dpToPx(5)

        for (row in keys) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (key in row) {
                val btn = TextView(context).apply {
                    text = key
                    textSize = 24f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                        setMargins(btnMargin, btnMargin, btnMargin, btnMargin)
                    }
                    setBackgroundResource(R.drawable.bg_keypad_button)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onKeypadPress(key) }
                }
                rowLayout.addView(btn)
            }
            keypadGrid.addView(rowLayout)
        }
    }

    private fun showKeypad(onConfirm: (Long) -> Unit, onCancel: () -> Unit) {
        keypadBuffer.clear()
        refreshKeypadDisplay()
        custKeypadOverlay.visibility = View.VISIBLE

        btnKeypadAddTip.setOnClickListener {
            val amount = keypadBuffer.toString().toDoubleOrNull()
            if (amount != null && amount > 0) {
                onConfirm(MoneyUtils.dollarsToCents(amount))
            }
        }

        btnKeypadCancel.setOnClickListener { onCancel() }
    }

    private fun hideKeypad() {
        keypadBuffer.clear()
        custKeypadOverlay.visibility = View.GONE
    }

    private fun onKeypadPress(key: String) {
        when (key) {
            "⌫" -> {
                if (keypadBuffer.isNotEmpty()) {
                    keypadBuffer.deleteCharAt(keypadBuffer.length - 1)
                }
            }
            "." -> {
                if (!keypadBuffer.contains(".")) {
                    if (keypadBuffer.isEmpty()) keypadBuffer.append("0")
                    keypadBuffer.append(".")
                }
            }
            else -> {
                val dotIndex = keypadBuffer.indexOf(".")
                if (dotIndex >= 0 && keypadBuffer.length - dotIndex > 2) return
                if (keypadBuffer.length >= 7) return
                keypadBuffer.append(key)
            }
        }
        refreshKeypadDisplay()
    }

    private fun refreshKeypadDisplay() {
        txtKeypadAmount.text = if (keypadBuffer.isEmpty()) "$0.00" else "$$keypadBuffer"
    }

    // ── EMAIL INPUT ─────────────────────────────────────────────────

    fun showEmailInput(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        hideAll()
        emailBuffer.clear()
        emailShiftActive = false
        refreshEmailDisplay()
        rebuildEmailLetterRows()
        custEmailOverlay.visibility = View.VISIBLE

        btnEmailSubmit.setOnClickListener {
            val email = emailBuffer.toString().trim()
            if (email.isNotEmpty() && email.contains("@") && email.contains(".")) {
                onSubmit(email)
            }
        }
        btnEmailCancel.setOnClickListener { onCancel() }
    }

    fun hideEmailInput() {
        emailBuffer.clear()
        custEmailOverlay.visibility = View.GONE
    }

    private fun buildEmailKeyboard() {
        val btnH = dpToPx(42)
        val btnM = dpToPx(3)

        val letterRows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m")
        )

        val numberRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val numKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val numKeyW = dpToPx(34)
        for (key in numKeys) {
            numberRow.addView(makeEmailKey(key, numKeyW, btnH, btnM, Color.parseColor("#333355")))
        }
        emailKeyboardGrid.addView(numberRow)

        for (row in letterRows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (key in row) {
                rowLayout.addView(makeEmailKey(key, dpToPx(38), btnH, btnM))
            }
            emailKeyboardGrid.addView(rowLayout)
        }

        val shiftDeleteRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shiftDeleteRow.addView(makeEmailKey("⇧", dpToPx(56), btnH, btnM, Color.parseColor("#444466")))
        shiftDeleteRow.addView(makeEmailKey("@", dpToPx(38), btnH, btnM))
        shiftDeleteRow.addView(makeEmailKey(".", dpToPx(38), btnH, btnM))
        shiftDeleteRow.addView(makeEmailKey("-", dpToPx(38), btnH, btnM))
        shiftDeleteRow.addView(makeEmailKey("_", dpToPx(38), btnH, btnM))
        shiftDeleteRow.addView(makeEmailKey("⌫", dpToPx(56), btnH, btnM, Color.parseColor("#444466")))
        emailKeyboardGrid.addView(shiftDeleteRow)

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (shortcut in listOf(".com", ".net", ".org")) {
            val btn = TextView(context).apply {
                text = shortcut
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(64), btnH).apply {
                    setMargins(btnM, btnM, btnM, btnM)
                }
                setBackgroundResource(R.drawable.bg_keypad_button)
                isClickable = true
                isFocusable = true
                setOnClickListener { onEmailKeyPress(shortcut) }
            }
            bottomRow.addView(btn)
        }
        val spaceBar = TextView(context).apply {
            text = "space"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(100), btnH).apply {
                setMargins(btnM, btnM, btnM, btnM)
            }
            setBackgroundResource(R.drawable.bg_keypad_button)
            isClickable = true
            isFocusable = true
            setOnClickListener { /* no space in emails – ignore */ }
        }
        spaceBar.visibility = View.GONE
        bottomRow.addView(spaceBar)
        emailKeyboardGrid.addView(bottomRow)
    }

    private fun makeEmailKey(
        key: String, w: Int, h: Int, m: Int,
        bgColor: Int? = null
    ): TextView {
        return TextView(context).apply {
            text = key
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                setMargins(m, m, m, m)
            }
            if (bgColor != null) {
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dpToPx(10).toFloat()
                }
                background = gd
            } else {
                setBackgroundResource(R.drawable.bg_keypad_button)
            }
            isClickable = true
            isFocusable = true
            tag = key
            setOnClickListener { onEmailKeyPress(key) }
        }
    }

    private fun onEmailKeyPress(key: String) {
        when (key) {
            "⌫" -> {
                if (emailBuffer.isNotEmpty()) {
                    emailBuffer.deleteCharAt(emailBuffer.length - 1)
                }
            }
            "⇧" -> {
                emailShiftActive = !emailShiftActive
                rebuildEmailLetterRows()
                return
            }
            ".com", ".net", ".org" -> {
                emailBuffer.append(key)
            }
            else -> {
                if (emailBuffer.length < 64) {
                    emailBuffer.append(key)
                }
                if (emailShiftActive) {
                    emailShiftActive = false
                    rebuildEmailLetterRows()
                }
            }
        }
        refreshEmailDisplay()
    }

    private fun rebuildEmailLetterRows() {
        val letterRows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m")
        )
        val letterRowOffset = 1

        for (i in letterRows.indices) {
            val rowLayout = emailKeyboardGrid.getChildAt(letterRowOffset + i) as? LinearLayout ?: continue
            for (j in letterRows[i].indices) {
                val btn = rowLayout.getChildAt(j) as? TextView ?: continue
                val letter = letterRows[i][j]
                val display = if (emailShiftActive) letter.uppercase() else letter
                btn.text = display
                btn.tag = display
                btn.setOnClickListener { onEmailKeyPress(display) }
            }
        }
    }

    private fun refreshEmailDisplay() {
        txtEmailDisplay.text = if (emailBuffer.isEmpty()) "" else emailBuffer.toString()
    }

    // ── PAYMENT ─────────────────────────────────────────────────────

    fun showPaymentWaiting(businessName: String, totalCents: Long) {
        hideAll()
        txtPayBusinessName.text = businessName
        txtPayTotal.text = MoneyUtils.centsToDisplay(totalCents)
        txtPayIcon.text = "💳"
        txtPayMessage.text = "Please Insert or Tap Card\non PINPAD"
        paymentContainer.visibility = View.VISIBLE
    }

    fun showCashPayment(businessName: String, totalCents: Long) {
        hideAll()
        txtPayBusinessName.text = businessName
        txtPayTotal.text = MoneyUtils.centsToDisplay(totalCents)
        txtPayIcon.text = "💵"
        txtPayMessage.text = "Cash Payment"
        paymentContainer.visibility = View.VISIBLE
    }

    fun showProcessing() {
        hideAll()
        processingContainer.visibility = View.VISIBLE
    }

    // ── SUCCESS ─────────────────────────────────────────────────────

    fun showSuccess(totalCents: Long) {
        showPaymentApproved(
            PaymentSuccessInfo(isCash = false, amountChargedCents = totalCents),
            showReceiptChoice = false,
            onReceiptOption = null
        )
    }

    /**
     * @param showReceiptChoice When true, shows receipt buttons (ReceiptOptions phase).
     */
    fun showPaymentApproved(
        info: PaymentSuccessInfo,
        showReceiptChoice: Boolean,
        onReceiptOption: ((ReceiptOption) -> Unit)?
    ) {
        hideAll()
        bindPaymentSuccessContent(info)
        val showReceipt = showReceiptChoice && onReceiptOption != null
        receiptSectionDivider.visibility = if (showReceipt) View.VISIBLE else View.GONE
        receiptChoiceSection.visibility = if (showReceipt) View.VISIBLE else View.GONE
        bindReceiptChoiceListeners(if (showReceipt) onReceiptOption else null)
        successContainer.visibility = View.VISIBLE
    }

    private fun bindPaymentSuccessContent(info: PaymentSuccessInfo) {
        txtSuccessTitle.text = if (info.isCash) "Cash Payment Approved" else "Payment Approved"
        txtSuccessAmountLabel.text = "Amount paid"
        txtSuccessTotal.text = MoneyUtils.centsToDisplay(info.amountChargedCents)

        if (info.isCash) {
            layoutCashPaymentDetail.visibility = View.VISIBLE
            txtSuccessCashTendered.text = MoneyUtils.centsToDisplay(info.tenderedCents)
            if (info.changeCents > 0L) {
                layoutSuccessChange.visibility = View.VISIBLE
                txtSuccessChange.text = MoneyUtils.centsToDisplay(info.changeCents)
            } else {
                layoutSuccessChange.visibility = View.GONE
            }
        } else {
            layoutCashPaymentDetail.visibility = View.GONE
        }
    }

    private fun bindReceiptChoiceListeners(onReceiptOption: ((ReceiptOption) -> Unit)?) {
        val h = onReceiptOption
        btnCustReceiptPrint.setOnClickListener { h?.invoke(ReceiptOption.PRINT) }
        btnCustReceiptEmail.setOnClickListener { h?.invoke(ReceiptOption.EMAIL) }
        btnCustReceiptSms.setOnClickListener { h?.invoke(ReceiptOption.SMS) }
        btnCustReceiptSkip.setOnClickListener { h?.invoke(ReceiptOption.SKIP) }
    }

    // ── DECLINED ────────────────────────────────────────────────────

    fun showDeclined(message: String = "Please try again") {
        hideAll()
        txtDeclinedMessage.text = message
        declinedContainer.visibility = View.VISIBLE
    }

    // ── Utilities ───────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun roundCents(value: Double): Long {
        return java.math.BigDecimal(value)
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .toLong()
    }
}
