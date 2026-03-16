package com.ernesto.myapplication

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class GlobalModifierActivity : AppCompatActivity() {

    private lateinit var groupRecycler: RecyclerView
    private lateinit var optionRecycler: RecyclerView
    private lateinit var editSearch: EditText
    private lateinit var txtOptionCount: android.widget.TextView

    private val db = FirebaseFirestore.getInstance()
    private var selectedGroup: ModifierGroupModel? = null
    private var currentOptionAdapter: ModifierOptionAdapter? = null
    private var groupAdapter: ModifierGroupAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_modifier)

        groupRecycler = findViewById(R.id.groupRecycler)
        optionRecycler = findViewById(R.id.optionRecycler)
        editSearch = findViewById(R.id.editSearch)
        txtOptionCount = findViewById(R.id.txtOptionCount)

        groupRecycler.layoutManager = LinearLayoutManager(this)
        optionRecycler.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btnAddGroup).setOnClickListener {
            showAddGroupDialog()
        }

        findViewById<View>(R.id.btnAddOption).setOnClickListener {
            if (selectedGroup == null) {
                Toast.makeText(this, "Select a group first", Toast.LENGTH_SHORT).show()
            } else {
                showAddOptionDialog()
            }
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentOptionAdapter?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadGroups()
    }

    override fun onResume() {
        super.onResume()
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
                    val maxSelection = doc.getLong("maxSelection")?.toInt() ?: 1
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

                list.sortBy { it.name.lowercase() }

                val selectedId = selectedGroup?.id
                if (selectedId != null && !list.any { it.id == selectedId }) {
                    selectedGroup = null
                    txtOptionCount.text = "Select a group"
                    currentOptionAdapter = null
                    optionRecycler.adapter = null
                } else if (selectedId != null) {
                    selectedGroup = list.find { it.id == selectedId }
                }

                groupAdapter = ModifierGroupAdapter(
                    groups = list,
                    onGroupClick = { group ->
                        selectedGroup = group
                        groupAdapter?.selectedGroupId = group.id
                        editSearch.setText("")
                        loadOptions(group.id)
                    },
                    context = this,
                    onDataChanged = { loadGroups() }
                ).also { adapter ->
                    groupRecycler.adapter = adapter
                    adapter.selectedGroupId = selectedGroup?.id
                }

                selectedGroup?.let { loadOptions(it.id) }
            }
    }

    private fun loadOptions(groupId: String) {
        db.collection("ModifierOptions")
            .whereEqualTo("groupId", groupId)
            .get()
            .addOnSuccessListener { documents ->
                val selected = selectedGroup ?: return@addOnSuccessListener
                if (selected.id != groupId) return@addOnSuccessListener

                val optionList = mutableListOf<ModifierOptionDisplay>()
                for (doc in documents) {
                    val name = doc.getString("name") ?: continue
                    val price = doc.getDouble("price") ?: 0.0
                    optionList.add(
                        ModifierOptionDisplay(
                            id = doc.id,
                            name = name,
                            price = price,
                            isRemove = selected.groupType == "REMOVE"
                        )
                    )
                }

                optionList.sortBy { it.name.lowercase() }

                val count = optionList.size
                txtOptionCount.text = "$count option${if (count != 1) "s" else ""}"

                val adapter = ModifierOptionAdapter(
                    context = this,
                    optionList = optionList,
                    refresh = { loadOptions(groupId) }
                )
                currentOptionAdapter = adapter
                optionRecycler.adapter = adapter
            }
    }

    private fun showAddGroupDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(this)
        nameInput.hint = "Group name (e.g. Size)"

        val requiredCheckbox = CheckBox(this)
        requiredCheckbox.text = "Required"

        val removeCheckbox = CheckBox(this)
        removeCheckbox.text = "Remove Ingredients Group"

        val maxSelectionInput = EditText(this)
        maxSelectionInput.hint = "Max Selection (e.g. 1)"
        maxSelectionInput.inputType = InputType.TYPE_CLASS_NUMBER

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
                val maxSelection = maxSelectionInput.text.toString().toIntOrNull() ?: 1

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
                            Toast.makeText(this, "Group added", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddOptionDialog() {
        val group = selectedGroup ?: return

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 10)

        val nameInput = EditText(this)
        nameInput.hint = "Option name (e.g. Small)"

        val priceInput = EditText(this)
        priceInput.hint = "Price adjustment"
        priceInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        layout.addView(nameInput)
        if (group.groupType != "REMOVE") {
            layout.addView(priceInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Option")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val price = if (group.groupType == "REMOVE") 0.0
                    else priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (name.isNotEmpty()) {
                    val option = hashMapOf(
                        "name" to name,
                        "price" to price,
                        "groupId" to group.id
                    )

                    db.collection("ModifierOptions")
                        .add(option)
                        .addOnSuccessListener {
                            loadOptions(group.id)
                            Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
