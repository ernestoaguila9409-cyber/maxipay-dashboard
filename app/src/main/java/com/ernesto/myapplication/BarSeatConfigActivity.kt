package com.ernesto.myapplication

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class BarSeatConfigActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var recyclerSeats: RecyclerView
    private lateinit var txtEmptyState: TextView
    private lateinit var adapter: SeatAdapter
    private val seats = mutableListOf<SeatItem>()

    private data class SeatItem(
        val docId: String,
        val name: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bar_seat_config)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Seats Configuration"

        recyclerSeats = findViewById(R.id.recyclerSeats)
        txtEmptyState = findViewById(R.id.txtEmptyState)

        adapter = SeatAdapter(
            seats = seats,
            onEdit = { seat -> showEditSeatDialog(seat) },
            onDelete = { seat -> confirmDeleteSeat(seat) }
        )
        recyclerSeats.layoutManager = LinearLayoutManager(this)
        recyclerSeats.adapter = adapter

        findViewById<View>(R.id.btnAddSeat).setOnClickListener {
            showAddSeatDialog()
        }

        loadSeats()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSeats() {
        db.collection("Tables")
            .whereEqualTo("active", true)
            .whereEqualTo("areaType", "BAR_SEAT")
            .get()
            .addOnSuccessListener { snap ->
                seats.clear()
                for (doc in snap.documents) {
                    seats.add(SeatItem(
                        docId = doc.id,
                        name = doc.getString("name") ?: "Bar Seat"
                    ))
                }
                seats.sortBy {
                    val match = Regex("(\\d+)").find(it.name)
                    match?.value?.toIntOrNull() ?: Int.MAX_VALUE
                }
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load seats: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateEmptyState() {
        if (seats.isEmpty()) {
            txtEmptyState.visibility = View.VISIBLE
            recyclerSeats.visibility = View.GONE
        } else {
            txtEmptyState.visibility = View.GONE
            recyclerSeats.visibility = View.VISIBLE
        }
    }

    private fun nextSeatNumber(): Int {
        var max = 0
        for (seat in seats) {
            val match = Regex("(\\d+)").find(seat.name)
            val num = match?.value?.toIntOrNull() ?: 0
            if (num > max) max = num
        }
        return max + 1
    }

    private fun showAddSeatDialog() {
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val container = FrameLayout(this)
        container.setPadding(pad, pad / 2, pad, 0)

        val edtName = EditText(this).apply {
            hint = "Seat name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText("Bar Seat ${nextSeatNumber()}")
        }
        container.addView(edtName)

        AlertDialog.Builder(this)
            .setTitle("Add Bar Seat")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val name = edtName.text.toString().trim()

                if (name.isBlank()) {
                    Toast.makeText(this, "Seat name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val data = hashMapOf(
                    "name" to name,
                    "seats" to 1,
                    "shape" to "SQUARE",
                    "posX" to 50.0,
                    "posY" to 50.0,
                    "section" to "Bar",
                    "areaType" to "BAR_SEAT",
                    "active" to true
                )

                db.collection("Tables").add(data)
                    .addOnSuccessListener { ref ->
                        seats.add(SeatItem(ref.id, name))
                        seats.sortBy {
                            val match = Regex("(\\d+)").find(it.name)
                            match?.value?.toIntOrNull() ?: Int.MAX_VALUE
                        }
                        adapter.notifyDataSetChanged()
                        updateEmptyState()
                        ensureBarSection()
                        Toast.makeText(this, "\"$name\" added", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditSeatDialog(seat: SeatItem) {
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val container = FrameLayout(this)
        container.setPadding(pad, pad / 2, pad, 0)

        val edtName = EditText(this).apply {
            hint = "Seat name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(seat.name)
        }
        container.addView(edtName)

        AlertDialog.Builder(this)
            .setTitle("Edit Bar Seat")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = edtName.text.toString().trim()

                if (name.isBlank()) {
                    Toast.makeText(this, "Seat name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                db.collection("Tables").document(seat.docId)
                    .update("name", name, "seats", 1)
                    .addOnSuccessListener {
                        val idx = seats.indexOfFirst { it.docId == seat.docId }
                        if (idx >= 0) {
                            seats[idx] = SeatItem(seat.docId, name)
                            adapter.notifyItemChanged(idx)
                        }
                        Toast.makeText(this, "\"$name\" updated", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSeat(seat: SeatItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Seat")
            .setMessage("Delete \"${seat.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                db.collection("Tables").document(seat.docId)
                    .update("active", false)
                    .addOnSuccessListener {
                        val idx = seats.indexOfFirst { it.docId == seat.docId }
                        if (idx >= 0) {
                            seats.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                        updateEmptyState()
                        renumberSeatsAfterDeletion()
                        Toast.makeText(this, "\"${seat.name}\" deleted", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renumberSeatsAfterDeletion() {
        if (seats.isEmpty()) return

        seats.sortBy {
            val match = Regex("(\\d+)").find(it.name)
            match?.value?.toIntOrNull() ?: Int.MAX_VALUE
        }

        for ((index, seat) in seats.withIndex()) {
            val newName = "Bar Seat ${index + 1}"
            if (seat.name != newName) {
                db.collection("Tables").document(seat.docId)
                    .update("name", newName)
                    .addOnSuccessListener {
                        val idx = seats.indexOfFirst { it.docId == seat.docId }
                        if (idx >= 0) {
                            seats[idx] = SeatItem(seat.docId, newName)
                            adapter.notifyItemChanged(idx)
                        }
                    }
            }
        }
    }

    private fun ensureBarSection() {
        db.collection("Sections").document("Bar")
            .set(hashMapOf("name" to "Bar"))
    }

    private class SeatAdapter(
        private val seats: List<SeatItem>,
        private val onEdit: (SeatItem) -> Unit,
        private val onDelete: (SeatItem) -> Unit
    ) : RecyclerView.Adapter<SeatAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtSeatName: TextView = itemView.findViewById(R.id.txtSeatName)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteSeat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bar_seat_config, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val seat = seats[position]
            holder.txtSeatName.text = seat.name
            holder.itemView.setOnClickListener { onEdit(seat) }
            holder.btnDelete.setOnClickListener { onDelete(seat) }
        }

        override fun getItemCount(): Int = seats.size
    }
}
