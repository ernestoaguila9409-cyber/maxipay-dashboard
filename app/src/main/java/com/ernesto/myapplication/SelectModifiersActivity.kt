package com.ernesto.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class SelectModifiersActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val checkBoxes = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val preSelected = intent.getStringArrayListExtra("SELECTED_IDS")?.toSet() ?: emptySet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 2f
            setPadding(40, 0, 40, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 112
            )
        }
        val title = TextView(this).apply {
            text = "Assign Modifiers"
            textSize = 18f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        root.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 100)
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 4f
            setPadding(24, 16, 24, 16)
        }
        val saveBtn = TextView(this).apply {
            text = "Save"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(48, 24, 48, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#6366F1"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { saveAndFinish() }
        }
        bottomBar.addView(saveBtn)
        root.addView(bottomBar)

        setContentView(root)

        db.collection("ModifierGroups").get()
            .addOnSuccessListener { snap ->
                val groups = snap.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    Pair(doc.id, name)
                }.sortedBy { it.second }

                if (groups.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = "No modifier groups configured"
                        textSize = 14f
                        setTextColor(Color.GRAY)
                        setPadding(40, 40, 40, 40)
                    }
                    listContainer.addView(empty)
                    return@addOnSuccessListener
                }

                for ((groupId, groupName) in groups) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundColor(Color.WHITE)
                        setPadding(32, 28, 32, 28)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8 }
                        layoutParams = lp
                        elevation = 1f
                    }

                    val cb = CheckBox(this).apply {
                        text = groupName
                        textSize = 15f
                        setTextColor(Color.parseColor("#1E293B"))
                        isChecked = preSelected.contains(groupId)
                    }
                    card.addView(cb)
                    checkBoxes[groupId] = cb

                    card.setOnClickListener { cb.isChecked = !cb.isChecked }
                    listContainer.addView(card)
                }
            }
    }

    private fun saveAndFinish() {
        val selected = ArrayList(checkBoxes.filter { it.value.isChecked }.keys.toList())
        val result = Intent().apply {
            putStringArrayListExtra("SELECTED_IDS", selected)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        saveAndFinish()
    }
}
