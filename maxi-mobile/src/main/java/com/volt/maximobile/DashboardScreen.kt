package com.volt.maximobile

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.firebase.firestore.ListenerRegistration
import com.volt.shared.MerchantFirestore

@Composable
fun DashboardScreen(
    employeeName: String,
    employeeRole: String,
    onNewOrder: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val badges = remember { mutableStateMapOf<String, Int>() }
    var businessName by remember {
        mutableStateOf(com.volt.shared.device.PosDeviceIdentity.getMerchantBusinessName(context))
    }
    val modules = remember(context) { dashboardModules(context) }

    DisposableEffect(Unit) {
        var listener: ListenerRegistration? = null
        if (MerchantFirestore.isInitialized) {
            listener = MerchantFirestore.col("Orders")
                .whereEqualTo("status", "OPEN")
                .addSnapshotListener { snapshots, _ ->
                    if (snapshots == null) return@addSnapshotListener
                    var dineIn = 0
                    var toGo = 0
                    var bar = 0
                    var online = 0
                    for (doc in snapshots) {
                        val source = doc.getString("orderSource") ?: ""
                        val ot = doc.getString("orderType") ?: ""
                        if (source.isNotBlank() || ot == "UBER_EATS" || ot == "ONLINE_PICKUP") {
                            online++
                            continue
                        }
                        when (ot) {
                            "DINE_IN" -> {
                                val tid = doc.getString("tableId")
                                if (!tid.isNullOrBlank()) dineIn++
                            }
                            "TO_GO" -> toGo++
                            "BAR", "BAR_TAB" -> bar++
                        }
                    }
                    badges["dine_in"] = dineIn
                    badges["to_go"] = toGo
                    badges["bar"] = bar
                    badges["online_orders"] = online
                }
        }
        onDispose { listener?.remove() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val root = LayoutInflater.from(ctx).inflate(R.layout.activity_dashboard, null, false)
            val pager = root.findViewById<ViewPager2>(R.id.pagerDashboard)
            val pageIndicator = root.findViewById<LinearLayout>(R.id.pageIndicator)
            val pageAdapter = DashboardPagesAdapter { item ->
                val module = modules.firstOrNull { it.key == item.moduleKey } ?: return@DashboardPagesAdapter
                launchDashboardModule(
                    context = ctx,
                    module = module,
                    employeeName = employeeName,
                    employeeRole = employeeRole,
                    onNewOrder = onNewOrder,
                )
            }
            pager.adapter = pageAdapter
            pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        updateDashboardPageIndicator(pageIndicator, pager.adapter?.itemCount ?: 0, position)
                    }
                },
            )
            root.setTag(R.id.tag_maxi_dashboard_card_adapter, pageAdapter)
            root
        },
        update = { root ->
            root.findViewById<TextView>(R.id.txtBusinessName).text =
                businessName.ifBlank { "MaxiPay POS" }
            root.findViewById<TextView>(R.id.txtEmployeeLine).text =
                "$employeeName  •  $employeeRole"
            root.findViewById<TextView>(R.id.txtProfileInitials).text =
                DashboardCardContent.profileInitials(employeeName)

            val pager = root.findViewById<ViewPager2>(R.id.pagerDashboard)
            val pageIndicator = root.findViewById<LinearLayout>(R.id.pageIndicator)
            val pageAdapter = root.getTag(R.id.tag_maxi_dashboard_card_adapter) as? DashboardPagesAdapter
            val pages = DashboardCardContent.partitionPages(context, modules, badges)
            pageAdapter?.submitPages(pages)
            updateDashboardPageIndicator(pageIndicator, pages.size, pager.currentItem)
        },
    )
}

private fun updateDashboardPageIndicator(
    pageIndicator: LinearLayout,
    pageCount: Int,
    selectedPage: Int,
) {
    pageIndicator.removeAllViews()
    if (pageCount <= 1) {
        pageIndicator.visibility = View.GONE
        return
    }
    pageIndicator.visibility = View.VISIBLE
    val density = pageIndicator.resources.displayMetrics.density
    val dotSize = (8 * density).toInt()
    val dotMargin = (4 * density).toInt()
    for (index in 0 until pageCount) {
        val dot = View(pageIndicator.context)
        val params = LinearLayout.LayoutParams(dotSize, dotSize)
        params.setMargins(dotMargin, 0, dotMargin, 0)
        dot.layoutParams = params
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                ContextCompat.getColor(
                    pageIndicator.context,
                    if (index == selectedPage) {
                        R.color.dashboard_page_dot_selected
                    } else {
                        R.color.dashboard_page_dot_unselected
                    },
                ),
            )
        }
        pageIndicator.addView(dot)
    }
}

private fun dashboardModules(context: Context): List<DashboardModule> =
    DashboardModule.mergeTipDashboardTile(
        context,
        DashboardModule.mergeReservationDashboardTile(
            DashboardModule.mergeOnlineOrdersDashboardTile(
                DashboardModule.mergePrintersDashboardTile(DashboardModule.getDefaults()),
                showOnlineOrdersTile = OnlineOrderingDashboardSync.shouldShowOnlineOrdersTile(),
            ),
        ),
    )

private fun launchDashboardModule(
    context: Context,
    module: DashboardModule,
    employeeName: String,
    employeeRole: String,
    onNewOrder: (String) -> Unit,
) {
    when (module.key) {
        "dine_in" -> onNewOrder("DINE_IN")
        "to_go" -> onNewOrder("TO_GO")
        "bar" -> onNewOrder("BAR")
        "online_orders" -> {
            context.startActivity(
                Intent(context, OrdersActivity::class.java).apply {
                    putExtra("employeeName", employeeName)
                    putExtra("employeeRole", employeeRole)
                    putExtra("FILTER_ONLINE", true)
                },
            )
        }
        "transactions" -> {
            context.startActivity(
                Intent(context, TransactionActivity::class.java).apply {
                    putExtra("employeeName", employeeName)
                    putExtra("employeeRole", employeeRole)
                },
            )
        }
        "settle_batch" -> context.startActivity(Intent(context, BatchManagementActivity::class.java))
        "employees" -> context.startActivity(Intent(context, EmployeesActivity::class.java))
        "customers" -> context.startActivity(Intent(context, CustomersActivity::class.java))
        "orders" -> {
            context.startActivity(
                Intent(context, OrdersActivity::class.java).apply {
                    putExtra("employeeName", employeeName)
                    putExtra("employeeRole", employeeRole)
                },
            )
        }
        "setup" -> context.startActivity(Intent(context, ConfigurationActivity::class.java))
        "modifiers" -> context.startActivity(Intent(context, GlobalModifierActivity::class.java))
        "inventory" -> context.startActivity(Intent(context, MenuOnlyActivity::class.java))
        "reports" -> context.startActivity(Intent(context, ReportsActivity::class.java))
        "printers" -> context.startActivity(Intent(context, PrintersActivity::class.java))
        "cash_flow" -> context.startActivity(Intent(context, CashFlowActivity::class.java))
        "tips" -> {
            if (TipConfig.isTipsEnabled(context) && !TipConfig.isTipOnCustomerScreen(context)) {
                context.startActivity(Intent(context, TipAdjustmentActivity::class.java))
            } else {
                val msg = if (!TipConfig.isTipsEnabled(context)) {
                    "Tips are disabled in settings."
                } else {
                    "Tips are collected on the payment screen. Tip adjustment is not available here."
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        "reservation" -> {
            context.startActivity(
                Intent(context, ReservationActivity::class.java).apply {
                    putExtra("employeeName", employeeName)
                },
            )
        }
    }
}
