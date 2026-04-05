package com.ernesto.myapplication

import android.os.Bundle
import android.util.Log
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

    private val selectedHeader = ModifierItem(null, "Selected Modifiers", null, 0, false, isHeader = true)
    private val availableHeader = ModifierItem(null, "Available Modifiers", null, 0, false, isHeader = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recyclerView = RecyclerView(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator?.changeDuration = 0
        setContentView(recyclerView)

        itemId = intent.getStringExtra("ITEM_ID")
        title = intent.getStringExtra("ITEM_NAME")

        recyclerView.adapter = ModifierAdapter()

        attachDragHelper()
        loadModifierGroups()
    }

    // ── Index helpers ───────────────────────────────────────────────

    private fun availableHeaderIndex(): Int = modifierList.indexOf(availableHeader)

    // ── Initial load (runs once) ────────────────────────────────────

    private fun loadModifierGroups() {
        val iid = itemId ?: return

        db.collection("MenuItems").document(iid).get()
            .addOnSuccessListener { itemDoc ->
                @Suppress("UNCHECKED_CAST")
                val itemModGroupIds = (itemDoc.get("modifierGroupIds") as? List<String>) ?: emptyList()
                Log.d("Inventory", "Item modifierGroupIds: $itemModGroupIds")

                db.collection("ModifierGroups").get()
                    .addOnSuccessListener { groupDocs ->
                        Log.d("Inventory", "Loaded groups: ${groupDocs.size()}")

                        db.collection("ItemModifierGroups")
                            .whereEqualTo("itemId", iid)
                            .get()
                            .addOnSuccessListener { assignedDocs ->
                                val linkMap = mutableMapOf<String, Pair<String, Int>>()
                                for (doc in assignedDocs) {
                                    val groupId = doc.getString("groupId") ?: continue
                                    val displayOrder = doc.getLong("displayOrder")?.toInt() ?: 0
                                    linkMap[groupId] = Pair(doc.id, displayOrder)
                                }

                                val assignedFromItem = itemModGroupIds.toSet()
                                val assignedFromLinks = linkMap.keys
                                val allAssigned = assignedFromItem.union(assignedFromLinks)

                                val missingLinks = assignedFromItem - assignedFromLinks
                                if (missingLinks.isNotEmpty()) {
                                    syncMissingLinks(iid, missingLinks, itemModGroupIds)
                                }

                                val tempList = mutableListOf<ModifierItem>()
                                for (doc in groupDocs) {
                                    val groupId = doc.id
                                    val name = doc.getString("name") ?: continue
                                    val linkData = linkMap[groupId]
                                    val isAssigned = allAssigned.contains(groupId)
                                    val order = linkData?.second
                                        ?: if (assignedFromItem.contains(groupId)) {
                                            itemModGroupIds.indexOf(groupId) + 1
                                        } else 0

                                    tempList.add(
                                        ModifierItem(
                                            groupId = groupId,
                                            groupName = name,
                                            linkId = linkData?.first,
                                            displayOrder = order,
                                            isAssigned = isAssigned
                                        )
                                    )
                                }

                                val selected = tempList.filter { it.isAssigned }.sortedBy { it.displayOrder }
                                val unselected = tempList.filter { !it.isAssigned }

                                modifierList.clear()
                                modifierList.add(selectedHeader)
                                modifierList.addAll(selected)
                                modifierList.add(availableHeader)
                                modifierList.addAll(unselected)

                                recyclerView.adapter?.notifyDataSetChanged()
                            }
                    }
            }
            .addOnFailureListener { loadModifierGroupsLegacy() }
    }

    private fun syncMissingLinks(itemId: String, missingGroupIds: Set<String>, allIds: List<String>) {
        val batch = db.batch()
        for (groupId in missingGroupIds) {
            val ref = db.collection("ItemModifierGroups").document()
            batch.set(ref, hashMapOf(
                "itemId" to itemId,
                "groupId" to groupId,
                "displayOrder" to allIds.indexOf(groupId) + 1
            ))
        }
        batch.commit()
            .addOnSuccessListener { Log.d("Inventory", "Synced ${missingGroupIds.size} missing link docs") }
            .addOnFailureListener { Log.e("Inventory", "Failed to sync links: ${it.message}") }
    }

    private fun loadModifierGroupsLegacy() {
        db.collection("ModifierGroups").get()
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

                        val selected = tempList.filter { it.isAssigned }.sortedBy { it.displayOrder }
                        val unselected = tempList.filter { !it.isAssigned }

                        modifierList.clear()
                        modifierList.add(selectedHeader)
                        modifierList.addAll(selected)
                        modifierList.add(availableHeader)
                        modifierList.addAll(unselected)

                        recyclerView.adapter?.notifyDataSetChanged()
                    }
            }
    }

    // ── Drag-to-reorder ─────────────────────────────────────────────

    private fun attachDragHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
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
                if (fromItem.isHeader || toItem.isHeader) return false
                if (!fromItem.isAssigned || !toItem.isAssigned) return false

                modifierList.removeAt(from)
                modifierList.add(to, fromItem)
                recyclerView.adapter?.notifyItemMoved(from, to)
                persistToFirestore()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    // ── Optimistic assign (instant UI) ──────────────────────────────

    private fun assignGroup(item: ModifierItem) {
        val iid = itemId ?: return
        item.isAssigned = true

        val oldPos = modifierList.indexOf(item)
        if (oldPos < 0) return
        modifierList.removeAt(oldPos)

        val insertPos = availableHeaderIndex()
        modifierList.add(insertPos, item)

        recyclerView.adapter?.notifyItemMoved(oldPos, insertPos)

        val nextOrder = modifierList.filter { it.isAssigned && !it.isHeader }.size
        item.displayOrder = nextOrder

        val data = hashMapOf(
            "itemId" to iid,
            "groupId" to item.groupId,
            "displayOrder" to nextOrder
        )
        db.collection("ItemModifierGroups").add(data)
            .addOnSuccessListener { ref ->
                item.linkId = ref.id
                persistModifierGroupIdsOnItem()
            }
    }

    // ── Optimistic remove (instant UI) ──────────────────────────────

    private fun removeGroup(item: ModifierItem) {
        val iid = itemId ?: return
        val gid = item.groupId ?: return
        item.isAssigned = false

        val oldPos = modifierList.indexOf(item)
        if (oldPos < 0) return
        modifierList.removeAt(oldPos)

        modifierList.add(item)

        recyclerView.adapter?.notifyItemMoved(oldPos, modifierList.size - 1)

        val linkId = item.linkId
        item.linkId = null

        if (linkId != null) {
            db.collection("ItemModifierGroups").document(linkId).delete()
                .addOnSuccessListener { persistModifierGroupIdsOnItem() }
                .addOnFailureListener { deleteLinksAndPersist(iid, gid) }
        } else {
            deleteLinksAndPersist(iid, gid)
        }
    }

    // ── Background Firestore sync ───────────────────────────────────

    private fun deleteLinksAndPersist(itemId: String, groupId: String) {
        db.collection("ItemModifierGroups")
            .whereEqualTo("itemId", itemId)
            .whereEqualTo("groupId", groupId)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    persistModifierGroupIdsOnItem()
                    return@addOnSuccessListener
                }
                val batch = db.batch()
                for (doc in snap.documents) batch.delete(doc.reference)
                batch.commit().addOnCompleteListener { persistModifierGroupIdsOnItem() }
            }
            .addOnFailureListener { persistModifierGroupIdsOnItem() }
    }

    private fun persistModifierGroupIdsOnItem() {
        val iid = itemId ?: return
        val ids = modifierList
            .filter { it.isAssigned && !it.isHeader }
            .mapNotNull { it.groupId }

        db.collection("MenuItems").document(iid)
            .update("modifierGroupIds", ids)
            .addOnSuccessListener { Log.d("Inventory", "Synced modifierGroupIds: $ids") }
            .addOnFailureListener { Log.e("Inventory", "Failed to sync modifierGroupIds: ${it.message}") }
    }

    private fun persistToFirestore() {
        val iid = itemId ?: return
        val assigned = modifierList.filter { it.isAssigned && !it.isHeader }

        val batch = db.batch()
        assigned.forEachIndexed { index, item ->
            item.displayOrder = index + 1
            item.linkId?.let {
                batch.update(db.collection("ItemModifierGroups").document(it), "displayOrder", item.displayOrder)
            }
        }
        batch.commit()
        persistModifierGroupIdsOnItem()
    }

    // ── Adapter ─────────────────────────────────────────────────────

    inner class ModifierAdapter :
        RecyclerView.Adapter<ModifierAdapter.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (modifierList[position].isHeader) 0 else 1

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
                    if (isChecked) assignGroup(item) else removeGroup(item)
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
