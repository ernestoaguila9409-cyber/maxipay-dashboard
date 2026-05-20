package com.volt.maximobile

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.volt.maximobile.dvpaylite.P8ReceiptPrinter

/**
 * Prints customer receipts on the Dejavoo P8 built-in printer (maxi-mobile only).
 */
object CustomerReceiptPrint {

    fun printPaidOrder(
        context: Context,
        orderDoc: DocumentSnapshot,
        items: List<DocumentSnapshot>,
        payments: List<Map<String, Any>>,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
    ) {
        P8ReceiptPrinter.init(context.applicationContext)
        val rs = ReceiptSettings.load(context)
        val segments = MaxiReceiptBuilder.buildFromPaidOrder(context, orderDoc, items, payments)
        P8ReceiptPrinter.printReceipt(
            segments = segments,
            settings = rs,
            onSuccess = onSuccess,
            onFailure = onFailure,
        )
    }
}
