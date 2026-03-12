package com.ernesto.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

data class Terminal(
    val id: String = "",
    val name: String = "",
    val tpn: String = "",
    val ipAddress: String = "",
    val registerId: String = "",
    val authKey: String = "",
    var status: String = "OFFLINE",
    var lastSeen: Long? = null
)

class TerminalAdapter(
    private val terminals: List<Terminal>,
    private val onClick: (Terminal) -> Unit
) : RecyclerView.Adapter<TerminalAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtTerminalName)
        val txtTpn: TextView = view.findViewById(R.id.txtTerminalTpn)
        val txtStatusBadge: TextView = view.findViewById(R.id.txtStatusBadge)
        val txtLastSeen: TextView = view.findViewById(R.id.txtLastSeen)
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

        val isOnline = terminal.status.equals("ONLINE", ignoreCase = true)
        holder.txtStatusBadge.text = if (isOnline) "ONLINE" else "OFFLINE"
        holder.txtStatusBadge.setBackgroundResource(
            if (isOnline) R.drawable.bg_status_online else R.drawable.bg_status_offline
        )

        if (isOnline) {
            holder.txtLastSeen.visibility = View.GONE
        } else {
            holder.txtLastSeen.visibility = View.VISIBLE
            holder.txtLastSeen.text = terminal.lastSeen?.let { formatLastSeen(it) }
                ?.let { "• Last seen $it" } ?: "• Last seen unknown"
        }

        holder.itemView.setOnClickListener { onClick(terminal) }
    }

    private fun formatLastSeen(timestampMs: Long): String {
        val diffMs = System.currentTimeMillis() - timestampMs
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
            hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
            else -> "$days day${if (days == 1L) "" else "s"} ago"
        }
    }

    override fun getItemCount() = terminals.size
}
