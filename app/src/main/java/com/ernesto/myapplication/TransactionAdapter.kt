package com.ernesto.myapplication

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.data.SaleWithRefunds
import java.text.SimpleDateFormat
import java.util.*

class TransactionAdapter(
    private val transactions: List<SaleWithRefunds>,
    private val onTransactionClick: (com.ernesto.myapplication.data.Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val statusDot: View = itemView.findViewById(R.id.statusDot)
        val txtCard: TextView = itemView.findViewById(R.id.txtCard)
        val txtType: TextView = itemView.findViewById(R.id.txtType)
        val txtAmount: TextView = itemView.findViewById(R.id.txtAmount)
        val txtMeta: TextView = itemView.findViewById(R.id.txtMeta)
        val txtVoidBadge: TextView = itemView.findViewById(R.id.txtVoidBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {

        val saleWithRefunds = transactions[position]
        val sale = saleWithRefunds.sale
        val refunds = saleWithRefunds.refunds
        val context = holder.itemView.context

        val saleAmount = sale.amountInCents / 100.0
        val totalRefunded = refunds.sumOf { kotlin.math.abs(it.amountInCents) } / 100.0
        val netAmount = saleAmount - totalRefunded

        val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
            .format(Date(sale.date))

        // Default green for SALE
        holder.statusDot.setBackgroundColor(
            context.getColor(android.R.color.holo_green_dark)
        )

        // Display all payment methods (loop through payments array)
        val paymentLines = if (sale.payments.isNotEmpty()) {
            sale.payments.map { p ->
                val methodLabel = if (p.paymentType.equals("Cash", true)) {
                    "Cash"
                } else {
                    if (p.cardBrand.isNotBlank() && p.last4.isNotBlank()) "${p.cardBrand} •••• ${p.last4}"
                    else p.paymentType.ifBlank { "Card" }
                }
                val amountStr = String.format("$%.2f", p.amountInCents / 100.0)
                "$methodLabel  $amountStr"
            }
        } else {
            // Fallback for older docs with single paymentType/cardBrand/last4
            val methodLabel = if (sale.paymentType.equals("Cash", true)) {
                "Cash"
            } else {
                if (sale.cardBrand.isNotBlank() && sale.last4.isNotBlank()) "${sale.cardBrand} •••• ${sale.last4}"
                else sale.paymentType.ifBlank { "Card" }
            }
            val amountStr = String.format("$%.2f", sale.amountInCents / 100.0)
            listOf("$methodLabel  $amountStr")
        }
        holder.txtCard.text = paymentLines.joinToString("\n")

        val typeLines = if (sale.payments.isNotEmpty()) {
            sale.payments.map { p ->
                if (p.paymentType.equals("Cash", true)) {
                    "Cash Payment"
                } else {
                    val displayEntryType = when (p.entryType) {
                        "Chip" -> "Chip"
                        "ChipContactless" -> "Contactless"
                        "Swipe" -> "Swipe"
                        "Manual" -> "Manual"
                        else -> p.entryType
                    }
                    "${p.paymentType.ifBlank { "Credit" }} • $displayEntryType"
                }
            }
        } else {
            val displayEntryType = when (sale.entryType) {
                "Chip" -> "Chip"
                "ChipContactless" -> "Contactless"
                "Swipe" -> "Swipe"
                "Manual" -> "Manual"
                else -> sale.entryType
            }
            listOf(
                if (sale.paymentType.equals("Cash", true)) "Cash Payment"
                else "${sale.paymentType} • $displayEntryType"
            )
        }
        holder.txtType.text = typeLines.joinToString("\n")

        holder.txtAmount.text =
            String.format("$%.2f", netAmount)

        holder.txtMeta.text =
            buildString {
                append("Date: $date\nRef: ${sale.referenceId}")

                if (refunds.isNotEmpty()) {
                    refunds.forEach {
                        append("\n\n🔵 Refund -$")
                        append(String.format("%.2f", it.amountInCents / 100.0))
                    }
                }
            }

        holder.txtVoidBadge.visibility =
            if (sale.voided) View.VISIBLE else View.GONE

        if (sale.voided) {
            holder.statusDot.setBackgroundColor(
                context.getColor(android.R.color.holo_red_dark)
            )
            holder.itemView.alpha = 0.6f
            holder.txtAmount.paintFlags =
                holder.txtAmount.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            holder.itemView.alpha = 1.0f
            holder.txtAmount.paintFlags =
                holder.txtAmount.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        holder.itemView.setOnClickListener {
            onTransactionClick(sale)
        }
    }

    override fun getItemCount(): Int = transactions.size
}
