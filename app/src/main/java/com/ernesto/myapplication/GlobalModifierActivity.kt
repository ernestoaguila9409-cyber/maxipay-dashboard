package com.ernesto.myapplication

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FieldValue
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

                    val rawOptions = doc.get("options") as? List<Map<String, Any>> ?: emptyList()
                    val options = rawOptions.mapIndexed { i, opt ->
                        val rawAct = opt["action"] as? String
                        val optAction = if (groupType == "REMOVE") {
                            "REMOVE"
                        } else if (rawAct?.trim()?.uppercase() == "REMOVE") {
                            "REMOVE"
                        } else {
                            "ADD"
                        }
                        ModifierOptionEntry(
                            id = opt["id"] as? String ?: "opt_${i}",
                            name = opt["name"] as? String ?: "",
                            price = (opt["price"] as? Number)?.toDouble() ?: 0.0,
                            triggersModifierGroupIds = (opt["triggersModifierGroupIds"] as? List<String>) ?: emptyList(),
                            action = optAction,
                            imageUrl = (opt["imageUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }

                    list.add(
                        ModifierGroupModel(
                            id = doc.id,
                            name = name,
                            required = required,
                            maxSelection = maxSelection,
                            groupType = groupType,
                            options = options
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
        val selected = selectedGroup ?: return
        if (selected.id != groupId) return

        val isRemove = selected.groupType == "REMOVE"

        db.collection("ModifierGroups").document(groupId).get()
            .addOnSuccessListener { groupDoc ->
                if (selectedGroup?.id != groupId) return@addOnSuccessListener

                @Suppress("UNCHECKED_CAST")
                val rawEmbedded = groupDoc.get("options") as? List<Map<String, Any>> ?: emptyList()

                if (rawEmbedded.isNotEmpty()) {
                    val optionList = rawEmbedded.mapIndexed { i, opt ->
                        @Suppress("UNCHECKED_CAST")
                        val rawAct = opt["action"] as? String
                        val storedAction = if (isRemove) {
                            "REMOVE"
                        } else if (rawAct?.trim()?.uppercase() == "REMOVE") {
                            "REMOVE"
                        } else {
                            "ADD"
                        }
                        val showRemoveStyle = isRemove || storedAction == "REMOVE"
                        ModifierOptionDisplay(
                            id = (opt["id"] as? String) ?: "opt_$i",
                            name = (opt["name"] as? String) ?: "",
                            price = (opt["price"] as? Number)?.toDouble() ?: 0.0,
                            isRemove = showRemoveStyle,
                            triggersModifierGroupIds = (opt["triggersModifierGroupIds"] as? List<String>) ?: emptyList(),
                            action = storedAction,
                            imageUrl = (opt["imageUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }.sortedBy { it.name.lowercase() }

                    val count = optionList.size
                    txtOptionCount.text = "$count option${if (count != 1) "s" else ""}"

                    currentOptionAdapter = ModifierOptionAdapter(
                        context = this,
                        optionList = optionList,
                        refresh = { loadGroups() },
                        groupId = groupId,
                        useEmbeddedOptions = true,
                        groupType = selected.groupType,
                    )
                    optionRecycler.adapter = currentOptionAdapter
                    return@addOnSuccessListener
                }

                db.collection("ModifierOptions")
                    .whereEqualTo("groupId", groupId)
                    .get()
                    .addOnSuccessListener { documents ->
                        if (selectedGroup?.id != groupId) return@addOnSuccessListener

                        val optionList = mutableListOf<ModifierOptionDisplay>()
                        for (doc in documents) {
                            val name = doc.getString("name") ?: continue
                            val price = doc.getDouble("price") ?: 0.0
                            val rawAct = doc.getString("action")
                            val storedAction = if (isRemove) {
                                "REMOVE"
                            } else if (rawAct?.trim()?.uppercase() == "REMOVE") {
                                "REMOVE"
                            } else {
                                "ADD"
                            }
                            val showRemoveStyle = isRemove || storedAction == "REMOVE"
                            optionList.add(
                                ModifierOptionDisplay(
                                    id = doc.id,
                                    name = name,
                                    price = price,
                                    isRemove = showRemoveStyle,
                                    triggersModifierGroupIds = (doc.get("triggersModifierGroupIds") as? List<String>) ?: emptyList(),
                                    action = storedAction,
                                    imageUrl = doc.getString("imageUrl")?.trim()?.takeIf { it.isNotEmpty() },
                                )
                            )
                        }

                        optionList.sortBy { it.name.lowercase() }

                        val count = optionList.size
                        txtOptionCount.text = "$count option${if (count != 1) "s" else ""}"

                        currentOptionAdapter = ModifierOptionAdapter(
                            context = this,
                            optionList = optionList,
                            refresh = { loadOptions(groupId) },
                            groupId = groupId,
                            useEmbeddedOptions = false,
                            groupType = selected.groupType,
                        )
                        optionRecycler.adapter = currentOptionAdapter
                    }
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
        db.collection("ModifierGroups").get()
            .addOnSuccessListener { snap ->
                val otherGroups = snap.documents.mapNotNull { doc ->
                    val gName = doc.getString("name") ?: return@mapNotNull null
                    if (doc.id == group.id) return@mapNotNull null
                    val required = doc.getBoolean("required") ?: false
                    val maxSel = doc.getLong("maxSelection")?.toInt() ?: 1
                    @Suppress("UNCHECKED_CAST")
                    val optCount = (doc.get("options") as? List<*>)?.size ?: 0
                    Triple(doc.id, gName,
                        "${if (required) "Required" else "Optional"} · 1–$maxSel sel · $optCount opt")
                }.sortedBy { it.second }
                buildAddOptionDialog(group, otherGroups)
            }
            .addOnFailureListener { buildAddOptionDialog(group, emptyList()) }
    }

    private fun buildAddOptionDialog(
        group: ModifierGroupModel,
        otherGroups: List<Triple<String, String, String>>
    ) {
        val showPrice = group.groupType != "REMOVE"
        val form = ModifierOptionFormHelper.inflateForm(
            this,
            showPrice = showPrice,
            otherGroups = otherGroups,
            initialSelected = emptySet(),
            startExpanded = false,
        )

        val dialogRoot = ModifierOptionKeypadHost.wrap(this, form)
        val removeStyleCb = CheckBox(this).apply {
            text = getString(R.string.modifier_option_remove_style_checkbox)
            visibility = if (group.groupType == "REMOVE") View.GONE else View.VISIBLE
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (group.groupType != "REMOVE") addView(removeStyleCb)
            addView(
                dialogRoot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MyApplication_MaterialAlertDialog)
            .setTitle("Add Option")
            .setView(shell)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = form.editName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    form.tilName.error = getString(R.string.item_detail_name_required)
                    return@setOnClickListener
                }
                form.tilName.error = null
                val price = if (showPrice) {
                    form.editPrice.text?.toString()?.toDoubleOrNull() ?: 0.0
                } else {
                    0.0
                }
                val selectedTriggers = form.triggers.selectedTriggerGroupIds()

                val optId = "opt_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(5)}"
                val optAction = when {
                    group.groupType == "REMOVE" -> "REMOVE"
                    removeStyleCb.isChecked -> "REMOVE"
                    else -> "ADD"
                }
                val option = hashMapOf<String, Any>(
                    "id" to optId,
                    "name" to name,
                    "price" to price,
                    "action" to optAction,
                )
                if (selectedTriggers.isNotEmpty()) {
                    option["triggersModifierGroupIds"] = selectedTriggers
                }

                db.collection("ModifierGroups")
                    .document(group.id)
                    .update("options", FieldValue.arrayUnion(option))
                    .addOnSuccessListener {
                        dialog.dismiss()
                        loadGroups()
                        Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to add option", Toast.LENGTH_SHORT).show()
                    }
            }
        }
        dialog.show()
    }
}
