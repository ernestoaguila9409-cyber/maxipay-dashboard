package com.volt.maximobile

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    searchQuery: String,
    busy: Boolean,
    checkoutEnabled: Boolean,
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
            var cartTotalValue: TextView? = null
            var cartCheckoutButton: MaterialButton? = null
            var cartLineAdapter: OrderCartLineAdapter? = null

            fun formatMoney(amount: Double): String =
                "$${String.format(Locale.US, "%.2f", amount)}"

            fun bindCartSheet(
                lines: List<CartLine>,
                checkoutReady: Boolean,
            ) {
                val subtotal = lines.sumOf { it.unitPriceDollars * it.quantity }
                val itemCount = lines.sumOf { it.quantity }
                val hasItems = lines.isNotEmpty()
                cartLineList?.visibility = if (hasItems) View.VISIBLE else View.GONE
                cartEmptyMessage?.visibility = if (hasItems) View.GONE else View.VISIBLE
                cartLineAdapter?.submitList(lines)
                cartSubtotalValue?.text = formatMoney(subtotal)
                cartTotalValue?.text = formatMoney(subtotal)
                cartCheckoutButton?.isEnabled = checkoutReady
                reviewLabel.text = if (hasItems) {
                    context.getString(R.string.order_review_summary, itemCount, formatMoney(subtotal))
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
                bindCartSheet(controller.latestCart, controller.latestCheckoutEnabled)
                dialog.setOnDismissListener {
                    cartDialog = null
                    cartLineList = null
                    cartEmptyMessage = null
                    cartSubtotalValue = null
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
            controller.bindCartSheet(cart, checkoutEnabled)
            if (controller.searchInput.text?.toString() != searchQuery) {
                controller.setSuppressSearchCallback(true)
                controller.searchInput.setText(searchQuery)
                controller.searchInput.setSelection(searchQuery.length)
                controller.setSuppressSearchCallback(false)
            }
        },
    )
}

private class OrderScreenController(
    val titleView: TextView,
    val subtitleView: TextView,
    val categoryAdapter: OrderCategoryAdapter,
    val menuAdapter: OrderMenuItemAdapter,
    val searchInput: TextInputEditText,
    val progress: ProgressBar,
    val reviewButton: LinearLayout,
    val reviewLabel: TextView,
    val bindCartSheet: (List<CartLine>, Boolean) -> Unit,
    val setSuppressSearchCallback: (Boolean) -> Unit,
    val showCartSheet: (OrderScreenController) -> Unit,
) {
    var latestCart: List<CartLine> = emptyList()
    var latestCheckoutEnabled: Boolean = false
}
