package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore

class DiscountsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: DiscountAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var txtNoDiscounts: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discounts)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Discounts"

        recycler = findViewById(R.id.recyclerDiscounts)
        txtNoDiscounts = findViewById(R.id.txtNoDiscounts)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = DiscountAdapter(
            onItemClick = { item ->
                val intent = Intent(this, AddDiscountActivity::class.java).apply {
                    putExtra("DISCOUNT_ID", item.id)
                    putExtra("DISCOUNT_NAME", item.name)
                    putExtra("DISCOUNT_TYPE", item.type)
                    putExtra("DISCOUNT_VALUE", item.value)
                    putExtra("DISCOUNT_APPLY_TO", item.applyTo)
                    putExtra("DISCOUNT_ACTIVE", item.active)
                    putExtra("DISCOUNT_APPLY_SCOPE", item.applyScope)
                    putExtra("DISCOUNT_AUTO_APPLY", item.autoApply)
                    putStringArrayListExtra("DISCOUNT_ITEM_IDS", ArrayList(item.itemIds))
                    item.schedule?.let { sched ->
                        putStringArrayListExtra("DISCOUNT_DAYS", ArrayList(sched.days))
                        putExtra("DISCOUNT_START_TIME", sched.startTime)
                        putExtra("DISCOUNT_END_TIME", sched.endTime)
                    }
                }
                loadItemNamesForDiscount(item) { names ->
                    intent.putStringArrayListExtra("DISCOUNT_ITEM_NAMES", ArrayList(names))
                    startActivity(intent)
                }
            },
            onToggleActive = { item, active -> setDiscountActive(item, active) }
        )
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAddDiscount).setOnClickListener {
            startActivity(Intent(this, AddDiscountActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadDiscounts()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadDiscounts() {
        db.collection("discounts")
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val type = doc.getString("type") ?: return@mapNotNull null
                    val value = (doc.getDouble("value") ?: doc.getLong("value")?.toDouble()) ?: return@mapNotNull null
                    val applyTo = doc.getString("applyTo") ?: "ORDER"
                    val active = doc.getBoolean("active") ?: true
                    val applyScope = doc.getString("applyScope") ?: if (applyTo == "ITEM") "item" else "order"
                    val autoApply = doc.getBoolean("autoApply") ?: true
                    val itemIds = (doc.get("itemIds") as? List<String>) ?: emptyList()

                    val schedMap = doc.get("schedule") as? Map<String, Any>
                    val schedule = if (schedMap != null) {
                        DiscountSchedule(
                            days = (schedMap["days"] as? List<String>) ?: emptyList(),
                            startTime = schedMap["startTime"]?.toString() ?: "",
                            endTime = schedMap["endTime"]?.toString() ?: ""
                        )
                    } else null

                    DiscountItem(
                        id = doc.id,
                        name = name,
                        type = type,
                        value = value,
                        applyTo = applyTo,
                        active = active,
                        applyScope = applyScope,
                        itemIds = itemIds,
                        schedule = schedule,
                        autoApply = autoApply
                    )
                }
                adapter.submitList(list)
                if (list.isEmpty()) {
                    recycler.visibility = View.GONE
                    txtNoDiscounts.visibility = View.VISIBLE
                } else {
                    recycler.visibility = View.VISIBLE
                    txtNoDiscounts.visibility = View.GONE
                }
            }
    }

    private fun loadItemNamesForDiscount(item: DiscountItem, onLoaded: (List<String>) -> Unit) {
        if (item.itemIds.isEmpty()) {
            onLoaded(emptyList())
            return
        }
        db.collection("discounts").document(item.id).get()
            .addOnSuccessListener { doc ->
                @Suppress("UNCHECKED_CAST")
                val names = (doc.get("itemNames") as? List<String>) ?: emptyList()
                onLoaded(names)
            }
            .addOnFailureListener { onLoaded(emptyList()) }
    }

    private fun setDiscountActive(item: DiscountItem, active: Boolean) {
        db.collection("discounts").document(item.id)
            .update("active", active)
            .addOnSuccessListener { loadDiscounts() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
