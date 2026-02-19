package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class BatchTransactionAdapter(
    private val transactions: List<Map<String, Any>>
) : RecyclerView.Adapter<BatchTransactionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtInfo: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = transactions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val transaction = transactions[position]

        val amount = transaction["amount"] as? Double ?: 0.0
        val date = transaction["timestamp"] as? Date
        val last4 = transaction["last4"] as? String ?: ""
        val cardBrand = transaction["cardBrand"] as? String ?: ""

        val formattedDate = if (date != null) {
            SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(date)
        } else {
            ""
        }

        holder.txtInfo.text =
            "$cardBrand •••• $last4\n$%.2f\n$formattedDate".format(amount)
    }
}
