package com.ernesto.myapplication

import android.content.Intent
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.ernesto.myapplication.engine.MoneyUtils
import java.util.Date

/**
 * Staff review UI for web online orders awaiting confirmation: line items, total,
 * [Accept] / [Cancel order] — same flow as the dashboard banner.
 *
 * Used from [MainActivity] (banner) and [OnlineOrderAlertSystem] (**VIEW ORDER** on the new-order alert).
 */
object OnlineOrderAwaitingStaffReviewDialog {

    /** Fresh fetch (e.g. after **VIEW ORDER**); opens full order detail if the ticket is no longer awaiting staff. */
    fun showFromOrderId(
        activity: AppCompatActivity,
        db: FirebaseFirestore,
        orderId: String,
        employeeName: String,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        db.collection("Orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (!doc.exists()) {
                    Toast.makeText(activity, "Order not found.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                if (OnlineOrderStaffConfirm.isAwaitingStaffWebOnline(doc)) {
                    loadItemsAndShow(activity, db, doc, employeeName)
                } else {
                    openOrderDetail(activity, orderId, employeeName)
                }
            }
            .addOnFailureListener { e ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, "Could not load order: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    /** Caller already holds a document (e.g. pending banner list). */
    fun showFromDocument(
        activity: AppCompatActivity,
        db: FirebaseFirestore,
        orderDoc: DocumentSnapshot,
        employeeName: String,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!orderDoc.exists()) {
            Toast.makeText(activity, "Order not found.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!OnlineOrderStaffConfirm.isAwaitingStaffWebOnline(orderDoc)) {
            Toast.makeText(activity, "That order was already handled.", Toast.LENGTH_SHORT).show()
            return
        }
        loadItemsAndShow(activity, db, orderDoc, employeeName)
    }

    private fun openOrderDetail(activity: AppCompatActivity, orderId: String, employeeName: String) {
        val intent = Intent(activity, OrderDetailActivity::class.java).apply {
            putExtra("orderId", orderId)
            putExtra("employeeName", employeeName)
        }
        activity.startActivity(intent)
    }

    private fun loadItemsAndShow(
        activity: AppCompatActivity,
        db: FirebaseFirestore,
        orderDoc: DocumentSnapshot,
        employeeName: String,
    ) {
        val orderId = orderDoc.id
        val orderNum = orderDoc.getLong("orderNumber") ?: 0L
        val customer = orderDoc.getString("customerName")?.trim().orEmpty().ifEmpty { "Guest" }
        val payment = orderDoc.getString("onlinePaymentChoice") ?: ""
        val payLine = when (payment) {
            "PAY_ONLINE_HPP" -> activity.getString(R.string.pending_online_payment_card_online)
            else -> activity.getString(R.string.pending_online_payment_pickup)
        }
        orderDoc.reference.collection("items").get()
            .addOnSuccessListener { qs ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                val sb = StringBuilder()
                sb.append("Customer: ").append(customer).append('\n').append(payLine).append("\n\n")
                for (item in qs.documents) {
                    val name = item.getString("name") ?: "Item"
                    val qty = (item.getLong("quantity") ?: 1L).toInt().coerceAtLeast(1)
                    val lineTotal = item.getLong("lineTotalInCents")
                        ?: item.getLong("lineTotalWithTaxInCents")
                        ?: 0L
                    sb.append("• ").append(name).append(" ×").append(qty).append("   ")
                        .append(MoneyUtils.centsToDisplay(lineTotal)).append('\n')
                }
                val totalCents = orderDoc.getLong("totalInCents") ?: 0L
                sb.append("\nTotal ").append(MoneyUtils.centsToDisplay(totalCents))
                val scroll = ScrollView(activity)
                val tv = TextView(activity).apply {
                    text = sb.toString()
                    textSize = 15f
                    setPadding(48, 32, 48, 24)
                }
                scroll.addView(tv)
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Order #$orderNum")
                    .setView(scroll)
                    .setPositiveButton("Accept") { _, _ -> acceptOrder(db, orderId, employeeName, activity) }
                    .setNegativeButton("Cancel order") { _, _ ->
                        MaterialAlertDialogBuilder(activity)
                            .setMessage(
                                "Decline this order? It will be voided and will not appear in Online orders.",
                            )
                            .setPositiveButton("Decline") { _, _ ->
                                declineOrder(db, orderId, orderDoc, employeeName, activity)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .show()
            }
            .addOnFailureListener { e ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, "Could not load items: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun acceptOrder(db: FirebaseFirestore, orderId: String, employeeName: String, activity: AppCompatActivity) {
        db.collection("Orders").document(orderId).update(
            mapOf(
                OnlineOrderStaffConfirm.FIELD_AWAITING to false,
                "staffConfirmedOrderAt" to Timestamp.now(),
                "staffConfirmedOrderBy" to employeeName.ifBlank { "Staff" },
                "updatedAt" to Date(),
            ),
        ).addOnFailureListener { e ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, "Accept failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun declineOrder(
        db: FirebaseFirestore,
        orderId: String,
        orderDoc: DocumentSnapshot,
        employeeName: String,
        activity: AppCompatActivity,
    ) {
        val paid = orderDoc.getLong("totalPaidInCents") ?: 0L
        if (paid > 0L) {
            Toast.makeText(
                activity,
                "This order is already paid online. Open it in Orders to handle a refund; it was not voided.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        db.collection("Orders").document(orderId).update(
            mapOf(
                "status" to "VOIDED",
                "voided" to true,
                "voidedAt" to Timestamp.now(),
                "voidedBy" to employeeName.ifBlank { "Staff" },
                OnlineOrderStaffConfirm.FIELD_AWAITING to false,
                "updatedAt" to Date(),
            ),
        ).addOnFailureListener { e ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, "Could not cancel: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
