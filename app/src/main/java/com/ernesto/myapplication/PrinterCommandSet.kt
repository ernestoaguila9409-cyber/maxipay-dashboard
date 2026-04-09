package com.ernesto.myapplication

/**
 * ESC/POS dialect used when building kitchen chit payloads.
 *
 * - [ESCPOS]: standard Epson ESC/POS (`GS !` for size, `ESC E n` for bold).
 *   Works on Epson TM-T88, TM-T20, and most thermal receipt printers.
 * - [STAR_DOT_MATRIX]: Star SP700 / SP742 impact printers in Star Line / Dot Matrix mode.
 *   Uses `ESC h` (height), `ESC W` (width), `ESC E`/`ESC F` (bold) instead of `GS !`.
 */
enum class PrinterCommandSet(val firestoreValue: String) {
    ESCPOS("ESCPOS"),
    STAR_DOT_MATRIX("STAR_DOT_MATRIX");

    companion object {
        fun fromFirestore(raw: String?): PrinterCommandSet {
            val u = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.firestoreValue == u } ?: ESCPOS
        }

        /** Star impact printers that need Star Line Mode commands (not ESC/POS). */
        private val STAR_DOT_MATRIX_MODEL = Regex(
            """SP\s?7\d{2}|SP742|SP712|SP747|SP500""",
            RegexOption.IGNORE_CASE,
        )

        /** Star thermal printers that speak ESC/POS (Star's emulation mode). */
        private val STAR_ESCPOS_MODEL = Regex(
            """TSP\d{3,4}|mC-Print\d?|mPOP|SM-[A-Z0-9]+""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Infers the command set from manufacturer name and/or model string.
         * Model patterns are checked first because they are the strongest signal —
         * the manufacturer string may be wrong when ESC/POS probes confuse detection.
         */
        fun infer(manufacturer: String?, model: String? = null): PrinterCommandSet {
            val mdl = model?.trim()?.lowercase().orEmpty()
            val mfr = manufacturer?.trim()?.lowercase().orEmpty()
            val combined = "$mfr $mdl"

            if (STAR_DOT_MATRIX_MODEL.containsMatchIn(combined)) return STAR_DOT_MATRIX
            if (STAR_ESCPOS_MODEL.containsMatchIn(combined)) return ESCPOS
            if (Regex("""tm-[a-z0-9]""").containsMatchIn(combined)) return ESCPOS

            if (mfr.contains("star")) return STAR_DOT_MATRIX
            return ESCPOS
        }

        @Deprecated("Use infer(manufacturer, model) instead", ReplaceWith("infer(manufacturer)"))
        fun inferFromManufacturer(manufacturer: String?): PrinterCommandSet = infer(manufacturer)
    }
}
