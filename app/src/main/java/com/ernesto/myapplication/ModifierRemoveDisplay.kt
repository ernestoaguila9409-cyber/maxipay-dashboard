package com.ernesto.myapplication

import java.util.Locale

/**
 * Remove-style modifiers may be stored with the option label already prefixed (e.g. **"NO LETTUCE"**).
 * Avoid duplicating to **"No NO LETTUCE"** / **"NO NO LETTUCE"** when formatting for UI and printers.
 */
object ModifierRemoveDisplay {

    fun alreadyNoPrefixed(name: String): Boolean {
        val t = name.trim()
        if (t.isEmpty()) return false
        val u = t.uppercase(Locale.US)
        return u.startsWith("NO ") || u == "NO"
    }

    /** Cart, item lists, KDS-style lines: `lettuce` → `No lettuce`; `NO LETTUCE` → unchanged. */
    fun cartLine(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return t
        return if (alreadyNoPrefixed(t)) t else "No $t"
    }

    /** Receipt / ESC-POS lines that use uppercase **NO** prefix. */
    fun receiptNoLine(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return t
        return if (alreadyNoPrefixed(t)) t else "NO $t"
    }
}
