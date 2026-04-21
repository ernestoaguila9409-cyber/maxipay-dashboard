package com.ernesto.myapplication

import android.graphics.Color
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

/**
 * Pick kitchen display stations for a menu item ([assignedItemIds] on [KdsStationRouting.COLLECTION]).
 * Same rules as item edit: active/paired devices, baseline from Firestore snapshot, category + “show all” coverage.
 */
object KdsMenuItemStationPicker {

    data class DeviceRow(val id: String, val name: String)

    private fun dp(activity: AppCompatActivity, px: Int): Int =
        (px * activity.resources.displayMetrics.density).toInt()

    /**
     * Shows the multi-select KDS dialog. [onAfterSave] runs after a successful Firestore batch commit
     * (also when there were no changes, after dismiss — optional refresh).
     */
    fun show(
        activity: AppCompatActivity,
        itemId: String,
        placementCategoryIds: Set<String>,
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        onAfterSave: () -> Unit = {},
    ) {
        val id = itemId.trim()
        if (id.isEmpty()) return

        db.collection(KdsStationRouting.COLLECTION).get()
            .addOnSuccessListener { snap ->
                val all = snap.documents.mapNotNull { doc ->
                    if (!KdsStationRouting.isDeviceSelectable(doc)) return@mapNotNull null
                    val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
                    DeviceRow(doc.id, name)
                }.sortedBy { it.name.lowercase(Locale.getDefault()) }

                if (all.isEmpty()) {
                    val msg = if (snap.documents.isEmpty()) {
                        R.string.item_detail_no_kds_devices
                    } else {
                        R.string.item_detail_no_kds_active_devices
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val assignedIds = snap.documents
                    .filter {
                        KdsStationRouting.isDeviceSelectable(it) &&
                            KdsStationRouting.deviceCoversMenuItem(it, id, placementCategoryIds)
                    }
                    .map { it.id }
                    .toSet()

                val content = LayoutInflater.from(activity).inflate(R.layout.dialog_kds_station_picker, null, false)
                content.findViewById<TextView>(R.id.txtKdsPickerHint).text =
                    activity.getString(R.string.item_detail_kds_hint)
                val container = content.findViewById<LinearLayout>(R.id.containerKdsPickerChecks)

                val boxes = all.map { dev ->
                    CheckBox(activity).apply {
                        text = dev.name
                        isChecked = assignedIds.contains(dev.id)
                        textSize = 16f
                        setTextColor(Color.parseColor("#1E293B"))
                        setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                    }.also { container.addView(it) }
                }

                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.item_detail_add_kds_title)
                    .setView(content)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val want = all.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.id }.toSet()
                        commitItemAssignments(activity, db, id, all, want, assignedIds, onAfterSave)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    activity,
                    e.message ?: activity.getString(R.string.item_detail_load_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    /**
     * Same multi-select dialog as [show], but for an item that doesn't exist in Firestore yet.
     * No writes — the caller receives the selected device IDs in [onSelected] to commit later with
     * [applyNewItemAssignments] once the MenuItem doc has been created.
     *
     * When [currentSelection] is null, baseline = devices that already route [categoryId]
     * (category-level or "all items" coverage). When non-null, uses it as the baseline so the picker
     * preserves the user's previous in-dialog choice across re-opens.
     * [onCancelled] runs when the user dismisses without saving (to let callers re-show their form).
     */
    fun pickForNewItem(
        activity: AppCompatActivity,
        categoryId: String,
        currentSelection: Set<String>?,
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        onSelected: (Set<String>) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        db.collection(KdsStationRouting.COLLECTION).get()
            .addOnSuccessListener { snap ->
                val all = snap.documents.mapNotNull { doc ->
                    if (!KdsStationRouting.isDeviceSelectable(doc)) return@mapNotNull null
                    val name = doc.getString("name")?.trim().orEmpty().ifBlank { doc.id }
                    DeviceRow(doc.id, name)
                }.sortedBy { it.name.lowercase(Locale.getDefault()) }

                if (all.isEmpty()) {
                    val msg = if (snap.documents.isEmpty()) {
                        R.string.item_detail_no_kds_devices
                    } else {
                        R.string.item_detail_no_kds_active_devices
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    onCancelled()
                    return@addOnSuccessListener
                }

                val baseline: Set<String> = currentSelection
                    ?: snap.documents
                        .filter {
                            KdsStationRouting.isDeviceSelectable(it) &&
                                KdsStationRouting.deviceCoversCategory(it, categoryId)
                        }
                        .map { it.id }
                        .toSet()

                val content = LayoutInflater.from(activity).inflate(R.layout.dialog_kds_station_picker, null, false)
                content.findViewById<TextView>(R.id.txtKdsPickerHint).text =
                    activity.getString(R.string.item_detail_kds_hint)
                val container = content.findViewById<LinearLayout>(R.id.containerKdsPickerChecks)

                val boxes = all.map { dev ->
                    CheckBox(activity).apply {
                        text = dev.name
                        isChecked = baseline.contains(dev.id)
                        textSize = 16f
                        setTextColor(Color.parseColor("#1E293B"))
                        setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                    }.also { container.addView(it) }
                }

                var saved = false
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.item_detail_add_kds_title)
                    .setView(content)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> onCancelled() }
                    .setPositiveButton(R.string.save, null)
                    .setOnCancelListener { onCancelled() }
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val want = all.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.id }.toSet()
                        saved = true
                        onSelected(want)
                        dialog.dismiss()
                    }
                }
                dialog.setOnDismissListener {
                    if (!saved) onCancelled()
                }
                dialog.show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    activity,
                    e.message ?: activity.getString(R.string.item_detail_load_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                onCancelled()
            }
    }

    /**
     * Writes the selection chosen via [pickForNewItem] to Firestore after the item has been created.
     * [deviceIds] is the full set of stations the user explicitly checked; each gets [itemId] added to
     * [KdsStationRouting.COLLECTION]/{device}.assignedItemIds.
     */
    fun applyNewItemAssignments(
        db: FirebaseFirestore,
        itemId: String,
        deviceIds: Set<String>,
        onComplete: (success: Boolean, error: String?) -> Unit = { _, _ -> },
    ) {
        val id = itemId.trim()
        val devices = deviceIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (id.isEmpty() || devices.isEmpty()) {
            onComplete(true, null)
            return
        }
        val batch = db.batch()
        for (deviceId in devices) {
            val ref = db.collection(KdsStationRouting.COLLECTION).document(deviceId)
            batch.update(ref, "assignedItemIds", FieldValue.arrayUnion(id))
        }
        batch.commit()
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener { e -> onComplete(false, e.message) }
    }

    private fun commitItemAssignments(
        activity: AppCompatActivity,
        db: FirebaseFirestore,
        itemId: String,
        allDevices: List<DeviceRow>,
        wantIds: Set<String>,
        baselineHadIds: Set<String>,
        onAfterSave: () -> Unit,
    ) {
        val batch = db.batch()
        var ops = 0
        for (dev in allDevices) {
            val want = wantIds.contains(dev.id)
            val had = baselineHadIds.contains(dev.id)
            if (want == had) continue
            val ref = db.collection(KdsStationRouting.COLLECTION).document(dev.id)
            if (want) {
                batch.update(ref, "assignedItemIds", FieldValue.arrayUnion(itemId))
            } else {
                batch.update(ref, "assignedItemIds", FieldValue.arrayRemove(itemId))
            }
            ops++
        }
        if (ops == 0) {
            Toast.makeText(activity, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
            onAfterSave()
            return
        }
        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(activity, R.string.item_detail_saved, Toast.LENGTH_SHORT).show()
                onAfterSave()
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
