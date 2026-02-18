package com.ernesto.myapplication

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.data.Transaction
import java.text.SimpleDateFormat
import java.util.*

class TransactionAdapter(
    private val transactions: List<Transaction>,
    private val onTransactionClick: (Transaction) -> Unit
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

        val transaction = transactions[position]
        val context = holder.itemView.context

        val amount = transaction.amountInCents / 100.0
        val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
            .format(Date(transaction.date))

        // Set normal text
        holder.txtCard.text =
            "${transaction.cardBrand} •••• ${transaction.last4}"

        holder.txtType.text =
            "${transaction.paymentType} • ${transaction.entryType}"

        holder.txtAmount.text =
            String.format("$%.2f", amount)

        holder.txtMeta.text =
            "Date: $date\nRef: ${transaction.referenceId}"

        // 🔥 Status Styling
        if (transaction.voided) {

            // Red dot
            holder.statusDot.setBackgroundColor(
                context.getColor(android.R.color.holo_red_dark)
            )

            // Show VOID badge
            holder.txtVoidBadge.visibility = View.VISIBLE

            // Fade item
            holder.itemView.alpha = 0.6f

            // Strike-through amount
            holder.txtAmount.paintFlags =
                holder.txtAmount.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

        } else {

            // Green dot
            holder.statusDot.setBackgroundColor(
                context.getColor(android.R.color.holo_green_dark)
            )

            // Hide VOID badge
            holder.txtVoidBadge.visibility = View.GONE

            // Normal opacity
            holder.itemView.alpha = 1.0f

            // Remove strike-through
            holder.txtAmount.paintFlags =
                holder.txtAmount.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        holder.itemView.setOnClickListener {
            onTransactionClick(transaction)
        }
    }

    override fun getItemCount(): Int = transactions.size
}
