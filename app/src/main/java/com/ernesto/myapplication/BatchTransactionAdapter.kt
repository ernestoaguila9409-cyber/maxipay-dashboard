package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ernesto.myapplication.data.SaleWithRefunds
import java.util.Locale

class BatchTransactionAdapter(
    private val items: List<SaleWithRefunds>
) : RecyclerView.Adapter<BatchTransactionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtCard: TextView = view.findViewById(R.id.txtCard)
        val txtFinalAmount: TextView = view.findViewById(R.id.txtFinalAmount)
        val txtRefunds: TextView = view.findViewById(R.id.txtRefunds)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val saleWithRefunds = items[position]
        val sale = saleWithRefunds.sale
        val refunds = saleWithRefunds.refunds

        val saleAmount = sale.amountInCents / 100.0
        val totalRefunded = refunds.sumOf { it.amountInCents } / 100.0
        val netAmount = saleAmount - totalRefunded

        // Card name
        holder.txtCard.text =
            "${sale.cardBrand} •••• ${sale.last4}"

        // Final amount on right
        holder.txtFinalAmount.text =
            String.format(Locale.getDefault(), "$%.2f", netAmount)

        // Refund list
        if (refunds.isNotEmpty()) {
            val refundText = buildString {
                refunds.forEach {
                    append("🔵 Refund -$")
                    append(String.format(Locale.getDefault(), "%.2f", it.amountInCents / 100.0))
                    append("\n")
                }
            }
            holder.txtRefunds.text = refundText
        } else {
            holder.txtRefunds.text = ""
        }
    }

    override fun getItemCount(): Int = items.size
}

