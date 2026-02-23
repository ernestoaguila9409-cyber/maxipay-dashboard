package com.ernesto.myapplication

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.google.firebase.firestore.FirebaseFirestore

class ModifierSelectionDialog(
    private val context: Context,
    private val onConfirm: (List<SelectedModifierOption>) -> Unit
) {

    private val db = FirebaseFirestore.getInstance()
    private val selectedOptions = mutableListOf<SelectedModifierOption>()

    fun show(itemId: String) {

        db.collection("ModifierGroups")
            .whereEqualTo("itemId", itemId)
            .get()
            .addOnSuccessListener { groupDocs ->

                val scrollView = ScrollView(context)
                val container = LinearLayout(context)
                container.orientation = LinearLayout.VERTICAL
                container.setPadding(40, 40, 40, 40)

                scrollView.addView(container)

                for (groupDoc in groupDocs) {

                    val groupId = groupDoc.id
                    val groupName = groupDoc.getString("name") ?: continue
                    val maxSelection =
                        groupDoc.getLong("maxSelection")?.toInt() ?: 1
                    val required =
                        groupDoc.getBoolean("required") ?: false

                    val title = TextView(context)
                    title.text =
                        "$groupName ${if (required) "*" else ""}"
                    title.textSize = 18f
                    container.addView(title)

                    val groupSelections =
                        mutableListOf<SelectedModifierOption>()

                    db.collection("ModifierOptions")
                        .whereEqualTo("groupId", groupId)
                        .get()
                        .addOnSuccessListener { optionDocs ->

                            if (maxSelection == 1) {

                                val radioGroup = RadioGroup(context)

                                for (optionDoc in optionDocs) {

                                    val optionId = optionDoc.id
                                    val name =
                                        optionDoc.getString("name") ?: ""
                                    val price =
                                        optionDoc.getDouble("price") ?: 0.0

                                    val radio =
                                        RadioButton(context)
                                    radio.text =
                                        "$name (+$${String.format("%.2f", price)})"

                                    radio.setOnCheckedChangeListener { _, isChecked ->
                                        if (isChecked) {
                                            groupSelections.clear()
                                            groupSelections.add(
                                                SelectedModifierOption(
                                                    groupId,
                                                    optionId,
                                                    name,
                                                    price
                                                )
                                            )
                                        }
                                    }

                                    radioGroup.addView(radio)
                                }

                                container.addView(radioGroup)

                            } else {

                                for (optionDoc in optionDocs) {

                                    val optionId = optionDoc.id
                                    val name =
                                        optionDoc.getString("name") ?: ""
                                    val price =
                                        optionDoc.getDouble("price") ?: 0.0

                                    val checkbox =
                                        CheckBox(context)
                                    checkbox.text =
                                        "$name (+$${String.format("%.2f", price)})"

                                    checkbox.setOnCheckedChangeListener { _, isChecked ->

                                        if (isChecked) {

                                            if (groupSelections.size < maxSelection) {
                                                groupSelections.add(
                                                    SelectedModifierOption(
                                                        groupId,
                                                        optionId,
                                                        name,
                                                        price
                                                    )
                                                )
                                            } else {
                                                checkbox.isChecked = false
                                                Toast.makeText(
                                                    context,
                                                    "Max $maxSelection selections allowed",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }

                                        } else {
                                            groupSelections.removeAll {
                                                it.optionId == optionId
                                            }
                                        }
                                    }

                                    container.addView(checkbox)
                                }
                            }
                        }

                    selectedOptions.addAll(groupSelections)
                }

                AlertDialog.Builder(context)
                    .setTitle("Select Modifiers")
                    .setView(scrollView)
                    .setPositiveButton("Add to Cart") { _, _ ->
                        onConfirm(selectedOptions)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }
}