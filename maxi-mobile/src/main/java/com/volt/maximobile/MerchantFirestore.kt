package com.volt.maximobile

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/** Same merchant-scoped paths as the main POS app. */
object MerchantFirestore {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    @Volatile
    var merchantId: String = ""
        private set

    val isInitialized: Boolean get() = merchantId.isNotBlank()

    private val NAME_MAP: Map<String, String> = mapOf(
        "Orders" to "orders",
        "Categories" to "categories",
        "MenuItems" to "menuItems",
        "Batches" to "batches",
        "Transactions" to "transactions",
        "Counters" to "counters",
        "Tables" to "tables",
        "tableLayouts" to "tableLayouts",
        "PosDevices" to "posDevices",
        "DeviceActivations" to "deviceActivations",
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

    fun resolvedName(name: String): String = NAME_MAP[name] ?: name

    fun col(name: String): CollectionReference {
        check(isInitialized) { "MerchantFirestore.init() not called" }
        return merchantDoc().collection(resolvedName(name))
    }

    fun doc(collectionName: String, docId: String): DocumentReference =
        col(collectionName).document(docId)
}
