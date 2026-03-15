package com.ernesto.myapplication

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore

class GlobalModifierActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var btnAdd: FloatingActionButton
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_modifier)

        recycler = findViewById(R.id.recyclerModifierGroups)
        btnAdd = findViewById(R.id.btnAddModifierGroup)

        recycler.layoutManager = LinearLayoutManager(this)

        btnAdd.setOnClickListener {
            showAddGroupDialog()
        }

        loadGroups()
    }

    private fun loadGroups() {

        db.collection("ModifierGroups")
            .get()
            .addOnSuccessListener { documents ->

                val list = mutableListOf<ModifierGroupModel>()

                for (doc in documents) {

                    val name = doc.getString("name") ?: continue
                    val required = doc.getBoolean("required") ?: false
                    val maxSelection =
                        doc.getLong("maxSelection")?.toInt() ?: 1
                    val groupType = doc.getString("groupType") ?: "ADD"

                    list.add(
                        ModifierGroupModel(
                            id = doc.id,
                            name = name,
                            required = required,
                            maxSelection = maxSelection,
                            groupType = groupType
                        )
                    )
                }

                recycler.adapter =
                    ModifierGroupExpandableAdapter(list)
            }
    }

    private fun showAddGroupDialog() {

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(this)
        nameInput.hint = "Group name (ex: Size)"

        val requiredCheckbox = CheckBox(this)
        requiredCheckbox.text = "Required"

        val removeCheckbox = CheckBox(this)
        removeCheckbox.text = "Remove Ingredients Group"

        val maxSelectionInput = EditText(this)
        maxSelectionInput.hint = "Max Selection (ex: 1)"
        maxSelectionInput.inputType =
            InputType.TYPE_CLASS_NUMBER

        layout.addView(nameInput)
        layout.addView(requiredCheckbox)
        layout.addView(removeCheckbox)
        layout.addView(maxSelectionInput)

        AlertDialog.Builder(this)
            .setTitle("Add Modifier Group")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val name = nameInput.text.toString().trim()
                val required = requiredCheckbox.isChecked
                val groupType = if (removeCheckbox.isChecked) "REMOVE" else "ADD"
                val maxSelection =
                    maxSelectionInput.text.toString().toIntOrNull() ?: 1

                if (name.isNotEmpty()) {

                    val group = hashMapOf(
                        "name" to name,
                        "required" to required,
                        "maxSelection" to maxSelection,
                        "groupType" to groupType
                    )

                    db.collection("ModifierGroups")
                        .add(group)
                        .addOnSuccessListener {
                            loadGroups()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}