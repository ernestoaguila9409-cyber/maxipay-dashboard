package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DiscoveredIpAdapter(
    private val onPrinterClick: (DetectedPrinter) -> Unit,
) : RecyclerView.Adapter<DiscoveredIpAdapter.VH>() {

    private val items = mutableListOf<DetectedPrinter>()

    fun submitList(printers: List<DetectedPrinter>) {
        items.clear()
        items.addAll(printers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discovered_printer_ip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val printer = items[position]
        holder.txtIp.text = printer.ipAddress
        holder.txtInfo.text = printer.displayLabel
        holder.itemView.setOnClickListener { onPrinterClick(printer) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtIp: TextView = itemView.findViewById(R.id.txtIp)
        val txtInfo: TextView = itemView.findViewById(R.id.txtPrinterInfo)
    }
}
