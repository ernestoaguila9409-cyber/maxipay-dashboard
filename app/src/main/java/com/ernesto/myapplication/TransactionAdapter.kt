package com.ernesto.myapplication

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
        val txtInfo: TextView = itemView.findViewById(R.id.txtTransactionInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction = transactions[position]

        val amount = transaction.amountInCents / 100.0
        val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
            .format(Date(transaction.date))

        val text = """
            Type: ${transaction.paymentType}
            Amount: $%.2f
            Date: $date
            Ref: ${transaction.referenceId}
        """.trimIndent().format(amount)

        holder.txtInfo.text = text

        // CLICK LISTENER
        holder.itemView.setOnClickListener {
            onTransactionClick(transaction)
        }
    }

    override fun getItemCount(): Int = transactions.size
}
