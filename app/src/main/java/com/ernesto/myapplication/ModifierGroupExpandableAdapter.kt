package com.ernesto.myapplication

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class ModifierGroupExpandableAdapter(
    private val groups: MutableList<ModifierGroupModel>
) : RecyclerView.Adapter<ModifierGroupExpandableAdapter.GroupViewHolder>() {

    private val db = FirebaseFirestore.getInstance()

    inner class GroupViewHolder(val layout: LinearLayout) :
        RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val layout = LinearLayout(parent.context)
        layout.orientation = LinearLayout.VERTICAL
        return GroupViewHolder(layout)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {

        val group = groups[position]
        holder.layout.removeAllViews()

        val title = TextView(holder.layout.context)
        title.text = group.name
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setPadding(40, 40, 40, 40)

        holder.layout.addView(title)

        title.setOnClickListener {
            group.isExpanded = !group.isExpanded
            notifyItemChanged(position)
        }

        if (group.isExpanded) {

            loadOptions(group, holder)

            val addOption = TextView(holder.layout.context)
            addOption.text = "+ Add Option"
            addOption.setPadding(80, 30, 40, 30)
            addOption.setTextColor(0xFF6A4FB3.toInt())

            holder.layout.addView(addOption)

            addOption.setOnClickListener {
                showAddOptionDialog(holder.layout.context, group)
            }
        }
    }

    private fun loadOptions(group: ModifierGroupModel, holder: GroupViewHolder) {

        db.collection("ModifierOptions")
            .whereEqualTo("groupId", group.id)
            .get()
            .addOnSuccessListener { documents ->

                for (doc in documents) {

                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0

                    val optionText = TextView(holder.layout.context)
                    optionText.text =
                        "• $name   +$${String.format("%.2f", price)}"
                    optionText.setPadding(80, 20, 40, 20)

                    holder.layout.addView(optionText)

                    optionText.setOnClickListener {
                        showEditOptionDialog(
                            holder.layout.context,
                            doc.id,
                            name,
                            price
                        )
                    }
                }
            }
    }

    private fun showAddOptionDialog(
        context: android.content.Context,
        group: ModifierGroupModel
    ) {

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.hint = "Option Name (ex: Small)"

        val priceInput = EditText(context)
        priceInput.hint = "Price Adjustment"
        priceInput.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        layout.addView(priceInput)

        AlertDialog.Builder(context)
            .setTitle("Add Option")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (name.isNotEmpty()) {

                    val option = hashMapOf(
                        "name" to name,
                        "price" to price,
                        "groupId" to group.id
                    )

                    db.collection("ModifierOptions")
                        .add(option)
                        .addOnSuccessListener {
                            notifyDataSetChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditOptionDialog(
        context: android.content.Context,
        optionId: String,
        currentName: String,
        currentPrice: Double
    ) {

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(context)
        nameInput.setText(currentName)

        val priceInput = EditText(context)
        priceInput.setText(currentPrice.toString())
        priceInput.inputType =
            InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        layout.addView(priceInput)

        AlertDialog.Builder(context)
            .setTitle("Edit Option")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val newName = nameInput.text.toString().trim()
                val newPrice =
                    priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (newName.isNotEmpty()) {

                    val updates = mapOf(
                        "name" to newName,
                        "price" to newPrice
                    )

                    db.collection("ModifierOptions")
                        .document(optionId)
                        .update(updates)
                        .addOnSuccessListener {
                            notifyDataSetChanged()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->

                db.collection("ModifierOptions")
                    .document(optionId)
                    .delete()
                    .addOnSuccessListener {
                        notifyDataSetChanged()
                    }
            }
            .show()
    }
}