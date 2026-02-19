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

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val saleWithRefunds = items[position]
        val sale = saleWithRefunds.sale
        val refunds = saleWithRefunds.refunds

        // ----------------------------
        // SALE INFO
        // ----------------------------
        holder.txtCard.text =
            "${sale.cardBrand} •••• ${sale.last4}"

        val saleAmount = sale.amountInCents / 100.0

        holder.txtFinalAmount.text =
            String.format(Locale.US, "$%.2f", saleAmount)

        // ----------------------------
        // REFUNDS + NET
        // ----------------------------
        if (refunds.isNotEmpty()) {

            val totalRefunds =
                refunds.sumOf { it.amountInCents } / 100.0

            val net = saleAmount - totalRefunds

            val refundLines = refunds.joinToString("\n") {
                "🔵 Refund -$" +
                        String.format(
                            Locale.US,
                            "%.2f",
                            it.amountInCents / 100.0
                        )
            }

            val netLine =
                "\nNet: $" +
                        String.format(Locale.US, "%.2f", net)

            holder.txtRefunds.visibility = View.VISIBLE
            holder.txtRefunds.text = refundLines + netLine

        } else {
            holder.txtRefunds.visibility = View.GONE
        }
    }
}
