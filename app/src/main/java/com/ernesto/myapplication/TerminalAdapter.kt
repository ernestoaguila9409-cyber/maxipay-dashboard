package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Terminal(
    val id: String = "",
    val name: String = "",
    val tpn: String = "",
    val registerId: String = "",
    val authKey: String = ""
)

class TerminalAdapter(
    private val terminals: List<Terminal>,
    private val onClick: (Terminal) -> Unit
) : RecyclerView.Adapter<TerminalAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtTerminalName)
        val txtTpn: TextView = view.findViewById(R.id.txtTerminalTpn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_terminal, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val terminal = terminals[position]
        holder.txtName.text = terminal.name.ifBlank { "Unnamed Terminal" }
        holder.txtTpn.text = "TPN: ${terminal.tpn}"
        holder.itemView.setOnClickListener { onClick(terminal) }
    }

    override fun getItemCount() = terminals.size
}
