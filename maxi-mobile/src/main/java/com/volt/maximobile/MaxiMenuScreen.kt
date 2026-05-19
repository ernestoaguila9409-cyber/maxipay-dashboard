package com.volt.maximobile

import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.volt.shared.engine.DiscountDisplay
import com.volt.shared.engine.MoneyUtils
import com.volt.shared.engine.OrderTaxCalculator
import com.volt.shared.engine.OrderTaxLine
import com.volt.shared.engine.OrderTaxRule
import java.util.Locale

/**
 * Portrait order screen for every order channel: vertical categories, searchable items,
 * sticky review bar, and a cart bottom sheet instead of a permanent cart column.
 */
@Composable
fun MaxiMenuOrderContent(
    orderTitle: String,
    orderSubtitle: String,
    categories: List<CatUi>,
    selectedCategoryId: String?,
    items: List<ItemUi>,
    cart: List<CartLine>,
    taxes: List<OrderTaxRule> = emptyList(),
    searchQuery: String,
    busy: Boolean,
    checkoutEnabled: Boolean,
    guestCount: Int = 0,
    guestNames: List<String> = emptyList(),
    selectedGuest: Int = 1,
    onGuestSelected: (Int) -> Unit = {},
    onBack: () -> Unit,
    onCategorySelected: (CatUi) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onItemClick: (ItemUi) -> Unit,
    onIncreaseLine: (Int) -> Unit,
    onDecreaseLine: (Int) -> Unit,
    onCheckoutClick: () -> Unit,
) {
    val filteredItems = remember(items, searchQuery) {
        val query = searchQuery.trim().lowercase(Locale.US)
        if (query.isEmpty()) items
        else items.filter { it.name.lowercase(Locale.US).contains(query) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val root = LayoutInflater.from(context).inflate(R.layout.activity_order, null, false)
            val categoryList = root.findViewById<RecyclerView>(R.id.categoryList)
            val menuItemList = root.findViewById<RecyclerView>(R.id.menuItemList)
            val searchInput = root.findViewById<TextInputEditText>(R.id.searchInput)
            val progress = root.findViewById<ProgressBar>(R.id.orderProgress)
            val reviewButton = root.findViewById<LinearLayout>(R.id.reviewOrderButton)
            val reviewLabel = root.findViewById<TextView>(R.id.reviewOrderLabel)
            val titleView = root.findViewById<TextView>(R.id.orderTitle)
            val subtitleView = root.findViewById<TextView>(R.id.orderSubtitle)
            val backButton = root.findViewById<ImageButton>(R.id.orderBackButton)
            val guestChipScroller = root.findViewById<HorizontalScrollView>(R.id.guestChipScroller)
            val guestChipGroup = root.findViewById<ChipGroup>(R.id.guestChipGroup)

            val categoryAdapter = OrderCategoryAdapter(onCategorySelected)
            categoryList.layoutManager = LinearLayoutManager(context)
            categoryList.adapter = categoryAdapter

            val menuAdapter = OrderMenuItemAdapter(onItemClick)
            menuItemList.layoutManager = LinearLayoutManager(context)
            menuItemList.adapter = menuAdapter

            var cartDialog: BottomSheetDialog? = null
            var cartLineList: RecyclerView? = null
            var cartEmptyMessage: TextView? = null
            var cartSubtotalValue: TextView? = null
            var cartTaxContainer: LinearLayout? = null
            var cartTotalValue: TextView? = null
            var cartCheckoutButton: MaterialButton? = null
            var cartLineAdapter: OrderCartLineAdapter? = null
            fun formatMoney(amount: Double): String =
                "$${String.format(Locale.US, "%.2f", amount)}"

            fun cartTotals(lines: List<CartLine>, taxRules: List<OrderTaxRule>): Pair<Double, Double> {
                val subtotal = lines.sumOf { it.unitPriceDollars * it.quantity }
                val taxLines = lines.map { line ->
                    OrderTaxLine(
                        lineKey = line.menuItemId,
                        lineTotalDollars = line.unitPriceDollars * line.quantity,
                        taxMode = line.taxMode,
                        taxIds = line.taxIds,
                    )
                }
                val breakdown = OrderTaxCalculator.computeBreakdown(taxLines, taxRules)
                val taxCents = OrderTaxCalculator.taxTotalCents(breakdown)
                val total = subtotal + taxCents / 100.0
                return subtotal to total
            }

            fun bindTaxRows(container: LinearLayout?, lines: List<CartLine>, taxRules: List<OrderTaxRule>) {
                container ?: return
                container.removeAllViews()
                val taxLines = lines.map { line ->
                    OrderTaxLine(
                        lineKey = line.menuItemId,
                        lineTotalDollars = line.unitPriceDollars * line.quantity,
                        taxMode = line.taxMode,
                        taxIds = line.taxIds,
                    )
                }
                val breakdown = OrderTaxCalculator.computeBreakdown(taxLines, taxRules)
                if (breakdown.isEmpty()) {
                    container.visibility = View.GONE
                    return
                }
                container.visibility = View.VISIBLE
                val taxColor = context.getColor(R.color.order_text_secondary)
                for (entry in breakdown) {
                    val row = TextView(context).apply {
                        text = "${DiscountDisplay.formatTaxLabel(entry.name, entry.type, entry.rate)}: ${
                            MoneyUtils.centsToDisplay(entry.amountInCents)
                        }"
                        textSize = 14f
                        setTextColor(taxColor)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                    }
                    container.addView(row)
                }
            }

            fun bindCartSheet(
                lines: List<CartLine>,
                checkoutReady: Boolean,
                taxRules: List<OrderTaxRule>,
            ) {
                val (subtotal, total) = cartTotals(lines, taxRules)
                val itemCount = lines.sumOf { it.quantity }
                val hasItems = lines.isNotEmpty()
                cartLineList?.visibility = if (hasItems) View.VISIBLE else View.GONE
                cartEmptyMessage?.visibility = if (hasItems) View.GONE else View.VISIBLE
                cartLineAdapter?.submitList(lines)
                cartSubtotalValue?.text = formatMoney(subtotal)
                bindTaxRows(cartTaxContainer, lines, taxRules)
                cartTotalValue?.text = formatMoney(total)
                cartCheckoutButton?.isEnabled = checkoutReady
                reviewLabel.text = if (hasItems) {
                    context.getString(R.string.order_review_summary, itemCount, formatMoney(total))
                } else {
                    context.getString(R.string.order_review_empty)
                }
                reviewButton.alpha = if (hasItems) 1f else 0.72f
            }

            fun showCartSheet(controller: OrderScreenController) {
                if (cartDialog?.isShowing == true) return
                val dialog = BottomSheetDialog(context)
                val sheet = LayoutInflater.from(context).inflate(R.layout.bottomsheet_cart, null, false)
                dialog.setContentView(sheet)
                dialog.behavior.skipCollapsed = true
                dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                cartLineList = sheet.findViewById(R.id.cartLineList)
                cartEmptyMessage = sheet.findViewById(R.id.cartEmptyMessage)
                cartSubtotalValue = sheet.findViewById(R.id.cartSubtotalValue)
                cartTaxContainer = sheet.findViewById(R.id.cartTaxContainer)
                cartTotalValue = sheet.findViewById(R.id.cartTotalValue)
                cartCheckoutButton = sheet.findViewById(R.id.cartCheckoutButton)
                cartLineAdapter = OrderCartLineAdapter(
                    onIncreaseLine = onIncreaseLine,
                    onDecreaseLine = onDecreaseLine,
                )
                cartLineList?.layoutManager = LinearLayoutManager(context)
                cartLineList?.adapter = cartLineAdapter
                cartCheckoutButton?.setOnClickListener {
                    dialog.dismiss()
                    onCheckoutClick()
                }
                bindCartSheet(controller.latestCart, controller.latestCheckoutEnabled, controller.latestTaxes)
                dialog.setOnDismissListener {
                    cartDialog = null
                    cartLineList = null
                    cartEmptyMessage = null
                    cartSubtotalValue = null
                    cartTaxContainer = null
                    cartTotalValue = null
                    cartCheckoutButton = null
                    cartLineAdapter = null
                }
                cartDialog = dialog
                dialog.show()
            }

            backButton.setOnClickListener { onBack() }

            var suppressSearchCallback = false
            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressSearchCallback) return
                    onSearchQueryChange(s?.toString().orEmpty())
                }
            })

            root.setTag(
                R.id.tag_order_screen_controller,
                OrderScreenController(
                    titleView = titleView,
                    subtitleView = subtitleView,
                    guestChipScroller = guestChipScroller,
                    guestChipGroup = guestChipGroup,
                    categoryAdapter = categoryAdapter,
                    menuAdapter = menuAdapter,
                    searchInput = searchInput,
                    progress = progress,
                    reviewButton = reviewButton,
                    reviewLabel = reviewLabel,
                    bindCartSheet = ::bindCartSheet,
                    setSuppressSearchCallback = { suppressSearchCallback = it },
                    showCartSheet = ::showCartSheet,
                ),
            )
            val controller = root.getTag(R.id.tag_order_screen_controller) as OrderScreenController
            controller.reviewButton.setOnClickListener { controller.showCartSheet(controller) }
            root
        },
        update = { root ->
            val controller = root.getTag(R.id.tag_order_screen_controller) as OrderScreenController
            controller.titleView.text = orderTitle
            controller.subtitleView.text = orderSubtitle
            controller.categoryAdapter.submitList(categories, selectedCategoryId)
            controller.menuAdapter.submitList(filteredItems)
            controller.progress.visibility = if (busy && items.isEmpty()) View.VISIBLE else View.GONE
            controller.latestCart = cart
            controller.latestCheckoutEnabled = checkoutEnabled
            controller.latestTaxes = taxes
            controller.bindCartSheet(cart, checkoutEnabled, taxes)
            rebuildGuestChips(
                context = root.context,
                scroller = controller.guestChipScroller,
                chipGroup = controller.guestChipGroup,
                guestCount = guestCount,
                guestNames = guestNames,
                selectedGuest = selectedGuest,
                onGuestSelected = onGuestSelected,
            )
            if (controller.searchInput.text?.toString() != searchQuery) {
                controller.setSuppressSearchCallback(true)
                controller.searchInput.setText(searchQuery)
                controller.searchInput.setSelection(searchQuery.length)
                controller.setSuppressSearchCallback(false)
            }
        },
    )
}

private fun rebuildGuestChips(
    context: android.content.Context,
    scroller: HorizontalScrollView,
    chipGroup: ChipGroup,
    guestCount: Int,
    guestNames: List<String>,
    selectedGuest: Int,
    onGuestSelected: (Int) -> Unit,
) {
    chipGroup.removeAllViews()
    if (guestCount <= 0) {
        scroller.visibility = View.GONE
        return
    }
    scroller.visibility = View.VISIBLE
    val brand = context.getColor(R.color.order_brand_primary)
    val white = context.getColor(android.R.color.white)
    val textPrimary = context.getColor(R.color.order_text_primary)
    val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked))
    val defaultStates = arrayOf(intArrayOf())
    var chipToScroll: View? = null
    for (g in 1..guestCount) {
        val label = guestNames.getOrNull(g - 1)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.order_guest_label, g)
        val chip = Chip(context).apply {
            text = label
            isCheckable = true
            isChecked = g == selectedGuest
            val density = context.resources.displayMetrics.density
            chipMinHeight = 36f * density
            chipStrokeWidth = density
            chipStrokeColor = ColorStateList.valueOf(brand)
            setTextColor(
                ColorStateList(
                    arrayOf(checkedStates[0], defaultStates[0]),
                    intArrayOf(white, textPrimary),
                ),
            )
            chipBackgroundColor = ColorStateList(
                arrayOf(checkedStates[0], defaultStates[0]),
                intArrayOf(brand, white),
            )
            setOnClickListener {
                onGuestSelected(g)
            }
        }
        if (g == selectedGuest) chipToScroll = chip
        chipGroup.addView(chip)
    }
    chipToScroll?.post {
        val target = chipToScroll.left - (scroller.width - chipToScroll.width) / 2
        scroller.smoothScrollTo(target.coerceAtLeast(0), 0)
    }
}

private class OrderScreenController(
    val titleView: TextView,
    val subtitleView: TextView,
    val guestChipScroller: HorizontalScrollView,
    val guestChipGroup: ChipGroup,
    val categoryAdapter: OrderCategoryAdapter,
    val menuAdapter: OrderMenuItemAdapter,
    val searchInput: TextInputEditText,
    val progress: ProgressBar,
    val reviewButton: LinearLayout,
    val reviewLabel: TextView,
    val bindCartSheet: (List<CartLine>, Boolean, List<OrderTaxRule>) -> Unit,
    val setSuppressSearchCallback: (Boolean) -> Unit,
    val showCartSheet: (OrderScreenController) -> Unit,
) {
    var latestCart: List<CartLine> = emptyList()
    var latestCheckoutEnabled: Boolean = false
    var latestTaxes: List<OrderTaxRule> = emptyList()
}
