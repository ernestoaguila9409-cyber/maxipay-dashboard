package com.ernesto.myapplication

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.util.Locale

/**
 * Shared [Customers] collection.
 *
 * Prefix matching uses [nameSearch] when present, plus fallbacks on [name] and [firstName]
 * so older documents (first/last only, no [nameSearch]) still appear. Results are merged and
 * filtered client-side with [displayName] so casing/spacing matches the typed input.
 */
object CustomerFirestoreHelper {

    const val COLLECTION = "Customers"
    const val SEARCH_MIN_CHARS = 2
    const val SEARCH_LIMIT = 8L
    const val DEBOUNCE_MS = 300L

    private const val FIRST_NAME_SCAN_LIMIT = 24L

    data class CustomerSuggestion(
        val id: String,
        val name: String,
        val phone: String?,
    )

    fun nameSearchKey(displayName: String): String = displayName.trim().lowercase()

    private val whitespaceCollapse = Regex("\\s+")

    /**
     * Firestore [name] is usually Title Case; typed input is often lowercase. Lexicographic
     * [orderBy("name").startAt("liz")] misses "Liz…", so we also query with each word titlecased.
     */
    fun titleCaseWordsForNameRangeQuery(raw: String): String {
        val collapsed = raw.trim().replace(whitespaceCollapse, " ")
        if (collapsed.isEmpty()) return collapsed
        return collapsed.split(' ').joinToString(" ") { w ->
            w.lowercase(Locale.getDefault()).replaceFirstChar { ch ->
                ch.titlecase(Locale.getDefault())
            }
        }
    }

    /** Trim, lowercase, collapse internal whitespace (for exact name matching). */
    fun normalizeNameForMatch(name: String): String =
        name.trim().lowercase(Locale.getDefault()).replace(whitespaceCollapse, " ")

    /** Digits only — same idea as [CustomerDuplicateChecker] phone comparison. */
    fun normalizePhoneDigits(phone: String): String =
        phone.replace(Regex("[^0-9]"), "")

    /**
     * If exactly one customer has the same display name and phone (after normalization), returns
     * their document id; otherwise null (zero matches, several matches, or phone missing).
     */
    fun resolveCustomerIdByExactNameAndPhone(
        db: FirebaseFirestore,
        guestName: String,
        phone: String,
        onResult: (String?) -> Unit,
        onError: (Exception) -> Unit = { },
    ) {
        val nameNorm = normalizeNameForMatch(guestName)
        val phoneDigits = normalizePhoneDigits(phone)
        if (nameNorm.isEmpty() || phoneDigits.isEmpty()) {
            onResult(null)
            return
        }
        db.collection(COLLECTION)
            .get()
            .addOnSuccessListener { snap ->
                val matches = snap.documents.filter { doc ->
                    val dn = normalizeNameForMatch(displayName(doc))
                    val pd = normalizePhoneDigits(doc.getString("phone") ?: "")
                    dn == nameNorm && pd == phoneDigits
                }
                val id = if (matches.size == 1) matches[0].id else null
                onResult(id)
            }
            .addOnFailureListener(onError)
    }

    /** Display name: first+last if either set, else [name]. */
    fun displayName(doc: DocumentSnapshot): String {
        val first = doc.getString("firstName")?.trim().orEmpty()
        val last = doc.getString("lastName")?.trim().orEmpty()
        val single = doc.getString("name")?.trim().orEmpty()
        return if (first.isNotEmpty() || last.isNotEmpty()) {
            "$first $last".trim()
        } else {
            single
        }
    }

    fun suggestionFromDoc(doc: DocumentSnapshot): CustomerSuggestion {
        val phone = doc.getString("phone")?.trim().orEmpty()
        return CustomerSuggestion(
            id = doc.id,
            name = displayName(doc),
            phone = phone.ifEmpty { null },
        )
    }

    /**
     * Prefix search: merges [nameSearch], [name], and [firstName] range queries, then keeps rows
     * whose display name lowercases to a string starting with [rawInput] (trimmed, lowercased).
     */
    fun searchCustomersByNamePrefix(
        db: FirebaseFirestore,
        rawInput: String,
        limit: Long = SEARCH_LIMIT,
        onResult: (List<CustomerSuggestion>) -> Unit,
        onError: (Exception) -> Unit = { },
    ) {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase(Locale.getDefault())
        if (lower.length < SEARCH_MIN_CHARS) {
            onResult(emptyList())
            return
        }

        val tasks = mutableListOf<Task<QuerySnapshot>>()
        tasks += db.collection(COLLECTION)
            .orderBy("nameSearch")
            .startAt(lower)
            .endAt(lower + "\uf8ff")
            .limit(limit)
            .get()

        tasks += db.collection(COLLECTION)
            .orderBy("name")
            .startAt(trimmed)
            .endAt(trimmed + "\uf8ff")
            .limit(limit)
            .get()

        val titleWords = titleCaseWordsForNameRangeQuery(trimmed)
        if (titleWords.length >= SEARCH_MIN_CHARS && titleWords != trimmed) {
            tasks += db.collection(COLLECTION)
                .orderBy("name")
                .startAt(titleWords)
                .endAt(titleWords + "\uf8ff")
                .limit(limit)
                .get()
        }

        val firstToken = trimmed.substringBefore(' ').trim()
        if (firstToken.length >= SEARCH_MIN_CHARS) {
            val firstForQuery = firstToken.lowercase(Locale.getDefault()).replaceFirstChar { ch ->
                ch.titlecase(Locale.getDefault())
            }
            tasks += db.collection(COLLECTION)
                .orderBy("firstName")
                .startAt(firstForQuery)
                .endAt(firstForQuery + "\uf8ff")
                .limit(FIRST_NAME_SCAN_LIMIT)
                .get()
        }

        val nameTokens = trimmed.split(whitespaceCollapse).filter { it.length >= SEARCH_MIN_CHARS }
        if (nameTokens.size >= 2) {
            val lastTok = nameTokens.last().lowercase(Locale.getDefault()).replaceFirstChar { ch ->
                ch.titlecase(Locale.getDefault())
            }
            tasks += db.collection(COLLECTION)
                .orderBy("lastName")
                .startAt(lastTok)
                .endAt(lastTok + "\uf8ff")
                .limit(FIRST_NAME_SCAN_LIMIT)
                .get()
        }

        Tasks.whenAllComplete(tasks)
            .addOnCompleteListener {
                val merged = LinkedHashMap<String, DocumentSnapshot>()
                for (t in tasks) {
                    if (t.isSuccessful) {
                        val snap = t.result ?: continue
                        for (doc in snap.documents) {
                            merged[doc.id] = doc
                        }
                    }
                }
                val needleNorm = normalizeNameForMatch(trimmed)
                val out = merged.values
                    .asSequence()
                    .map { suggestionFromDoc(it) }
                    .filter {
                        it.name.isNotBlank() &&
                            normalizeNameForMatch(it.name).startsWith(needleNorm)
                    }
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase(Locale.getDefault()) }
                    .take(limit.toInt())
                    .toList()
                onResult(out)
            }
            .addOnFailureListener(onError)
    }

    /**
     * Resolves [preferredCustomerId], else exact name+phone match, else creates a minimal [Customers] row
     * so every reservation can store [ReservationFirestoreHelper.FIELD_CUSTOMER_ID].
     */
    fun ensureCustomerIdForReservation(
        db: FirebaseFirestore,
        guestName: String,
        phone: String,
        preferredCustomerId: String?,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val pref = preferredCustomerId?.trim().orEmpty()
        if (pref.isNotEmpty()) {
            onResult(pref)
            return
        }
        resolveCustomerIdByExactNameAndPhone(
            db = db,
            guestName = guestName,
            phone = phone,
            onResult = { resolved ->
                if (!resolved.isNullOrBlank()) {
                    onResult(resolved)
                } else {
                    createMinimalCustomerForReservation(db, guestName, phone, onResult, onError)
                }
            },
            onError = onError,
        )
    }

    private fun createMinimalCustomerForReservation(
        db: FirebaseFirestore,
        guestName: String,
        phone: String,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val name = guestName.trim()
        if (name.isEmpty()) {
            onError(IllegalArgumentException("Guest name is required"))
            return
        }
        val parts = name.split(whitespaceCollapse).filter { it.isNotBlank() }
        val firstName = parts.firstOrNull() ?: name
        val lastName = if (parts.size >= 2) parts.drop(1).joinToString(" ") else ""
        val map = hashMapOf<String, Any>(
            "firstName" to firstName,
            "lastName" to lastName,
            "name" to name,
            "nameSearch" to nameSearchKey(name),
            "phone" to phone.trim(),
            "createdAt" to Timestamp.now(),
        )
        db.collection(COLLECTION)
            .add(map)
            .addOnSuccessListener { doc -> onResult(doc.id) }
            .addOnFailureListener(onError)
    }
}
