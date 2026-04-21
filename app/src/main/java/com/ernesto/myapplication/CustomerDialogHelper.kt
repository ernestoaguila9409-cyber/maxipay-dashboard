package com.ernesto.myapplication

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Reusable helper for capturing customer information (name, phone, email).
 * Uses the POS-style custom keyboard (same as Bar Seat) so the system IME
 * is never shown.
 */
object CustomerDialogHelper {

    data class CustomerInfo(
        val id: String? = null,
        val name: String,
        val phone: String = "",
        val email: String = ""
    )

    private data class SavedCustomer(
        val id: String,
        val name: String,
        val phone: String,
        val email: String
    ) {
        override fun toString(): String = name
    }

    fun showCustomerDialog(
        activity: Activity,
        initialName: String = "",
        initialPhone: String = "",
        initialEmail: String = "",
        onSave: (CustomerInfo) -> Unit
    ) {
        val allCustomers = mutableListOf<SavedCustomer>()

        fun dp(v: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics,
        ).toInt()

        val title = TextView(activity).apply {
            text = "Customer Information"
            setTypeface(null, Typeface.BOLD)
            textSize = 18f
            setTextColor(Color.parseColor("#111827"))
            setPadding(dp(20f), dp(20f), dp(20f), dp(8f))
        }

        val searchInput = EditText(activity).apply {
            hint = "Search saved customers..."
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setTextColor(Color.BLACK)
            textSize = 15f
        }

        val suggestionsList = ListView(activity).apply {
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            dividerHeight = 0
        }

        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1f),
            ).also { it.setMargins(dp(16f), dp(8f), dp(16f), dp(4f)) }
            setBackgroundColor(Color.parseColor("#E5E7EB"))
        }

        val sectionLabel = TextView(activity).apply {
            text = "Customer Details"
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16f), dp(8f), dp(16f), dp(4f))
            setTextColor(Color.parseColor("#555555"))
            textSize = 13f
        }

        val nameInput = EditText(activity).apply {
            hint = "Customer Name (required)"
            setText(initialName)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            textSize = 15f
        }

        val phoneInput = EditText(activity).apply {
            hint = "Phone (optional)"
            setText(initialPhone)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            textSize = 15f
        }

        val emailInput = EditText(activity).apply {
            hint = "Email (optional)"
            setText(initialEmail)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            textSize = 15f
        }

        val formContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4f), 0, dp(4f), dp(8f))
            addView(title)
            addView(
                searchInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(12f), dp(4f), dp(12f), dp(4f)) },
            )
            addView(
                suggestionsList,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(180f),
                ).apply { setMargins(dp(12f), 0, dp(12f), dp(4f)) },
            )
            addView(divider)
            addView(sectionLabel)
            addView(
                nameInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(12f), dp(4f), dp(12f), dp(4f)) },
            )
            addView(
                phoneInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(12f), dp(4f), dp(12f), dp(4f)) },
            )
            addView(
                emailInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(12f), dp(4f), dp(12f), dp(4f)) },
            )
        }

        val visibleCustomers = mutableListOf<SavedCustomer>()
        val adapter = object : ArrayAdapter<SavedCustomer>(
            activity,
            android.R.layout.simple_list_item_1,
            visibleCustomers,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val customer = getItem(position) ?: return view
                val display = buildString {
                    append(customer.name)
                    if (customer.phone.isNotBlank()) append("  •  ${customer.phone}")
                }
                view.text = display
                view.setTextColor(Color.BLACK)
                view.textSize = 14f
                view.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                return view
            }
        }
        suggestionsList.adapter = adapter

        fun refreshSuggestions() {
            val raw = searchInput.text?.toString()?.trim().orEmpty()
            val query = raw.lowercase()
            val digitsQuery = raw.filter { it.isDigit() }
            val matches = if (query.isEmpty()) {
                emptyList()
            } else {
                allCustomers.filter { c ->
                    c.name.lowercase().contains(query) ||
                        (digitsQuery.isNotEmpty() &&
                            c.phone.replace(Regex("\\D"), "").contains(digitsQuery))
                }
            }
            visibleCustomers.clear()
            visibleCustomers.addAll(matches.take(50))
            adapter.notifyDataSetChanged()
            suggestionsList.visibility =
                if (visibleCustomers.isNotEmpty()) View.VISIBLE else View.GONE
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshSuggestions() }
        })

        var selectedCustomerId: String? = null

        suggestionsList.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            selectedCustomerId = selected.id
            nameInput.setText(selected.name)
            phoneInput.setText(selected.phone)
            emailInput.setText(selected.email)
            searchInput.setText("")
            suggestionsList.visibility = View.GONE
            searchInput.clearFocus()
            nameInput.requestFocus()
        }

        val cancelBtn = MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "Cancel"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val saveBtn = MaterialButton(activity).apply {
            text = "Save"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val actionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            addView(
                cancelBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8f) },
            )
            addView(saveBtn)
        }

        val wrapped = BarSeatOrderKeypad.wrapFormWithKeypads(
            context = activity,
            formContent = formContent,
            actionBar = actionBar,
            alphaFields = listOf(searchInput, nameInput, emailInput),
            phoneFields = listOf(phoneInput),
        )

        phoneInput.filters = arrayOf(
            InputFilter { source, _, _, _, _, _ ->
                val filtered = source.filter { it.isDigit() }
                when {
                    filtered.length == source.length -> null
                    filtered.isEmpty() -> ""
                    else -> filtered
                }
            },
        )

        searchInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        nameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        emailInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        phoneInput.inputType = InputType.TYPE_CLASS_NUMBER

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dim = View(activity).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { /* consume — don't dismiss on outside tap */ }
        }

        val root = android.widget.FrameLayout(activity).apply {
            addView(
                dim,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                wrapped,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        formContent.background = ColorDrawable(Color.WHITE)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(activity, "Customer name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val info = CustomerInfo(
                id = selectedCustomerId,
                name = name,
                phone = phoneInput.text.toString().trim(),
                email = emailInput.text.toString().trim(),
            )
            dialog.dismiss()
            onSave(info)
        }

        dialog.setOnShowListener {
            loadSavedCustomers(allCustomers) { refreshSuggestions() }
        }

        dialog.show()
    }

    private fun loadSavedCustomers(
        allCustomers: MutableList<SavedCustomer>,
        onLoaded: () -> Unit,
    ) {
        FirebaseFirestore.getInstance().collection("Customers")
            .get()
            .addOnSuccessListener { snap ->
                data class Row(val id: String, val name: String, val phone: String, val email: String)

                val rows = snap.documents.mapNotNull { doc ->
                    val firstName = (doc.getString("firstName") ?: "").trim()
                    val lastName = (doc.getString("lastName") ?: "").trim()
                    val nameField = (doc.getString("name") ?: "").trim()
                    val fullName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                        "$firstName $lastName".trim()
                    } else {
                        nameField
                    }
                    if (fullName.isBlank()) return@mapNotNull null
                    Row(
                        id = doc.id,
                        name = fullName,
                        phone = doc.getString("phone") ?: "",
                        email = doc.getString("email") ?: "",
                    )
                }

                /** Same human when one normalized name is the other + a space + more (e.g. ernesto vs ernesto rodriguez). */
                fun namesLookLikeDuplicates(a: String, b: String): Boolean {
                    val an = CustomerFirestoreHelper.normalizeNameForMatch(a)
                    val bn = CustomerFirestoreHelper.normalizeNameForMatch(b)
                    if (an == bn) return true
                    val shorter = if (an.length <= bn.length) an else bn
                    val longer = if (an.length > bn.length) an else bn
                    if (shorter.length < 2) return false
                    if (!longer.startsWith(shorter)) return false
                    if (longer.length == shorter.length) return true
                    return longer[shorter.length] == ' '
                }

                fun pickBestRow(group: List<Row>): Row {
                    return group.maxWith(
                        compareBy<Row> { it.name.trim().length }
                            .thenByDescending { it.email.isNotBlank() },
                    )
                }

                val minDigitsForPhoneDedupe = 7
                val byPhone = rows
                    .filter { CustomerFirestoreHelper.normalizePhoneDigits(it.phone).length >= minDigitsForPhoneDedupe }
                    .groupBy { CustomerFirestoreHelper.normalizePhoneDigits(it.phone) }

                val usedIds = mutableSetOf<String>()
                val merged = mutableListOf<SavedCustomer>()

                for ((_, phoneGroup) in byPhone) {
                    if (phoneGroup.size == 1) {
                        val r = phoneGroup[0]
                        merged.add(SavedCustomer(r.id, r.name, r.phone, r.email))
                        usedIds.add(r.id)
                        continue
                    }
                    val clusters = mutableListOf<MutableList<Row>>()
                    for (r in phoneGroup) {
                        var placed = false
                        for (cl in clusters) {
                            if (cl.any { namesLookLikeDuplicates(it.name, r.name) }) {
                                cl.add(r)
                                placed = true
                                break
                            }
                        }
                        if (!placed) clusters.add(mutableListOf(r))
                    }
                    for (cl in clusters) {
                        val best = pickBestRow(cl)
                        val email = cl.firstOrNull { it.email.isNotBlank() }?.email ?: best.email
                        merged.add(SavedCustomer(best.id, best.name, best.phone, email))
                        cl.forEach { usedIds.add(it.id) }
                    }
                }

                for (r in rows) {
                    if (r.id in usedIds) continue
                    merged.add(SavedCustomer(r.id, r.name, r.phone, r.email))
                }

                allCustomers.clear()
                allCustomers.addAll(merged.sortedBy { it.name.lowercase() })
                onLoaded()
            }
    }
}
