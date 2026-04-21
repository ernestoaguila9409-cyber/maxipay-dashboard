package com.ernesto.kds.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ernesto.kds.data.KdsLineWorkflow
import com.ernesto.kds.data.KdsDisplaySettings
import com.ernesto.kds.data.KdsMenuAssignment
import com.ernesto.kds.data.KdsOrdersRepository
import com.ernesto.kds.data.KdsTextSettingKey
import com.ernesto.kds.data.KdsTextSettings
import com.ernesto.kds.data.Order
import com.ernesto.kds.data.OrderItem
import com.ernesto.kds.data.adjust
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Date

class KdsViewModel(
    application: Application,
    private val repository: KdsOrdersRepository = KdsOrdersRepository(),
) : AndroidViewModel(application) {

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(KdsViewModel::class.java)) {
                return KdsViewModel(app) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private val deviceDocId = MutableStateFlow("")

    /** Call when the tablet is paired so category filters use the device doc / assignedCategoryIds. */
    fun bindDeviceDocId(id: String) {
        deviceDocId.value = id.trim()
    }

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _dashboardColorKeys = MutableStateFlow<Map<String, String>>(emptyMap())
    val dashboardColorKeys: StateFlow<Map<String, String>> = _dashboardColorKeys.asStateFlow()

    private val _kdsDisplaySettings = MutableStateFlow(KdsDisplaySettings())
    val kdsDisplaySettings: StateFlow<KdsDisplaySettings> = _kdsDisplaySettings.asStateFlow()

    private val _textSettings = MutableStateFlow(KdsTextSettings.Default)
    val textSettings: StateFlow<KdsTextSettings> = _textSettings.asStateFlow()

    init {
        viewModelScope.launch {
            deviceDocId
                .flatMapLatest { id ->
                    if (id.isEmpty()) flowOf(KdsTextSettings.Default)
                    else repository.observeKdsTextSettings(id)
                }
                .collect { _textSettings.value = it }
        }
        viewModelScope.launch {
            deviceDocId
                .flatMapLatest { docId ->
                    repository.observeKitchenOrders(docId).flatMapLatest { rawOrders ->
                        if (docId.isEmpty()) {
                            flowOf(rawOrders)
                        } else {
                            combine(
                                repository.observeKdsDeviceMenuAssignment(docId),
                                repository.observeMenuItemCategoryPlacements(),
                            ) { assignment, itemPlacements ->
                                filterOrdersByMenuAssignment(
                                    rawOrders,
                                    assignment,
                                    itemPlacements,
                                )
                            }
                        }
                    }
                }
                .collect { list ->
                    _orders.value = list
                }
        }
        viewModelScope.launch {
            repository.observeDashboardColorKeys().collect { keys ->
                _dashboardColorKeys.value = keys
            }
        }
        viewModelScope.launch {
            repository.observeKdsDisplaySettings().collect { settings ->
                _kdsDisplaySettings.value = settings
            }
        }
    }

    fun markAsPreparing(order: Order) {
        val cardKey = order.cardKey
        val devId = deviceDocId.value.trim()
        viewModelScope.launch {
            val snapshotBefore = _orders.value.toList()
            applyOptimisticPreparing(cardKey)
            val lineIds = order.items.map { it.lineDocId.trim() }.filter { it.isNotEmpty() }.distinct()
            val result = runCatching {
                repository.updateOrderStatus(
                    order.id,
                    "PREPARING",
                    lineIds,
                    kdsClaimDeviceId = devId,
                    kdsClaimCardKey = cardKey,
                )
            }
            result.onFailure { e ->
                _orders.value = snapshotBefore
                val msg = if (e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
                ) {
                    "This ticket was started on another KDS."
                } else {
                    e.message?.takeIf { it.isNotBlank() } ?: "Could not start ticket."
                }
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun markAsReady(order: Order) {
        val cardKey = order.cardKey
        viewModelScope.launch {
            removeOrderCardOptimistically(cardKey)
            val lineIds = order.items.map { it.lineDocId.trim() }.filter { it.isNotEmpty() }.distinct()
            val coverQty = order.items
                .filter { it.lineDocId.isNotBlank() }
                .groupBy { it.lineDocId.trim() }
                .mapValues { (_, rows) -> rows.sumOf { it.quantity.toLong() } }
            runCatching {
                repository.updateOrderStatus(
                    order.id,
                    "READY",
                    lineIds,
                    coverQty,
                    kdsClaimCardKey = cardKey,
                )
            }
        }
    }

    /**
     * Swipe-right on a single item: mark just that one line READY on Firestore and drop it from
     * the local card. When the swiped item is the **last active line** on the card, also release
     * the card claim so the whole ticket closes across tablets (same as the full READY button).
     */
    fun markItemReady(order: Order, item: OrderItem) {
        val lineId = item.lineDocId.trim()
        if (lineId.isEmpty()) return
        val cardKey = order.cardKey
        viewModelScope.launch {
            val snapshotBefore = _orders.value.toList()
            val remainingAfter = order.items.filterNot { it === item }
            val isLastLine = remainingAfter.isEmpty()
            if (isLastLine) {
                removeOrderCardOptimistically(cardKey)
            } else {
                removeItemFromCardOptimistically(cardKey, item)
            }
            val coverQty = mapOf(lineId to item.quantity.toLong().coerceAtLeast(1L))
            val result = runCatching {
                repository.updateOrderStatus(
                    order.id,
                    "READY",
                    listOf(lineId),
                    coverQty,
                    kdsClaimCardKey = if (isLastLine) cardKey else null,
                )
            }
            result.onFailure {
                _orders.value = snapshotBefore
            }
        }
    }

    private fun removeItemFromCardOptimistically(cardKey: String, target: OrderItem) {
        _orders.value = _orders.value.map { o ->
            if (o.cardKey != cardKey) o
            else o.copy(items = o.items.filterNot { it === target })
        }
    }

    /** Hide the ticket immediately; Firestore listener will reconcile if the write fails. */
    private fun removeOrderCardOptimistically(cardKey: String) {
        _orders.value = _orders.value.filter { order -> order.cardKey != cardKey }
    }

    private fun applyOptimisticPreparing(cardKey: String) {
        _orders.value = _orders.value.map { o ->
            if (o.cardKey != cardKey) return@map o
            val nextItems = o.items.map { line ->
                if (line.kdsStatus.trim().equals("SENT", ignoreCase = true) ||
                    line.kdsStatus.isBlank()
                ) {
                    line.copy(kdsStatus = "PREPARING", kdsStartedAt = line.kdsStartedAt ?: Date())
                } else {
                    line
                }
            }
            o.copy(
                status = "PREPARING",
                items = nextItems,
                kitchenStartedAt = o.kitchenStartedAt ?: Date(),
            )
        }
    }

    fun adjustTextSetting(key: KdsTextSettingKey, deltaSteps: Int) {
        val id = deviceDocId.value.trim()
        if (id.isEmpty()) return
        viewModelScope.launch {
            val next = _textSettings.value.adjust(key, deltaSteps)
            runCatching { repository.saveKdsTextSettings(id, next) }
        }
    }

    fun setModifierAddColor(argb: Long) {
        val id = deviceDocId.value.trim()
        if (id.isEmpty()) return
        viewModelScope.launch {
            val next = _textSettings.value
                .copy(modifierAddColorArgb = argb and 0xFFFFFFFFL)
                .coerce()
            runCatching { repository.saveKdsTextSettings(id, next) }
        }
    }

    fun setModifierRemoveColor(argb: Long) {
        val id = deviceDocId.value.trim()
        if (id.isEmpty()) return
        viewModelScope.launch {
            val next = _textSettings.value
                .copy(modifierRemoveColorArgb = argb and 0xFFFFFFFFL)
                .coerce()
            runCatching { repository.saveKdsTextSettings(id, next) }
        }
    }

    companion object {
        /**
         * When the dashboard assigns categories / items to this tablet, keep only lines that
         * belong to that assignment. (Previously we only filtered *orders*, so one matching line
         * showed the whole ticket including unrelated items.)
         *
         * Empty assignment = show every line on every order (no filter).
         */
        fun filterOrdersByMenuAssignment(
            orders: List<Order>,
            assignment: KdsMenuAssignment,
            itemIdToCategoryPlacements: Map<String, Set<String>>,
        ): List<Order> {
            if (assignment.categoryIds.isEmpty() && assignment.itemIds.isEmpty()) {
                return orders
            }
            fun lineMatches(line: OrderItem): Boolean {
                val id = line.itemId.trim()
                if (id.isEmpty()) return false
                if (id in assignment.itemIds) return true
                val placements = itemIdToCategoryPlacements[id] ?: emptySet()
                return placements.any { it in assignment.categoryIds }
            }
            return orders.mapNotNull { order ->
                val lines = order.items.filter { lineMatches(it) }
                val active = KdsLineWorkflow.linesOmittingKitchenReady(lines)
                if (active.isEmpty()) return@mapNotNull null
                val status = KdsLineWorkflow.deriveCardStatusFromVisibleLines(active)
                if (status.equals("READY", ignoreCase = true)) return@mapNotNull null
                val prepAnchor = KdsLineWorkflow.derivePrepStartedAtFromVisibleLines(active, status)
                order.copy(items = active, status = status, kitchenStartedAt = prepAnchor)
            }
        }
    }
}
