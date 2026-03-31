package com.ernesto.myapplication

import java.util.Locale

/**
 * Normalizes category names for deduplication (OCR / manual import vs existing Firestore categories).
 *
 * Rules:
 * - Trim, lowercase (ROOT)
 * - Keep Unicode letters, digits, spaces; strip other characters
 * - Collapse whitespace
 * - Remove trailing "s" only for a **single word** longer than 3 chars (basic plural match)
 */
object CategoryNameUtils {

  fun normalizeCategoryName(name: String): String {
    var s = name.trim().lowercase(Locale.ROOT)
    val builder = StringBuilder()
    for (ch in s) {
      when {
        ch.isLetterOrDigit() -> builder.append(ch)
        ch.isWhitespace() -> builder.append(' ')
        else -> { /* strip punctuation */ }
      }
    }
    s = builder.toString().replace(Regex("\\s+"), " ").trim()
    if (!s.contains(' ') && s.length > 3 && s.endsWith('s')) {
      s = s.dropLast(1)
    }
    return s
  }

  /** Title-case for storing a human-readable category name on create. */
  fun formatCategoryDisplayName(raw: String): String {
    return raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word ->
      val lower = word.lowercase(Locale.ROOT)
      lower.replaceFirstChar { it.uppercaseChar() }
    }
  }

  /**
   * Map key for an existing Firestore category document.
   * Prefer stored [normalizedName] when present; otherwise derive from [name].
   */
  fun normalizedKeyForDocument(
    name: String?,
    storedNormalizedName: String?
  ): String {
    val raw = storedNormalizedName?.takeIf { it.isNotBlank() } ?: (name ?: "")
    return normalizeCategoryName(raw)
  }
}
