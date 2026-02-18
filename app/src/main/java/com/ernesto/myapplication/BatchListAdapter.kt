package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class BatchItem(
    val batchId: String,
    val date: String,
    val summary: String
)

class BatchListAdapter(
    private val batches: List<BatchItem>
) : RecyclerView.Adapter<BatchListAdapter.BatchViewHolder>() {

    class BatchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtBatchId: TextView = view.findViewById(R.id.txtBatchId)
        val txtBatchDate: TextView = view.findViewById(R.id.txtBatchDate)
        val txtBatchSummary: TextView = view.findViewById(R.id.txtBatchSummary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch, parent, false)
        return BatchViewHolder(view)
    }

    override fun onBindViewHolder(holder: BatchViewHolder, position: Int) {
        val batch = batches[position]

        holder.txtBatchId.text = "Batch ID: ${batch.batchId}"
        holder.txtBatchDate.text = "Date: ${batch.date}"
        holder.txtBatchSummary.text = batch.summary
    }

    override fun getItemCount(): Int = batches.size
}
