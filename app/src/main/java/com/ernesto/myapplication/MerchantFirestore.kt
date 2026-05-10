package com.ernesto.myapplication

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Single source of truth for merchant-scoped Firestore paths.
 *
 * All merchant data lives under `Merchants/{merchantId}/<subcollection>`.
 * Call [init] at app startup (after resolving which merchant this device belongs to)
 * and use [col] / [doc] everywhere instead of raw `db.collection("...")`.
 *
 * Collections that are NOT merchant-scoped (e.g. `Merchants` itself, `Terminals`,
 * `UberWebhookEvents`) should still use `db.collection(...)` directly.
 */
object MerchantFirestore {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    @Volatile
    var merchantId: String = ""
        private set

    val isInitialized: Boolean get() = merchantId.isNotBlank()

    /**
     * Old root-level name -> new subcollection name under `Merchants/{mid}/`.
     * Keeps a single mapping so every call site is consistent.
     */
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

    /**
     * Resolve the subcollection name. Accepts either the old root name (e.g. `"Orders"`)
     * or the new subcollection name (e.g. `"orders"`); returns the canonical new name.
     */
    fun resolvedName(name: String): String =
        NAME_MAP[name] ?: name

    /** Merchant-scoped collection reference: `Merchants/{mid}/{resolvedName}`. */
    fun col(name: String): CollectionReference {
        check(isInitialized) { "MerchantFirestore.init() not called" }
        return merchantDoc().collection(resolvedName(name))
    }

    /** Merchant-scoped document reference: `Merchants/{mid}/{resolvedName}/{docId}`. */
    fun doc(collectionName: String, docId: String): DocumentReference {
        return col(collectionName).document(docId)
    }
}
