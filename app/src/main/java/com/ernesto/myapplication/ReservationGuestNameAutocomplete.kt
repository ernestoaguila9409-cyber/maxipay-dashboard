package com.ernesto.myapplication

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.min

/**
 * Debounced Firestore prefix search on guest name; suggestions render in [suggestionsRecycler]
 * inside the dialog (avoids ListPopupWindow drawing behind the dialog window).
 */
class ReservationGuestNameAutocomplete(
    private val activity: AppCompatActivity,
    private val dialog: AppCompatDialog,
    private val guestField: EditText,
    private val phoneField: EditText,
    private val suggestionsRecycler: RecyclerView,
    private val db: FirebaseFirestore,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var selectedCustomerId: String? = null
    private var selectedCustomerName: String? = null
    private var applyingSuggestion = false

    private val maxListHeightPx: Int =
        activity.resources.getDimensionPixelSize(R.dimen.reservation_customer_suggestions_max_height)

    private val rvAdapter = SuggestionRvAdapter { item ->
        applyingSuggestion = true
        guestField.setText(item.name)
        guestField.setSelection(item.name.length)
        if (!item.phone.isNullOrBlank()) {
            phoneField.setText(item.phone)
            phoneField.setSelection(item.phone.length)
        }
        selectedCustomerId = item.id
        selectedCustomerName = item.name
        applyingSuggestion = false
        hideSuggestions()
    }

    private val debouncedSearch = Runnable { runSearch() }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (applyingSuggestion) return
            val text = s?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                selectedCustomerId = null
                selectedCustomerName = null
                hideSuggestions()
                return
            }
            if (selectedCustomerName != null && text != selectedCustomerName) {
                selectedCustomerId = null
                selectedCustomerName = null
            }
            handler.removeCallbacks(debouncedSearch)
            if (text.length < CustomerFirestoreHelper.SEARCH_MIN_CHARS) {
                hideSuggestions()
                return
            }
            handler.postDelayed(debouncedSearch, CustomerFirestoreHelper.DEBOUNCE_MS)
        }
    }

    fun getSelectedCustomerId(): String? = selectedCustomerId

    fun onFieldFocus(field: EditText, hasFocus: Boolean) {
        if (field.id == R.id.etGuestName && !hasFocus) {
            hideSuggestions()
        }
    }

    fun start() {
        guestField.addTextChangedListener(textWatcher)
        suggestionsRecycler.layoutManager = LinearLayoutManager(activity)
        suggestionsRecycler.adapter = rvAdapter
        suggestionsRecycler.isNestedScrollingEnabled = false
    }

    fun destroy() {
        handler.removeCallbacks(debouncedSearch)
        guestField.removeTextChangedListener(textWatcher)
        hideSuggestions()
    }

    fun dismissPopup() {
        hideSuggestions()
    }

    private fun hideSuggestions() {
        rvAdapter.submit(emptyList())
        suggestionsRecycler.visibility = View.GONE
        val lp = suggestionsRecycler.layoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        suggestionsRecycler.layoutParams = lp
    }

    private fun showSuggestions(list: List<CustomerFirestoreHelper.CustomerSuggestion>) {
        if (list.isEmpty()) {
            hideSuggestions()
            return
        }
        rvAdapter.submit(list)
        val density = activity.resources.displayMetrics.density
        val rowPx = (48 * density).toInt()
        val paddingPx = (8 * density).toInt()
        val desired = min(list.size * rowPx + paddingPx, maxListHeightPx)
        val lp = suggestionsRecycler.layoutParams
        lp.height = desired
        suggestionsRecycler.layoutParams = lp
        suggestionsRecycler.visibility = View.VISIBLE
    }

    private fun runSearch() {
        if (!dialog.isShowing) return
        val raw = guestField.text?.toString()?.trim().orEmpty()
        if (raw.length < CustomerFirestoreHelper.SEARCH_MIN_CHARS) {
            hideSuggestions()
            return
        }
        CustomerFirestoreHelper.searchCustomersByNamePrefix(
            db = db,
            rawInput = raw,
            limit = CustomerFirestoreHelper.SEARCH_LIMIT,
            onResult = { list ->
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    val current = guestField.text?.toString()?.trim().orEmpty()
                    if (current.length < CustomerFirestoreHelper.SEARCH_MIN_CHARS) {
                        hideSuggestions()
                        return@runOnUiThread
                    }
                    showSuggestions(list)
                }
            },
            onError = { e ->
                activity.runOnUiThread {
                    if (dialog.isShowing) {
                        Toast.makeText(activity, e.message ?: "Search failed", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    private class SuggestionVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.txtAutoName)
        val phone: TextView = itemView.findViewById(R.id.txtAutoPhone)
    }

    private class SuggestionRvAdapter(
        private val onPick: (CustomerFirestoreHelper.CustomerSuggestion) -> Unit,
    ) : RecyclerView.Adapter<SuggestionVH>() {
        private val items = mutableListOf<CustomerFirestoreHelper.CustomerSuggestion>()

        fun submit(new: List<CustomerFirestoreHelper.CustomerSuggestion>) {
            items.clear()
            items.addAll(new)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_customer_autocomplete_row, parent, false)
            return SuggestionVH(v)
        }

        override fun onBindViewHolder(holder: SuggestionVH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            if (item.phone.isNullOrBlank()) {
                holder.phone.visibility = View.GONE
            } else {
                holder.phone.visibility = View.VISIBLE
                holder.phone.text = item.phone
            }
            holder.itemView.setOnClickListener { onPick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
