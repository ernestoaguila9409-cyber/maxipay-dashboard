package com.ernesto.myapplication

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DiscoveredIpAdapter(
    private val context: Context,
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
        val ip = printer.ipAddress.trim()
        val saved = PrinterDeviceType.values()
            .flatMap { SelectedPrinterPrefs.getAll(context, it) }
            .firstOrNull { it.ipAddress.trim() == ip }

        if (saved != null && saved.name.isNotBlank()) {
            holder.txtIp.text = saved.name
            holder.txtInfo.text = context.getString(
                R.string.discovered_printer_saved_line,
                ip,
                printer.displayLabel,
            )
        } else {
            holder.txtIp.text = printer.ipAddress
            holder.txtInfo.text = printer.displayLabel
        }
        holder.itemView.setOnClickListener { onPrinterClick(printer) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtIp: TextView = itemView.findViewById(R.id.txtIp)
        val txtInfo: TextView = itemView.findViewById(R.id.txtPrinterInfo)
    }
}
