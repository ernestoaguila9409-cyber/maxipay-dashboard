package com.ernesto.myapplication

import android.os.Bundle
import android.widget.EditText
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

                    list.add(
                        ModifierGroupModel(
                            id = doc.id,
                            name = name
                        )
                    )
                }

                recycler.adapter = ModifierGroupExpandableAdapter(list)
            }
    }

    private fun showAddGroupDialog() {

        val input = EditText(this)
        input.hint = "Group name (ex: Size, Toppings)"

        AlertDialog.Builder(this)
            .setTitle("Add Modifier Group")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {

                    val group = hashMapOf(
                        "name" to name
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