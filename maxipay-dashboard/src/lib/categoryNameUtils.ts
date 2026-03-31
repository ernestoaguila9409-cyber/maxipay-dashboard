/**
 * Shared rules for matching menu categories across OCR / imports (case, plural, punctuation).
 * Mirrors Android `CategoryNameUtils.normalizeCategoryName`.
 */

export function normalizeCategoryName(name: string): string {
  let s = name.trim().toLowerCase();
  let out = "";
  for (const ch of s) {
    if (/[\p{L}\p{N}]/u.test(ch)) {
      out += ch;
    } else if (/\s/.test(ch)) {
      out += " ";
    }
  }
  s = out.replace(/\s+/g, " ").trim();
  // Single-word trailing "s" only (avoid mangling "French Fries")
  if (!s.includes(" ") && s.length > 3 && s.endsWith("s")) {
    s = s.slice(0, -1);
  }
  return s;
}

/** Title-case for new category display name. */
export function formatCategoryDisplayName(raw: string): string {
  return raw
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(" ");
}
