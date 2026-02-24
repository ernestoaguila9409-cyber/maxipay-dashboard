package com.ernesto.myapplication

import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class AssignModifierToItemActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var recyclerView: RecyclerView
    private var itemId: String? = null

    private val modifierList = mutableListOf<ModifierItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recyclerView = RecyclerView(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        setContentView(recyclerView)

        itemId = intent.getStringExtra("ITEM_ID")
        title = intent.getStringExtra("ITEM_NAME")

        recyclerView.adapter = ModifierAdapter()

        attachDragHelper()
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

                        val assignedMap = mutableMapOf<String, Pair<String, Int>>()

                        for (doc in assignedDocs) {
                            val groupId = doc.getString("groupId") ?: continue
                            val displayOrder = doc.getLong("displayOrder")?.toInt() ?: 0
                            assignedMap[groupId] = Pair(doc.id, displayOrder)
                        }

                        val tempList = mutableListOf<ModifierItem>()

                        for (doc in groupDocs) {

                            val groupId = doc.id
                            val name = doc.getString("name") ?: continue

                            val assignedData = assignedMap[groupId]

                            tempList.add(
                                ModifierItem(
                                    groupId = groupId,
                                    groupName = name,
                                    linkId = assignedData?.first,
                                    displayOrder = assignedData?.second ?: 0,
                                    isAssigned = assignedData != null
                                )
                            )
                        }

                        val selected = tempList
                            .filter { it.isAssigned }
                            .sortedBy { it.displayOrder }

                        val unselected = tempList
                            .filter { !it.isAssigned }

                        modifierList.clear()

                        // Header Selected
                        modifierList.add(
                            ModifierItem(
                                groupId = null,
                                groupName = "Selected Modifiers",
                                linkId = null,
                                displayOrder = 0,
                                isAssigned = false,
                                isHeader = true
                            )
                        )

                        modifierList.addAll(selected)

                        // Header Available
                        modifierList.add(
                            ModifierItem(
                                groupId = null,
                                groupName = "Available Modifiers",
                                linkId = null,
                                displayOrder = 0,
                                isAssigned = false,
                                isHeader = true
                            )
                        )

                        modifierList.addAll(unselected)

                        recyclerView.adapter?.notifyDataSetChanged()
                    }
            }
    }

    private fun attachDragHelper() {

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {

                val from = viewHolder.adapterPosition
                val to = target.adapterPosition

                val fromItem = modifierList[from]
                val toItem = modifierList[to]

                // Prevent header drag
                if (fromItem.isHeader || toItem.isHeader) return false

                // Prevent dragging unselected
                if (!fromItem.isAssigned || !toItem.isAssigned) return false

                val item = modifierList.removeAt(from)
                modifierList.add(to, item)

                recyclerView.adapter?.notifyItemMoved(from, to)

                updateDisplayOrder()

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }

        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    private fun updateDisplayOrder() {

        val batch = db.batch()

        modifierList
            .filter { it.isAssigned && !it.isHeader }
            .forEachIndexed { index, item ->

                item.displayOrder = index + 1

                item.linkId?.let {
                    val ref = db.collection("ItemModifierGroups").document(it)
                    batch.update(ref, "displayOrder", item.displayOrder)
                }
            }

        batch.commit()
    }

    private fun assignGroup(item: ModifierItem) {

        val nextOrder = modifierList
            .filter { it.isAssigned && !it.isHeader }
            .size + 1

        val data = hashMapOf(
            "itemId" to itemId,
            "groupId" to item.groupId,
            "displayOrder" to nextOrder
        )

        db.collection("ItemModifierGroups")
            .add(data)
            .addOnSuccessListener {
                loadModifierGroups()
            }
    }

    private fun removeGroup(item: ModifierItem) {

        item.linkId?.let {
            db.collection("ItemModifierGroups")
                .document(it)
                .delete()
                .addOnSuccessListener {
                    loadModifierGroups()
                }
        }
    }

    inner class ModifierAdapter :
        RecyclerView.Adapter<ModifierAdapter.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (modifierList[position].isHeader) 0 else 1
        }

        inner class ViewHolder(
            view: android.view.View,
            val checkBox: CheckBox?
        ) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

            if (viewType == 0) {
                val textView = android.widget.TextView(parent.context)
                textView.textSize = 20f
                textView.setTypeface(null, android.graphics.Typeface.BOLD)
                textView.setPadding(40, 80, 40, 30)
                return ViewHolder(textView, null)
            }

            val container = androidx.appcompat.widget.LinearLayoutCompat(parent.context)
            container.orientation = androidx.appcompat.widget.LinearLayoutCompat.HORIZONTAL
            container.setPadding(40, 40, 40, 40)

            val checkBox = CheckBox(parent.context)
            container.addView(checkBox)

            return ViewHolder(container, checkBox)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {

            val item = modifierList[position]

            if (item.isHeader) {
                (holder.itemView as android.widget.TextView).text = item.groupName
                return
            }

            holder.checkBox?.apply {
                setOnCheckedChangeListener(null)
                text = item.groupName
                isChecked = item.isAssigned

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        assignGroup(item)
                    } else {
                        removeGroup(item)
                    }
                }
            }
        }

        override fun getItemCount(): Int = modifierList.size
    }

    data class ModifierItem(
        val groupId: String?,
        val groupName: String,
        var linkId: String?,
        var displayOrder: Int,
        var isAssigned: Boolean,
        val isHeader: Boolean = false
    )
}