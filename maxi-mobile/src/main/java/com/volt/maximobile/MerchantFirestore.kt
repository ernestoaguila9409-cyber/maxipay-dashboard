package com.volt.maximobile

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Maxi-mobile entry point for merchant-scoped Firestore paths.
 * Delegates to [:shared] [com.volt.shared.MerchantFirestore].
 */
object MerchantFirestore {
    private val shared get() = com.volt.shared.MerchantFirestore

    val merchantId: String get() = shared.merchantId
    val isInitialized: Boolean get() = shared.isInitialized

    fun init(merchantId: String) = shared.init(merchantId)
    fun reset() = shared.reset()
    fun resolvedName(name: String): String = shared.resolvedName(name)
    fun col(name: String): CollectionReference = shared.col(name)
    fun doc(collectionName: String, docId: String): DocumentReference = shared.doc(collectionName, docId)
    fun mergeMenuItemModifierGroupIds(doc: DocumentSnapshot): List<String> =
        shared.mergeMenuItemModifierGroupIds(doc)
    fun mergeMenuItemTaxIds(doc: DocumentSnapshot): List<String> = shared.mergeMenuItemTaxIds(doc)
    fun menuItemTaxModeForIds(taxIds: Collection<String>): String = shared.menuItemTaxModeForIds(taxIds)
    fun menuItemTaxModeFromDoc(doc: DocumentSnapshot): String = shared.menuItemTaxModeFromDoc(doc)
}
