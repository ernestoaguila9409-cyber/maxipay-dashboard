package com.ernesto.myapplication

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class AssignModifierToItemActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var container: LinearLayout
    private var itemId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(40, 40, 40, 40)

        setContentView(container)

        itemId = intent.getStringExtra("ITEM_ID")
        title = intent.getStringExtra("ITEM_NAME")

        loadModifierGroups()
    }

    private fun loadModifierGroups() {

        db.collection("ModifierGroups")
            .get()
            .addOnSuccessListener { groupDocs ->

                db.collection("ItemModifierGroups")
                    .whereEqualTo("itemId", itemId)
                    .get()
                    .addOnSuccessListener { assignedDocs ->

                        val assignedGroupIds =
                            assignedDocs.map { it.getString("groupId") }

                        for (doc in groupDocs) {

                            val groupId = doc.id
                            val groupName = doc.getString("name") ?: continue

                            val checkBox = CheckBox(this)
                            checkBox.text = groupName

                            // ✅ Pre-check if already assigned
                            if (assignedGroupIds.contains(groupId)) {
                                checkBox.isChecked = true
                            }

                            container.addView(checkBox)

                            checkBox.setOnCheckedChangeListener { _, isChecked ->

                                if (isChecked) {
                                    assignGroup(groupId)
                                } else {
                                    removeGroup(groupId)
                                }
                            }
                        }
                    }
            }
    }

    private fun assignGroup(groupId: String) {

        val data = hashMapOf(
            "itemId" to itemId,
            "groupId" to groupId
        )

        db.collection("ItemModifierGroups").add(data)
    }

    private fun removeGroup(groupId: String) {

        db.collection("ItemModifierGroups")
            .whereEqualTo("itemId", itemId)
            .whereEqualTo("groupId", groupId)
            .get()
            .addOnSuccessListener { documents ->
                for (doc in documents) {
                    db.collection("ItemModifierGroups")
                        .document(doc.id)
                        .delete()
                }
            }
    }
}