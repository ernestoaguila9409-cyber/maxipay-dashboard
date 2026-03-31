package com.ernesto.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Loads existing [Categories] and builds a map normalizedKey → document id.
 * Use before importing menu items so OCR categories can reuse existing rows.
 */
object CategoryImportRepository {

  private const val COLLECTION = "Categories"

  /**
   * Fetches all categories and maps [CategoryNameUtils.normalizeCategoryName]-style keys to ids.
   * First document wins if two share the same key (legacy data).
   */
  suspend fun loadNormalizedCategoryIdMap(
    db: FirebaseFirestore
  ): MutableMap<String, String> {
    val snap = db.collection(COLLECTION).get().await()
    val map = LinkedHashMap<String, String>()
    for (doc in snap.documents) {
      val name = doc.getString("name")
      val stored = doc.getString("normalizedName")
      val key = CategoryNameUtils.normalizedKeyForDocument(name, stored)
      if (key.isNotBlank() && !map.containsKey(key)) {
        map[key] = doc.id
      }
    }
    return map
  }
}
