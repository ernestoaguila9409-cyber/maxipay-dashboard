package com.volt.maximobile

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.app.Activity
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.volt.shared.MerchantFirestore
import com.volt.shared.data.OrderModifier
import java.util.Locale

/**
 * Shared modifier picker for Maxi order flows ([MainActivity]) and [MenuActivity].
 */
class MenuModifierPicker(private val activity: Activity) {

    private var suppressModifierCallbacks = false

    fun pickForMenuItem(
        itemId: String,
        name: String,
        basePrice: Double,
        taxMode: String = "INHERIT",
        taxIds: List<String> = emptyList(),
        onResult: (ModifierPickResult) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        MerchantFirestore.col("MenuItems").document(itemId).get()
            .addOnSuccessListener { itemDoc ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                continueCheckAndShowModifiers(
                    itemDoc = itemDoc,
                    itemId = itemId,
                    name = name,
                    effectiveBasePrice = basePrice,
                    taxMode = taxMode,
                    taxIds = taxIds,
                    onResult = onResult,
                    onCancelled = onCancelled,
                )
            }
            .addOnFailureListener { e ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnFailureListener
                Toast.makeText(
                    activity,
                    e.message ?: activity.getString(R.string.menu_item_load_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                onCancelled()
            }
    }

    private fun continueCheckAndShowModifiers(
        itemDoc: com.google.firebase.firestore.DocumentSnapshot,
        itemId: String,
        name: String,
        effectiveBasePrice: Double,
        taxMode: String,
        taxIds: List<String>,
        onResult: (ModifierPickResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val merged = MerchantFirestore.mergeMenuItemModifierGroupIds(itemDoc)
        fun deliverWithoutModifiers() {
            if (activity.isFinishing || activity.isDestroyed) return
            onResult(
                ModifierPickResult(
                    itemId = itemId,
                    name = name,
                    basePriceDollars = effectiveBasePrice,
                    modifiers = emptyList(),
                    taxMode = taxMode,
                    taxIds = taxIds,
                ),
            )
        }

        if (merged.isNotEmpty()) {
            showModifierDialog(itemId, name, effectiveBasePrice, merged, taxMode, taxIds, onResult, onCancelled)
            return
        }

        MerchantFirestore.col("ItemModifierGroups")
            .whereEqualTo("itemId", itemId)
            .orderBy("displayOrder")
            .get()
            .addOnSuccessListener { documents ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                val groupIds = documents.mapNotNull { it.getString("groupId") }.filter { it.isNotBlank() }
                if (groupIds.isEmpty()) {
                    deliverWithoutModifiers()
                } else {
                    showModifierDialog(itemId, name, effectiveBasePrice, groupIds, taxMode, taxIds, onResult, onCancelled)
                }
            }
            .addOnFailureListener {
                android.util.Log.e("MenuModifierPicker", "ItemModifierGroups lookup failed", it)
                deliverWithoutModifiers()
            }
    }

    private fun showModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        groupIds: List<String>,
        taxMode: String,
        taxIds: List<String>,
        onResult: (ModifierPickResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        if (groupIds.isEmpty()) {
            onResult(ModifierPickResult(itemId, name, basePrice, emptyList(), taxMode, taxIds))
            return
        }

        val orderIndex = groupIds.withIndex().associate { it.value to it.index }
        val allGroupInfos = mutableMapOf<String, GroupInfo>()
        val triggeredGroupIds = mutableSetOf<String>()
        var pending = groupIds.size

        fun parseOptions(raw: List<Map<String, Any>>?, groupType: String): List<ModifierOptionEntry> =
            raw?.mapNotNull { map ->
                val oN = map["name"]?.toString() ?: return@mapNotNull null
                val oP = (map["price"] as? Number)?.toDouble() ?: 0.0
                val oId = map["id"]?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val triggers = (map["triggersModifierGroupIds"] as? List<String>) ?: emptyList()
                val act = normalizedModifierOptionAction(map["action"] as? String, groupType)
                ModifierOptionEntry(oId, oN, oP, triggers, act)
            } ?: emptyList()

        fun fetchTriggeredGroups(callback: () -> Unit) {
            val additional = triggeredGroupIds.filter { it !in allGroupInfos }.toSet()
            if (additional.isEmpty()) {
                callback()
                return
            }
            var p = additional.size
            for (id in additional) {
                MerchantFirestore.col("ModifierGroups").document(id).get()
                    .addOnSuccessListener { doc ->
                        val gName = doc.getString("name") ?: ""
                        val isReq = doc.getBoolean("required") ?: false
                        val maxSel = doc.getLong("maxSelection")?.toInt() ?: 1
                        val gType = doc.getString("groupType") ?: "ADD"
                        @Suppress("UNCHECKED_CAST")
                        val opts = parseOptions(doc.get("options") as? List<Map<String, Any>>, gType)
                        if (gName.isNotEmpty()) {
                            allGroupInfos[id] = GroupInfo(id, gName, isReq, maxSel, gType, opts)
                            opts.forEach { triggeredGroupIds.addAll(it.triggersModifierGroupIds) }
                        }
                        p--
                        if (p == 0) fetchTriggeredGroups(callback)
                    }
                    .addOnFailureListener {
                        p--
                        if (p == 0) fetchTriggeredGroups(callback)
                    }
            }
        }

        fun mergeLegacyOptions(callback: () -> Unit) {
            val ids = allGroupInfos.keys.toList()
            if (ids.isEmpty()) {
                callback()
                return
            }
            val chunks = ids.chunked(30)
            var remaining = chunks.size
            for (chunk in chunks) {
                MerchantFirestore.col("ModifierOptions")
                    .whereIn("groupId", chunk)
                    .get()
                    .addOnSuccessListener { snap ->
                        for (doc in snap) {
                            val gId = doc.getString("groupId") ?: continue
                            val info = allGroupInfos[gId] ?: continue
                            if (info.options.any { it.id == doc.id }) continue
                            val oN = doc.getString("name") ?: continue
                            val oP = doc.getDouble("price") ?: 0.0
                            @Suppress("UNCHECKED_CAST")
                            val tr = (doc.get("triggersModifierGroupIds") as? List<String>) ?: emptyList()
                            val act = normalizedModifierOptionAction(doc.getString("action"), info.groupType)
                            val entry = ModifierOptionEntry(doc.id, oN, oP, tr, act)
                            allGroupInfos[gId] = info.copy(options = info.options + entry)
                            tr.forEach { triggeredGroupIds.add(it) }
                        }
                        remaining--
                        if (remaining == 0) callback()
                    }
                    .addOnFailureListener {
                        remaining--
                        if (remaining == 0) callback()
                    }
            }
        }

        fun onAllGroupsReady() {
            fetchTriggeredGroups {
                mergeLegacyOptions {
                    buildNestedModifierDialog(
                        itemId, name, basePrice, allGroupInfos, orderIndex, triggeredGroupIds,
                        taxMode, taxIds, onResult, onCancelled,
                    )
                }
            }
        }

        for (groupId in groupIds) {
            MerchantFirestore.col("ModifierGroups").document(groupId).get()
                .addOnSuccessListener { groupDoc ->
                    val gName = groupDoc.getString("name") ?: ""
                    val isReq = groupDoc.getBoolean("required") ?: false
                    val maxSel = groupDoc.getLong("maxSelection")?.toInt() ?: 1
                    val gType = groupDoc.getString("groupType") ?: "ADD"
                    @Suppress("UNCHECKED_CAST")
                    val opts = parseOptions(groupDoc.get("options") as? List<Map<String, Any>>, gType)
                    if (gName.isNotEmpty()) {
                        allGroupInfos[groupId] = GroupInfo(groupId, gName, isReq, maxSel, gType, opts)
                        opts.forEach { triggeredGroupIds.addAll(it.triggersModifierGroupIds) }
                    }
                    pending--
                    if (pending == 0) onAllGroupsReady()
                }
                .addOnFailureListener {
                    pending--
                    if (pending == 0) onAllGroupsReady()
                }
        }
    }

    private fun buildNestedModifierDialog(
        itemId: String,
        name: String,
        basePrice: Double,
        allGroupInfos: Map<String, GroupInfo>,
        orderIndex: Map<String, Int>,
        triggeredGroupIds: Set<String>,
        taxMode: String,
        taxIds: List<String>,
        onResult: (ModifierPickResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val selectedOptionsPerGroup = mutableMapOf<String, MutableList<SelectedOption>>()
        val groupContainers = mutableMapOf<String, LinearLayout>()
        val visibleGroupIds = mutableSetOf<String>()
        val radioGroupPreviousTriggers = mutableMapOf<String, List<String>>()
        val optionIdByRadioId = mutableMapOf<Int, String>()

        val topLevelGroupIds = orderIndex.keys.filter { it !in triggeredGroupIds }
        val topLevelGroups = topLevelGroupIds
            .mapNotNull { allGroupInfos[it] }
            .sortedWith(
                compareBy<GroupInfo> { !it.isRequired }
                    .thenBy { orderIndex[it.groupId] ?: 0 },
            )
        topLevelGroups.forEach { visibleGroupIds.add(it.groupId) }

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        fun hideTriggeredGroups(triggerIds: List<String>) {
            for (tid in triggerIds) {
                if (tid !in visibleGroupIds) continue
                val sels = selectedOptionsPerGroup[tid]?.toList() ?: emptyList()
                for (sel in sels) {
                    if (sel.triggersModifierGroupIds.isNotEmpty()) {
                        hideTriggeredGroups(sel.triggersModifierGroupIds)
                    }
                }
                visibleGroupIds.remove(tid)
                selectedOptionsPerGroup.remove(tid)
                radioGroupPreviousTriggers.remove(tid)
                suppressModifierCallbacks = true
                resetGroupSelectionUI(groupContainers[tid])
                suppressModifierCallbacks = false
                groupContainers[tid]?.visibility = View.GONE
            }
        }

        fun showTriggeredGroups(triggerIds: List<String>) {
            for (tid in triggerIds) {
                if (tid in visibleGroupIds) continue
                visibleGroupIds.add(tid)
                groupContainers[tid]?.visibility = View.VISIBLE
            }
        }

        fun createGroupSection(group: GroupInfo, isTriggered: Boolean): LinearLayout {
            val isRemoveGroup = group.groupType == "REMOVE"
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    if (isTriggered) 60 else 0,
                    if (isTriggered) 16 else 40,
                    0,
                    if (isTriggered) 16 else 40,
                )
                if (isTriggered) visibility = View.GONE
            }

            val title = TextView(activity).apply {
                text = when {
                    isTriggered -> "\u2192 ${group.groupName}"
                    isRemoveGroup -> "REMOVE INGREDIENTS"
                    else -> group.groupName
                }
                textSize = if (isTriggered) 16f else 18f
                setTypeface(null, Typeface.BOLD)
                when {
                    isRemoveGroup -> setTextColor(Color.parseColor("#D32F2F"))
                    isTriggered -> setTextColor(Color.parseColor("#6366F1"))
                }
            }

            val subtitle = TextView(activity)
            if (isRemoveGroup) {
                subtitle.text = "Tap to remove ingredients"
                subtitle.setTextColor(Color.GRAY)
            } else {
                val subtitleFull =
                    if (group.isRequired) "Required \u2022 Select up to ${group.maxSelection}"
                    else "Optional \u2022 Select up to ${group.maxSelection}"
                val spannable = SpannableString(subtitleFull)
                if (group.isRequired) {
                    spannable.setSpan(StyleSpan(Typeface.BOLD), 0, 8, 0)
                    spannable.setSpan(RelativeSizeSpan(1.25f), 0, 8, 0)
                    spannable.setSpan(ForegroundColorSpan(Color.parseColor("#1A1A1A")), 0, 8, 0)
                    spannable.setSpan(ForegroundColorSpan(Color.GRAY), 8, subtitleFull.length, 0)
                } else {
                    spannable.setSpan(ForegroundColorSpan(Color.GRAY), 0, subtitleFull.length, 0)
                }
                subtitle.text = spannable
            }
            subtitle.setPadding(0, 8, 0, 16)

            val divider = View(activity).apply {
                setBackgroundColor(if (isRemoveGroup) Color.parseColor("#D32F2F") else Color.LTGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2,
                )
            }

            container.addView(title)
            container.addView(subtitle)
            container.addView(divider)

            renderNestedOptions(
                group.options,
                group,
                container,
                selectedOptionsPerGroup,
                radioGroupPreviousTriggers,
                { showTriggeredGroups(it) },
                { hideTriggeredGroups(it) },
                optionIdByRadioId,
            )

            return container
        }

        for (group in topLevelGroups) {
            val container = createGroupSection(group, false)
            groupContainers[group.groupId] = container
            mainLayout.addView(container)

            for (opt in group.options) {
                for (tid in opt.triggersModifierGroupIds) {
                    if (tid !in groupContainers) {
                        val tGroup = allGroupInfos[tid] ?: continue
                        val tContainer = createGroupSection(tGroup, true)
                        groupContainers[tid] = tContainer
                        mainLayout.addView(tContainer)
                    }
                }
            }
        }

        for ((gId, gInfo) in allGroupInfos) {
            if (gId !in groupContainers) {
                val container = createGroupSection(gInfo, true)
                groupContainers[gId] = container
                mainLayout.addView(container)
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_MyApplication_MaterialAlertDialog)
            .setTitle("Select Options")
            .setView(ScrollView(activity).apply { addView(mainLayout) })
            .setPositiveButton("Add to Cart", null)
            .setNegativeButton("Cancel", null)
            .create()

        var confirmedOnce = false
        dialog.setOnShowListener {
            val addButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            addButton.setOnClickListener {
                for ((gId, gInfo) in allGroupInfos) {
                    if (gId !in visibleGroupIds) continue
                    if (!gInfo.isRequired) continue
                    val count = selectedOptionsPerGroup[gId]?.size ?: 0
                    if (count == 0) {
                        Toast.makeText(
                            activity,
                            "Please select required option for ${gInfo.groupName}.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }
                }

                val modifiers = buildOrderModifiers(
                    topLevelGroupIds,
                    allGroupInfos,
                    selectedOptionsPerGroup,
                    visibleGroupIds,
                )
                onResult(
                    ModifierPickResult(
                        itemId = itemId,
                        name = name,
                        basePriceDollars = basePrice,
                        modifiers = modifiers,
                        taxMode = taxMode,
                        taxIds = taxIds,
                    ),
                )
                confirmedOnce = true
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            if (!confirmedOnce) onCancelled()
        }

        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            dialog.show()
        }
    }

    private fun renderNestedOptions(
        options: List<ModifierOptionEntry>,
        group: GroupInfo,
        groupContainer: LinearLayout,
        selectedOptionsPerGroup: MutableMap<String, MutableList<SelectedOption>>,
        radioGroupPreviousTriggers: MutableMap<String, List<String>>,
        onTriggersActivated: (List<String>) -> Unit,
        onTriggersDeactivated: (List<String>) -> Unit,
        optionIdByRadioId: MutableMap<Int, String>,
    ) {
        val groupId = group.groupId
        val isRemoveGroup = group.groupType == "REMOVE"
        val maxSelection = group.maxSelection

        if (isRemoveGroup) {
            for (opt in options) {
                val checkBox = CheckBox(activity).apply {
                    text = opt.name
                    setTextColor(Color.parseColor("#D32F2F"))
                }
                groupContainer.addView(checkBox)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (suppressModifierCallbacks) return@setOnCheckedChangeListener
                    val sels = selectedOptionsPerGroup.getOrPut(groupId) { mutableListOf() }
                    if (isChecked) {
                        sels.add(SelectedOption(opt.id, opt.name, 0.0, "REMOVE", opt.triggersModifierGroupIds))
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersActivated(opt.triggersModifierGroupIds)
                    } else {
                        sels.removeAll { it.optionId == opt.id }
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersDeactivated(opt.triggersModifierGroupIds)
                    }
                }
            }
        } else if (maxSelection == 1) {
            val radioGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
            groupContainer.addView(radioGroup)
            val optionById = options.associateBy { it.id }

            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                if (suppressModifierCallbacks) return@setOnCheckedChangeListener
                if (checkedId == -1) return@setOnCheckedChangeListener
                val optId = optionIdByRadioId[checkedId] ?: return@setOnCheckedChangeListener
                val opt = optionById[optId] ?: return@setOnCheckedChangeListener

                val prev = radioGroupPreviousTriggers[groupId] ?: emptyList()
                if (prev.isNotEmpty()) onTriggersDeactivated(prev)

                val lineAction = effectiveOptionAction(opt, group)
                val isLineRemove = lineAction == "REMOVE"
                val sels = selectedOptionsPerGroup.getOrPut(groupId) { mutableListOf() }
                sels.clear()
                val priceLine = if (isLineRemove) 0.0 else opt.price
                sels.add(SelectedOption(opt.id, opt.name, priceLine, lineAction, opt.triggersModifierGroupIds))
                radioGroupPreviousTriggers[groupId] = opt.triggersModifierGroupIds
                if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersActivated(opt.triggersModifierGroupIds)
            }

            for (opt in options) {
                val lineAction = effectiveOptionAction(opt, group)
                val isLineRemove = lineAction == "REMOVE"
                val radioButton = RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = if (isLineRemove) {
                        opt.name
                    } else {
                        "${opt.name} +$${String.format(Locale.US, "%.2f", opt.price)}"
                    }
                    if (isLineRemove) setTextColor(Color.parseColor("#D32F2F"))
                }
                optionIdByRadioId[radioButton.id] = opt.id
                radioGroup.addView(radioButton)
            }
        } else {
            for (opt in options) {
                val lineAction = effectiveOptionAction(opt, group)
                val isLineRemove = lineAction == "REMOVE"
                val checkBox = CheckBox(activity).apply {
                    text = if (isLineRemove) {
                        opt.name
                    } else {
                        "${opt.name} +$${String.format(Locale.US, "%.2f", opt.price)}"
                    }
                    if (isLineRemove) setTextColor(Color.parseColor("#D32F2F"))
                }
                groupContainer.addView(checkBox)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (suppressModifierCallbacks) return@setOnCheckedChangeListener
                    val sels = selectedOptionsPerGroup.getOrPut(groupId) { mutableListOf() }
                    if (isChecked) {
                        if (sels.size >= maxSelection) {
                            checkBox.isChecked = false
                            Toast.makeText(
                                activity,
                                "Maximum $maxSelection selections allowed",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@setOnCheckedChangeListener
                        }
                        val priceLine = if (isLineRemove) 0.0 else opt.price
                        sels.add(SelectedOption(opt.id, opt.name, priceLine, lineAction, opt.triggersModifierGroupIds))
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersActivated(opt.triggersModifierGroupIds)
                    } else {
                        sels.removeAll { it.optionId == opt.id }
                        if (opt.triggersModifierGroupIds.isNotEmpty()) onTriggersDeactivated(opt.triggersModifierGroupIds)
                    }
                }
            }
        }
    }

    private fun buildOrderModifiers(
        topLevelGroupIds: List<String>,
        allGroupInfos: Map<String, GroupInfo>,
        selectedOptionsPerGroup: Map<String, List<SelectedOption>>,
        visibleGroupIds: Set<String>,
    ): List<OrderModifier> {
        val result = mutableListOf<OrderModifier>()
        for (gId in topLevelGroupIds) {
            val group = allGroupInfos[gId] ?: continue
            val selections = selectedOptionsPerGroup[gId] ?: continue
            for (sel in selections) {
                val children = buildChildModifiers(
                    sel.triggersModifierGroupIds,
                    allGroupInfos,
                    selectedOptionsPerGroup,
                    visibleGroupIds,
                )
                result.add(
                    OrderModifier(
                        name = sel.optionName,
                        action = sel.action,
                        price = sel.price,
                        groupId = gId,
                        groupName = group.groupName,
                        children = children,
                    ),
                )
            }
        }
        return result
    }

    private fun buildChildModifiers(
        triggerIds: List<String>,
        allGroupInfos: Map<String, GroupInfo>,
        selectedOptionsPerGroup: Map<String, List<SelectedOption>>,
        visibleGroupIds: Set<String>,
    ): List<OrderModifier> {
        val children = mutableListOf<OrderModifier>()
        for (tid in triggerIds) {
            if (tid !in visibleGroupIds) continue
            val tGroup = allGroupInfos[tid] ?: continue
            val tSels = selectedOptionsPerGroup[tid] ?: continue
            for (tSel in tSels) {
                val grandchildren = buildChildModifiers(
                    tSel.triggersModifierGroupIds,
                    allGroupInfos,
                    selectedOptionsPerGroup,
                    visibleGroupIds,
                )
                children.add(
                    OrderModifier(
                        name = tSel.optionName,
                        action = tSel.action,
                        price = tSel.price,
                        groupId = tid,
                        groupName = tGroup.groupName,
                        children = grandchildren,
                    ),
                )
            }
        }
        return children
    }

    private fun resetGroupSelectionUI(container: LinearLayout?) {
        container ?: return
        for (i in 0 until container.childCount) {
            when (val child = container.getChildAt(i)) {
                is CheckBox -> child.isChecked = false
                is RadioButton -> child.isChecked = false
                is RadioGroup -> child.clearCheck()
                is LinearLayout -> resetGroupSelectionUI(child)
            }
        }
    }

    private fun normalizedModifierOptionAction(raw: String?, groupType: String): String {
        if (groupType.trim().uppercase(Locale.US) == "REMOVE") return "REMOVE"
        return if (raw?.trim()?.uppercase(Locale.US) == "REMOVE") "REMOVE" else "ADD"
    }

    private fun effectiveOptionAction(opt: ModifierOptionEntry, group: GroupInfo): String {
        if (group.groupType.trim().uppercase(Locale.US) == "REMOVE") return "REMOVE"
        return if (opt.action.trim().uppercase(Locale.US) == "REMOVE") "REMOVE" else "ADD"
    }

    private data class SelectedOption(
        val optionId: String,
        val optionName: String,
        val price: Double,
        val action: String,
        val triggersModifierGroupIds: List<String> = emptyList(),
    )

    private data class GroupInfo(
        val groupId: String,
        val groupName: String,
        val isRequired: Boolean,
        val maxSelection: Int,
        val groupType: String = "ADD",
        val options: List<ModifierOptionEntry> = emptyList(),
    )
}
