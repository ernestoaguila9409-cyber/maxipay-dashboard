package com.volt.shared

import java.util.Locale

object ModifierRemoveDisplay {

    fun alreadyNoPrefixed(name: String): Boolean {
        val t = name.trim()
        if (t.isEmpty()) return false
        val u = t.uppercase(Locale.US)
        return u.startsWith("NO ") || u == "NO"
    }

    fun cartLine(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return t
        return if (alreadyNoPrefixed(t)) t else "No $t"
    }

    fun receiptNoLine(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return t
        return if (alreadyNoPrefixed(t)) t else "NO $t"
    }
}
