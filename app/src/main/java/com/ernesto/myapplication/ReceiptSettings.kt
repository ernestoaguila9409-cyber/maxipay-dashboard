package com.ernesto.myapplication

import android.content.Context
import android.util.Log
import java.util.Locale
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

const val LINE_WIDTH = 48
const val LINE_WIDTH_WIDE = 24

fun formatLine(left: String, right: String, width: Int = LINE_WIDTH): String {
    val space = (width - left.length - right.length).coerceAtLeast(1)
    return left + " ".repeat(space) + right
}

/** Landi C20 Pro 58mm: timestamp only so "MM/dd/yyyy hh:mm a" fits on one line. */
fun receiptOrderInfoDateLine(dateStr: String): String = dateStr

/**
 * Maps gateway [CardData.EntryType] (e.g. Dejavoo spinpos) to a short thermal-receipt label.
 */
fun receiptLabelForCardEntryType(raw: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val u = s.uppercase(Locale.US)
    return when {
        u.contains("CONTACTLESS") || u.contains("CTLS") || u.contains("NFC") ||
            u.contains("TAP") || u == "PROX" || u.contains("PROXIMITY") -> "Contactless"
        u.contains("CHIP") || u.contains("ICC") || u.contains("INSERT") ||
            (u.contains("EMV") && !u.contains("CONTACTLESS")) -> "Chip"
        u.contains("SWIPE") || u.contains("MAG") || u.contains("MSR") || u.contains("TRACK") -> "Swipe"
        else -> s.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() }
    }
}

data class ReceiptSettings(
    val businessName: String = "My Restaurant",
    val addressText: String = "123 Main Street\nCity, ST 12345\nTel: (555) 123-4567",
    val email: String = "",
    val logoUrl: String = "",
    val showServerName: Boolean = true,
    val showDateTime: Boolean = true,
    val showLogo: Boolean = true,
    val showEmail: Boolean = false,
    val boldBizName: Boolean = true,
    val boldAddress: Boolean = true,
    val boldOrderInfo: Boolean = true,
    val boldItems: Boolean = false,
    val boldTotals: Boolean = false,
    val boldGrandTotal: Boolean = true,
    val boldFooter: Boolean = false,
    val fontSizeBizName: Int = 2,    // 0=Normal, 1=Large, 2=X-Large
    val fontSizeAddress: Int = 2,
    val fontSizeOrderInfo: Int = 2,
    val fontSizeItems: Int = 0,
    val fontSizeTotals: Int = 0,
    val fontSizeGrandTotal: Int = 1,
    val fontSizeFooter: Int = 0
) {
    companion object {
        private const val PREFS = "receipt_settings"

        val FONT_SIZE_LABELS = arrayOf("Normal", "Large", "X-Large")

        // ESC/POS GS ! n — character size select
        // 0x00 = 1x width, 1x height  (Normal)
        // 0x01 = 1x width, 2x height  (Large / tall)
        // 0x11 = 2x width, 2x height  (X-Large)
        fun escPosSizeBytes(setting: Int): ByteArray = byteArrayOf(
            0x1D, 0x21,
            when (setting) {
                1 -> 0x01
                2 -> 0x11
                else -> 0x00
            }
        )

        fun lineWidthForSize(setting: Int): Int = when (setting) {
            2 -> LINE_WIDTH_WIDE
            else -> LINE_WIDTH
        }

        fun load(context: Context): ReceiptSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ReceiptSettings(
                businessName = p.getString("businessName", null) ?: "My Restaurant",
                addressText = p.getString("addressText", null)
                    ?: "123 Main Street\nCity, ST 12345\nTel: (555) 123-4567",
                email = p.getString("email", null) ?: "",
                logoUrl = p.getString("logoUrl", null) ?: "",
                showServerName = p.getBoolean("showServerName", true),
                showDateTime = p.getBoolean("showDateTime", true),
                showLogo = p.getBoolean("showLogo", true),
                showEmail = p.getBoolean("showEmail", false),
                boldBizName = p.getBoolean("boldBizName",
                    p.getBoolean("boldBusinessInfo",
                        p.getBoolean("boldHeader", true))),
                boldAddress = p.getBoolean("boldAddress",
                    p.getBoolean("boldBusinessInfo",
                        p.getBoolean("boldHeader", true))),
                boldOrderInfo = p.getBoolean("boldOrderInfo",
                    p.getBoolean("boldHeader", true)),
                boldItems = p.getBoolean("boldItems", false),
                boldTotals = p.getBoolean("boldTotals", false),
                boldGrandTotal = p.getBoolean("boldGrandTotal", true),
                boldFooter = p.getBoolean("boldFooter", false),
                fontSizeBizName = p.getInt("fontSizeBizName",
                    p.getInt("fontSizeBusinessInfo",
                        p.getInt("fontSizeHeader", 2))),
                fontSizeAddress = p.getInt("fontSizeAddress",
                    p.getInt("fontSizeBusinessInfo",
                        p.getInt("fontSizeHeader", 2))),
                fontSizeOrderInfo = p.getInt("fontSizeOrderInfo",
                    p.getInt("fontSizeHeader", 2)),
                fontSizeItems = p.getInt("fontSizeItems", 0),
                fontSizeTotals = p.getInt("fontSizeTotals", 0),
                fontSizeGrandTotal = p.getInt("fontSizeGrandTotal", 1),
                fontSizeFooter = p.getInt("fontSizeFooter", 0)
            )
        }

        fun save(context: Context, s: ReceiptSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("businessName", s.businessName)
                putString("addressText", s.addressText)
                putString("email", s.email)
                putString("logoUrl", s.logoUrl)
                putBoolean("showServerName", s.showServerName)
                putBoolean("showDateTime", s.showDateTime)
                putBoolean("showLogo", s.showLogo)
                putBoolean("showEmail", s.showEmail)
                putBoolean("boldBizName", s.boldBizName)
                putBoolean("boldAddress", s.boldAddress)
                putBoolean("boldOrderInfo", s.boldOrderInfo)
                putBoolean("boldItems", s.boldItems)
                putBoolean("boldTotals", s.boldTotals)
                putBoolean("boldGrandTotal", s.boldGrandTotal)
                putBoolean("boldFooter", s.boldFooter)
                putInt("fontSizeBizName", s.fontSizeBizName)
                putInt("fontSizeAddress", s.fontSizeAddress)
                putInt("fontSizeOrderInfo", s.fontSizeOrderInfo)
                putInt("fontSizeItems", s.fontSizeItems)
                putInt("fontSizeTotals", s.fontSizeTotals)
                putInt("fontSizeGrandTotal", s.fontSizeGrandTotal)
                putInt("fontSizeFooter", s.fontSizeFooter)
                apply()
            }
        }

        private var businessInfoListener: ListenerRegistration? = null
        private var receiptSettingsListener: ListenerRegistration? = null
        private var onSettingsChanged: ((ReceiptSettings) -> Unit)? = null

        fun setOnSettingsChangedListener(listener: ((ReceiptSettings) -> Unit)?) {
            onSettingsChanged = listener
        }

        /** Same shape as [maxipay-admin] `formatMerchantAddressForSettings`. */
        fun formatMerchantAddressFromRootField(addressField: Any?): String {
            if (addressField !is Map<*, *>) return ""
            val street = (addressField["street"] as? String)?.trim().orEmpty()
            val city = (addressField["city"] as? String)?.trim().orEmpty()
            val state = (addressField["state"] as? String)?.trim().orEmpty()
            val zip = (addressField["zip"] as? String)?.trim().orEmpty()
            val line2 = listOf(city, state, zip).filter { it.isNotEmpty() }.joinToString(" ")
            return when {
                street.isNotEmpty() && line2.isNotEmpty() -> "$street\n$line2"
                else -> street.ifEmpty { line2 }
            }
        }

        /**
         * Splits the combined receipt "address" box back into Firestore [address] + [phone],
         * matching how [startBusinessInfoSync] builds [ReceiptSettings.addressText].
         */
        fun splitAddressTextForFirestore(addressText: String): Pair<String, String> {
            val trimmed = addressText.trimEnd()
            if (trimmed.isEmpty()) return "" to ""
            val lines = trimmed.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return "" to ""
            val last = lines.last()
            val digits = last.count { it.isDigit() }
            val phoneLike = digits >= 7 ||
                last.contains("Tel:", ignoreCase = true) ||
                last.startsWith("+") ||
                Regex("(?i)^\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}\$").matches(last)
            return if (phoneLike && lines.size > 1) {
                lines.dropLast(1).joinToString("\n") to last
            } else {
                trimmed to ""
            }
        }

        /**
         * When `settings/businessInfo` is missing or empty, copy from [Merchants] root (admin
         * onboarding) so POS and dashboard share one document.
         */
        fun hydrateBusinessInfoFromMerchantRoot(context: Context) {
            if (!MerchantFirestore.isInitialized) return
            val mid = MerchantFirestore.merchantId.trim()
            if (mid.isEmpty()) return
            val db = FirebaseFirestore.getInstance()
            db.collection("Merchants").document(mid).get()
                .addOnSuccessListener { mSnap ->
                    if (!mSnap.exists()) return@addOnSuccessListener
                    val d = mSnap.data ?: return@addOnSuccessListener
                    val businessName = (d["businessName"] as? String)?.trim().orEmpty()
                    val phone = (d["phone"] as? String)?.trim().orEmpty()
                    val email = (d["email"] as? String)?.trim().orEmpty()
                    val address = formatMerchantAddressFromRootField(d["address"])
                    if (businessName.isEmpty() && address.isEmpty() && phone.isEmpty() && email.isEmpty()) {
                        return@addOnSuccessListener
                    }
                    val payload = hashMapOf<String, Any>(
                        "businessName" to businessName,
                        "address" to address,
                        "phone" to phone,
                        "email" to email,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                    MerchantFirestore.doc("Settings", "businessInfo")
                        .set(payload, SetOptions.merge())
                        .addOnSuccessListener {
                            Log.d("ReceiptSettings", "Hydrated businessInfo from Merchants/$mid")
                        }
                        .addOnFailureListener { e ->
                            Log.w("ReceiptSettings", "Hydrate businessInfo write failed", e)
                        }
                }
                .addOnFailureListener { e ->
                    Log.w("ReceiptSettings", "Hydrate Merchants read failed", e)
                }
        }

        fun startBusinessInfoSync(context: Context) {
            stopBusinessInfoSync()
            val db = FirebaseFirestore.getInstance()

            businessInfoListener = MerchantFirestore.doc("Settings", "businessInfo")
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Log.w("ReceiptSettings", "Business info sync error", err)
                        return@addSnapshotListener
                    }
                    if (snap == null) return@addSnapshotListener
                    if (!snap.exists()) {
                        hydrateBusinessInfoFromMerchantRoot(context)
                        return@addSnapshotListener
                    }

                    val bizName = snap.getString("businessName")?.trim().orEmpty()
                    val address = snap.getString("address")?.trim().orEmpty()
                    val phone = snap.getString("phone")?.trim().orEmpty()
                    val email = snap.getString("email")?.trim().orEmpty()
                    val logoUrl = snap.getString("logoUrl")?.trim().orEmpty()

                    if (bizName.isEmpty() && address.isEmpty() && phone.isEmpty() && email.isEmpty()) {
                        hydrateBusinessInfoFromMerchantRoot(context)
                        return@addSnapshotListener
                    }

                    val addressBlock = buildString {
                        if (address.isNotBlank()) append(address)
                        if (phone.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(phone)
                        }
                    }

                    val current = load(context)
                    val updated = current.copy(
                        businessName = bizName.ifEmpty { current.businessName },
                        addressText = addressBlock.ifEmpty { current.addressText },
                        email = email,
                        logoUrl = logoUrl
                    )
                    if (current != updated) {
                        save(context, updated)
                        onSettingsChanged?.invoke(updated)
                        Log.d("ReceiptSettings", "Synced business info: ${updated.businessName}")
                    }
                }

            receiptSettingsListener = MerchantFirestore.doc("Settings", "receiptSettings")
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Log.w("ReceiptSettings", "Receipt settings sync error", err)
                        return@addSnapshotListener
                    }
                    if (snap == null || !snap.exists()) return@addSnapshotListener

                    val current = load(context)
                    val updated = current.copy(
                        showServerName = snap.getBoolean("showServerName") ?: current.showServerName,
                        showDateTime = snap.getBoolean("showDateTime") ?: current.showDateTime,
                        showLogo = snap.getBoolean("showLogo") ?: current.showLogo,
                        showEmail = snap.getBoolean("showEmail") ?: current.showEmail,
                        boldBizName = snap.getBoolean("boldBizName") ?: current.boldBizName,
                        boldAddress = snap.getBoolean("boldAddress") ?: current.boldAddress,
                        boldOrderInfo = snap.getBoolean("boldOrderInfo") ?: current.boldOrderInfo,
                        boldItems = snap.getBoolean("boldItems") ?: current.boldItems,
                        boldTotals = snap.getBoolean("boldTotals") ?: current.boldTotals,
                        boldGrandTotal = snap.getBoolean("boldGrandTotal") ?: current.boldGrandTotal,
                        boldFooter = snap.getBoolean("boldFooter") ?: current.boldFooter,
                        fontSizeBizName = snap.getLong("fontSizeBizName")?.toInt() ?: current.fontSizeBizName,
                        fontSizeAddress = snap.getLong("fontSizeAddress")?.toInt() ?: current.fontSizeAddress,
                        fontSizeOrderInfo = snap.getLong("fontSizeOrderInfo")?.toInt() ?: current.fontSizeOrderInfo,
                        fontSizeItems = snap.getLong("fontSizeItems")?.toInt() ?: current.fontSizeItems,
                        fontSizeTotals = snap.getLong("fontSizeTotals")?.toInt() ?: current.fontSizeTotals,
                        fontSizeGrandTotal = snap.getLong("fontSizeGrandTotal")?.toInt() ?: current.fontSizeGrandTotal,
                        fontSizeFooter = snap.getLong("fontSizeFooter")?.toInt() ?: current.fontSizeFooter
                    )
                    if (current != updated) {
                        save(context, updated)
                        onSettingsChanged?.invoke(updated)
                        Log.d("ReceiptSettings", "Synced receipt settings from Firestore")
                    }
                }
        }

        fun stopBusinessInfoSync() {
            businessInfoListener?.remove()
            businessInfoListener = null
            receiptSettingsListener?.remove()
            receiptSettingsListener = null
            onSettingsChanged = null
        }

        fun saveToFirestore(s: ReceiptSettings) {
            if (!MerchantFirestore.isInitialized) {
                Log.w("ReceiptSettings", "saveToFirestore skipped: MerchantFirestore not initialized")
                return
            }
            val mid = MerchantFirestore.merchantId.trim()
            val db = FirebaseFirestore.getInstance()

            val receiptData = hashMapOf(
                "showServerName" to s.showServerName,
                "showDateTime" to s.showDateTime,
                "showLogo" to s.showLogo,
                "showEmail" to s.showEmail,
                "boldBizName" to s.boldBizName,
                "boldAddress" to s.boldAddress,
                "boldOrderInfo" to s.boldOrderInfo,
                "boldItems" to s.boldItems,
                "boldTotals" to s.boldTotals,
                "boldGrandTotal" to s.boldGrandTotal,
                "boldFooter" to s.boldFooter,
                "fontSizeBizName" to s.fontSizeBizName,
                "fontSizeAddress" to s.fontSizeAddress,
                "fontSizeOrderInfo" to s.fontSizeOrderInfo,
                "fontSizeItems" to s.fontSizeItems,
                "fontSizeTotals" to s.fontSizeTotals,
                "fontSizeGrandTotal" to s.fontSizeGrandTotal,
                "fontSizeFooter" to s.fontSizeFooter
            )
            MerchantFirestore.doc("Settings", "receiptSettings")
                .set(receiptData)
                .addOnSuccessListener { Log.d("ReceiptSettings", "Receipt settings saved to Firestore") }
                .addOnFailureListener { Log.w("ReceiptSettings", "Receipt settings Firestore save failed", it) }

            val (address, phone) = splitAddressTextForFirestore(s.addressText)

            val bizData = hashMapOf<String, Any>(
                "businessName" to s.businessName.trim(),
                "address" to address,
                "phone" to phone,
                "email" to s.email.trim(),
                "logoUrl" to s.logoUrl.trim(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            MerchantFirestore.doc("Settings", "businessInfo")
                .set(bizData, SetOptions.merge())
                .addOnSuccessListener { Log.d("ReceiptSettings", "Business info saved to Firestore") }
                .addOnFailureListener { Log.w("ReceiptSettings", "Business info Firestore save failed", it) }

            val merchantPatch = mutableMapOf<String, Any>(
                "businessName" to s.businessName.trim(),
                "phone" to phone,
            )
            val emailTrim = s.email.trim()
            if (emailTrim.isNotEmpty()) {
                merchantPatch["email"] = emailTrim
            }
            db.collection("Merchants").document(mid).update(merchantPatch)
                .addOnSuccessListener { Log.d("ReceiptSettings", "Merchant profile business fields updated") }
                .addOnFailureListener { e ->
                    Log.w("ReceiptSettings", "Merchants root update failed (admin list may lag)", e)
                }
        }
    }
}

/**
 * Wraps plain text to [maxChars] per line for thermal preview / print (same rules as web dashboard).
 * Respects existing newlines, then word-wraps; breaks long tokens without spaces.
 */
fun wrapThermalText(text: String, maxChars: Int): List<String> {
    if (maxChars < 1) return listOf(text)
    val lines = mutableListOf<String>()
    val paragraphs = text.replace("\r\n", "\n").split("\n")
    for (para in paragraphs) {
        if (para.isEmpty()) {
            lines.add("")
            continue
        }
        var remaining = para
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                lines.add(remaining)
                break
            }
            val chunk = remaining.take(maxChars)
            val lastSpace = chunk.lastIndexOf(' ')
            if (lastSpace > 0) {
                lines.add(chunk.take(lastSpace).trimEnd())
                remaining = remaining.drop(lastSpace + 1).trimStart()
            } else {
                lines.add(chunk)
                remaining = remaining.drop(maxChars)
            }
        }
    }
    return lines
}

/** Longest single line length (each user newline counts as its own line). */
fun maxPhysicalLineLength(text: String): Int =
    text.replace("\r\n", "\n").lines().maxOfOrNull { it.length } ?: 0
