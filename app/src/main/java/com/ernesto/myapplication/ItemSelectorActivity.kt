package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class ItemSelectorActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var recycler: RecyclerView
    private lateinit var edtSearch: EditText
    private lateinit var txtCount: TextView
    private lateinit var btnApply: View
    private lateinit var btnDone: View
    private lateinit var btnBack: View

    private val allItems = mutableListOf<MenuItemData>()
    private val categories = mutableMapOf<String, String>()
    private val subcategoryNames = mutableMapOf<String, String>()
    private val selectedIds = mutableSetOf<String>()
    private val adapter = SelectorAdapter()
    private var searchQuery = ""

    data class MenuItemData(
        val id: String,
        val name: String,
        val price: Double,
        val categoryId: String,
        val subcategoryId: String = ""
    )

    sealed class ListItem {
        data class Header(val name: String, val count: Int) : ListItem()
        data class Row(
            val id: String,
            val name: String,
            val price: Double,
            val selected: Boolean
        ) : ListItem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_item_selector)

        recycler = findViewById(R.id.recyclerItems)
        edtSearch = findViewById(R.id.edtSearch)
        txtCount = findViewById(R.id.txtSelectionCount)
        btnApply = findViewById(R.id.btnApply)
        btnDone = findViewById(R.id.btnDone)
        btnBack = findViewById(R.id.btnBack)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        intent.getStringArrayListExtra("SELECTED_IDS")?.let {
            selectedIds.addAll(it)
        }

        btnBack.setOnClickListener { finish() }
        btnDone.setOnClickListener { applyAndFinish() }
        btnApply.setOnClickListener { applyAndFinish() }

        edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                rebuildList()
            }
        })

        loadData()
    }

    private fun loadData() {
        db.collection("Categories").get()
            .addOnSuccessListener { catSnap ->
                catSnap.documents.forEach { doc ->
                    val name = doc.getString("name") ?: return@forEach
                    categories[doc.id] = name
                }
                db.collection("subcategories").get()
                    .addOnSuccessListener { subSnap ->
                        subSnap.documents.forEach { doc ->
                            val name = doc.getString("name") ?: return@forEach
                            subcategoryNames[doc.id] = name
                        }
                        loadItems()
                    }
                    .addOnFailureListener { loadItems() }
            }
            .addOnFailureListener {
                loadItems()
            }
    }

    private fun loadItems() {
        db.collection("MenuItems").get()
            .addOnSuccessListener { snap ->
                allItems.clear()
                snap.documents.forEach { doc ->
                    val name = doc.getString("name") ?: return@forEach
                    @Suppress("UNCHECKED_CAST")
                    val pricingRaw = doc.get("pricing") as? Map<String, Any>
                    val pricingPos = (pricingRaw?.get("pos") as? Number)?.toDouble()
                    val price = pricingPos
                        ?: doc.getDouble("price")
                        ?: doc.getLong("price")?.toDouble()
                        ?: 0.0
                    val catId = doc.getString("categoryId") ?: ""
                    val subId = doc.getString("subcategoryId") ?: ""
                    allItems.add(MenuItemData(doc.id, name, price, catId, subId))
                }
                allItems.sortBy { it.name.lowercase(Locale.US) }
                rebuildList()
                updateCount()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load items", Toast.LENGTH_SHORT).show()
            }
    }

    private fun rebuildList() {
        val filtered = if (searchQuery.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.name.lowercase(Locale.US).contains(searchQuery) }
        }

        val grouped = linkedMapOf<String, MutableList<MenuItemData>>()
        for (item in filtered) {
            val catName = categories[item.categoryId] ?: "Uncategorized"
            grouped.getOrPut(catName) { mutableListOf() }.add(item)
        }

        val list = mutableListOf<ListItem>()
        for ((catName, items) in grouped.entries.sortedBy { it.key }) {
            list.add(ListItem.Header(catName, items.size))

            val hasSubcategories = items.any { it.subcategoryId.isNotEmpty() }
            if (hasSubcategories) {
                val subGrouped = linkedMapOf<String, MutableList<MenuItemData>>()
                val noSub = mutableListOf<MenuItemData>()
                for (item in items) {
                    if (item.subcategoryId.isNotEmpty()) {
                        val subName = subcategoryNames[item.subcategoryId] ?: "Other"
                        subGrouped.getOrPut(subName) { mutableListOf() }.add(item)
                    } else {
                        noSub.add(item)
                    }
                }
                for (item in noSub) {
                    list.add(ListItem.Row(item.id, item.name, item.price, item.id in selectedIds))
                }
                for ((subName, subItems) in subGrouped.entries.sortedBy { it.key }) {
                    list.add(ListItem.Header("  $subName", subItems.size))
                    for (item in subItems) {
                        list.add(ListItem.Row(item.id, item.name, item.price, item.id in selectedIds))
                    }
                }
            } else {
                for (item in items) {
                    list.add(ListItem.Row(item.id, item.name, item.price, item.id in selectedIds))
                }
            }
        }

        adapter.submitList(list)
    }

    private fun toggleItem(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        rebuildList()
        updateCount()
    }

    private fun updateCount() {
        val n = selectedIds.size
        txtCount.text = "$n item${if (n != 1) "s" else ""} selected"
    }

    private fun applyAndFinish() {
        val ids = ArrayList(selectedIds.toList())
        val names = ArrayList(ids.mapNotNull { id ->
            allItems.find { it.id == id }?.name
        })
        val result = Intent().apply {
            putStringArrayListExtra("SELECTED_IDS", ids)
            putStringArrayListExtra("SELECTED_NAMES", names)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private inner class SelectorAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items = listOf<ListItem>()

        fun submitList(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is ListItem.Header -> 0
            is ListItem.Row -> 1
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inflater.inflate(R.layout.item_selector_header, parent, false))
            } else {
                RowVH(inflater.inflate(R.layout.item_selector_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> (holder as HeaderVH).bind(item)
                is ListItem.Row -> (holder as RowVH).bind(item)
            }
        }

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val txtName: TextView = view.findViewById(R.id.txtCategoryName)
            private val txtCount: TextView = view.findViewById(R.id.txtCategoryCount)
            fun bind(header: ListItem.Header) {
                txtName.text = header.name.uppercase(Locale.US)
                txtCount.text = "${header.count} items"
            }
        }

        inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
            private val txtName: TextView = view.findViewById(R.id.txtItemName)
            private val txtPrice: TextView = view.findViewById(R.id.txtItemPrice)
            private val imgCheck: ImageView = view.findViewById(R.id.imgCheck)

            fun bind(row: ListItem.Row) {
                txtName.text = row.name
                txtPrice.text = String.format(Locale.US, "$%.2f", row.price)
                imgCheck.setImageResource(
                    if (row.selected) R.drawable.ic_check_filled else R.drawable.ic_check_empty
                )
                itemView.setOnClickListener { toggleItem(row.id) }
            }
        }
    }
}
