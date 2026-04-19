package com.ernesto.kds.data

enum class KdsTextSettingKey {
    Header,
    TableInfo,
    CustomerName,
    Timer,
    ItemName,
    Modifiers,
    Buttons,
}

fun KdsTextSettings.adjust(key: KdsTextSettingKey, deltaSteps: Int): KdsTextSettings {
    val d = KdsTextSettings.STEP_SP * deltaSteps
    return when (key) {
        KdsTextSettingKey.Header -> copy(headerSp = headerSp + d)
        KdsTextSettingKey.TableInfo -> copy(tableInfoSp = tableInfoSp + d)
        KdsTextSettingKey.CustomerName -> copy(customerNameSp = customerNameSp + d)
        KdsTextSettingKey.Timer -> copy(timerSp = timerSp + d)
        KdsTextSettingKey.ItemName -> copy(itemNameSp = itemNameSp + d)
        KdsTextSettingKey.Modifiers -> copy(modifiersSp = modifiersSp + d)
        KdsTextSettingKey.Buttons -> copy(buttonsSp = buttonsSp + d)
    }.coerce()
}

/**
 * Per-device UI text scale for the KDS, stored at
 * `kds_devices/{deviceId}/settings/ui`.
 *
 * Values are in **sp** (scaled pixels) for use in Compose `sp` units.
 */
data class KdsTextSettings(
    val headerSp: Float,
    val tableInfoSp: Float,
    val customerNameSp: Float,
    val timerSp: Float,
    val itemNameSp: Float,
    val modifiersSp: Float,
    val buttonsSp: Float,
    /** ARGB for modifiers with POS action **ADD** (default / options). */
    val modifierAddColorArgb: Long,
    /** ARGB for modifiers with POS action **REMOVE** (hold / “No …” lines). */
    val modifierRemoveColorArgb: Long,
) {
    fun coerce(): KdsTextSettings = copy(
        headerSp = headerSp.coerceIn(MIN_SP, MAX_SP),
        tableInfoSp = tableInfoSp.coerceIn(MIN_SP, MAX_SP),
        customerNameSp = customerNameSp.coerceIn(MIN_SP, MAX_SP),
        timerSp = timerSp.coerceIn(MIN_SP, MAX_SP),
        itemNameSp = itemNameSp.coerceIn(MIN_SP, MAX_SP),
        modifiersSp = modifiersSp.coerceIn(MIN_SP, MAX_SP_MODIFIERS),
        buttonsSp = buttonsSp.coerceIn(MIN_SP, MAX_SP),
        modifierAddColorArgb = modifierAddColorArgb and 0xFFFFFFFFL,
        modifierRemoveColorArgb = modifierRemoveColorArgb and 0xFFFFFFFFL,
    )

    companion object {
        const val MIN_SP = 10f
        /** Upper bound for most roles (header, timer, item name, etc.). */
        const val MAX_SP = 44f
        /**
         * Modifiers stack under item lines; larger values collapse line height on narrow cards.
         * Kept separate so other text can still scale up on big displays.
         */
        const val MAX_SP_MODIFIERS = 30f
        const val STEP_SP = 1f

        /** Matches the previous hardcoded KDS appearance before settings existed. */
        val Default = KdsTextSettings(
            headerSp = 24f,
            tableInfoSp = 15f,
            customerNameSp = 15f,
            timerSp = 18f,
            itemNameSp = 24f,
            modifiersSp = 18f,
            buttonsSp = 20f,
            modifierAddColorArgb = 0xFF555555L,
            modifierRemoveColorArgb = 0xFFC62828L,
        ).coerce()
    }

    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "headerSp" to headerSp,
        "tableInfoSp" to tableInfoSp,
        "customerNameSp" to customerNameSp,
        "timerSp" to timerSp,
        "itemNameSp" to itemNameSp,
        "modifiersSp" to modifiersSp,
        "buttonsSp" to buttonsSp,
        "modifierAddColorArgb" to modifierAddColorArgb,
        "modifierRemoveColorArgb" to modifierRemoveColorArgb,
    )
}

/** Parse KDS device settings/ui document fields; missing keys use [KdsTextSettings.Default]. */
fun parseKdsTextSettings(data: Map<String, Any>?): KdsTextSettings {
    if (data == null) return KdsTextSettings.Default
    fun f(key: String, def: Float, max: Float = KdsTextSettings.MAX_SP): Float {
        val v = data.get(key) ?: return def
        return (v as? Number)?.toFloat()?.coerceIn(KdsTextSettings.MIN_SP, max) ?: def
    }
    val d = KdsTextSettings.Default
    val tableInfoSp = f("tableInfoSp", d.tableInfoSp)
    val customerNameSp = when (val v = data.get("customerNameSp")) {
        null -> tableInfoSp
        is Number -> v.toFloat().coerceIn(KdsTextSettings.MIN_SP, KdsTextSettings.MAX_SP)
        else -> tableInfoSp
    }
    fun colorLong(key: String, def: Long): Long {
        val v = data.get(key) ?: return def
        return (v as? Number)?.toLong()?.and(0xFFFFFFFFL) ?: def
    }
    return KdsTextSettings(
        headerSp = f("headerSp", d.headerSp),
        tableInfoSp = tableInfoSp,
        customerNameSp = customerNameSp,
        timerSp = f("timerSp", d.timerSp),
        itemNameSp = f("itemNameSp", d.itemNameSp),
        modifiersSp = f("modifiersSp", d.modifiersSp, KdsTextSettings.MAX_SP_MODIFIERS),
        buttonsSp = f("buttonsSp", d.buttonsSp),
        modifierAddColorArgb = colorLong("modifierAddColorArgb", d.modifierAddColorArgb),
        modifierRemoveColorArgb = colorLong("modifierRemoveColorArgb", d.modifierRemoveColorArgb),
    ).coerce()
}
