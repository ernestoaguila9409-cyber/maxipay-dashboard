package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class BatchListAdapter(
    private val batches: List<Map<String, Any>>,
    private val onBatchClick: (String) -> Unit
) : RecyclerView.Adapter<BatchListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtBatchId: TextView = view.findViewById(R.id.txtBatchId)
        val txtDate: TextView = view.findViewById(R.id.txtBatchDate)
        val txtSummary: TextView = view.findViewById(R.id.txtBatchSummary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val batch = batches[position]

        val batchId = batch["batchId"] as? String ?: ""
        val total = (batch["total"] as? Number)?.toDouble() ?: 0.0
        val count = (batch["transactionCount"] as? Number)?.toLong() ?: 0L
        val date = batch["formattedDate"] as? String ?: ""

        holder.txtBatchId.text = "Batch ID: $batchId"
        holder.txtDate.text = "Date: $date"
        holder.txtSummary.text =
            "Transactions: $count | Total: $${String.format(Locale.US, "%.2f", total)}"

        holder.itemView.setOnClickListener {
            onBatchClick(batchId)
        }
    }

    override fun getItemCount(): Int = batches.size
}
