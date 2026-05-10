package com.ernesto.kds.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Single source of truth for merchant-scoped Firestore paths in the KDS app.
 *
 * All merchant data lives under `Merchants/{merchantId}/<subcollection>`.
 * Call [init] after pairing resolves the merchant and use [col] / [doc]
 * everywhere instead of raw `db.collection("...")`.
 */
object MerchantFirestore {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    @Volatile
    var merchantId: String = ""
        private set

    val isInitialized: Boolean get() = merchantId.isNotBlank()

    private val NAME_MAP: Map<String, String> = mapOf(
        "Orders" to "orders",
        "Categories" to "categories",
        "subcategories" to "subcategories",
        "MenuItems" to "menuItems",
        "ModifierGroups" to "modifierGroups",
        "ModifierOptions" to "modifierOptions",
        "ItemModifierGroups" to "itemModifierGroups",
        "menus" to "menus",
        "menuSchedules" to "menuSchedules",
        "Employees" to "employees",
        "Customers" to "customers",
        "Taxes" to "taxes",
        "discounts" to "discounts",
        "Printers" to "printers",
        "Batches" to "batches",
        "Transactions" to "transactions",
        "cashLogs" to "cashLogs",
        "kds_devices" to "kds_devices",
        "payment_terminals" to "payment_terminals",
        "Sections" to "sections",
        "tableLayouts" to "tableLayouts",
        "Tables" to "tables",
        "PosDevices" to "posDevices",
        "DeviceActivations" to "deviceActivations",
        "Reservations" to "reservations",
        "Settings" to "settings",
        "settings" to "settings",
        "OnlineHeroSlides" to "onlineHeroSlides",
        "RemotePaymentCommands" to "remotePaymentCommands",
        "OnlineTerminalPaymentRequests" to "onlineTerminalPaymentRequests",
        "Counters" to "counters",
    )

    fun init(merchantId: String) {
        require(merchantId.isNotBlank()) { "merchantId must not be blank" }
        this.merchantId = merchantId
    }

    fun reset() {
        merchantId = ""
    }

    private fun merchantDoc(): DocumentReference =
        db.collection("Merchants").document(merchantId)

    fun resolvedName(name: String): String =
        NAME_MAP[name] ?: name

    fun col(name: String): CollectionReference {
        check(isInitialized) { "MerchantFirestore.init() not called" }
        return merchantDoc().collection(resolvedName(name))
    }

    fun doc(collectionName: String, docId: String): DocumentReference {
        return col(collectionName).document(docId)
    }
}
