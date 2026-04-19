package com.ernesto.myapplication

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class KitchenOrderConfigurationActivity : AppCompatActivity() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val printingRef by lazy { PrintingSettingsFirestore.documentRef(db) }

    private var printingListener: ListenerRegistration? = null
    private var applyingRemotePrintingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kitchen_order_configuration)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.kitchen_kds_config_title)

        findViewById<RadioGroup>(R.id.printTriggerRadioGroup).setOnCheckedChangeListener { _, checkedId ->
            if (applyingRemotePrintingSettings) return@setOnCheckedChangeListener
            if (checkedId == -1) return@setOnCheckedChangeListener
            val value = when (checkedId) {
                R.id.radioPrintTriggerOnSend -> PrintingSettingsFirestore.ON_SEND
                R.id.radioPrintTriggerOnPayment -> PrintingSettingsFirestore.ON_PAYMENT
                R.id.radioPrintTriggerFirstEvent -> PrintingSettingsFirestore.FIRST_EVENT
                else -> return@setOnCheckedChangeListener
            }
            printingRef.set(
                hashMapOf(PrintingSettingsFirestore.FIELD_PRINT_TRIGGER_MODE to value),
                SetOptions.merge(),
            )
        }

        findViewById<RadioGroup>(R.id.printItemFilterRadioGroup).setOnCheckedChangeListener { _, checkedId ->
            if (applyingRemotePrintingSettings) return@setOnCheckedChangeListener
            if (checkedId == -1) return@setOnCheckedChangeListener
            val value = when (checkedId) {
                R.id.radioPrintItemFilterAllItems -> PrintingSettingsFirestore.ALL_ITEMS
                R.id.radioPrintItemFilterByLabel -> PrintingSettingsFirestore.BY_LABEL
                else -> return@setOnCheckedChangeListener
            }
            printingRef.set(
                hashMapOf(PrintingSettingsFirestore.FIELD_PRINT_ITEM_FILTER_MODE to value),
                SetOptions.merge(),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        printingListener = printingRef.addSnapshotListener { snap, error ->
            if (error != null) return@addSnapshotListener
            if (snap == null) return@addSnapshotListener

            if (!snap.exists()) {
                printingRef.set(PrintingSettingsFirestore.defaultPrintingDocument(), SetOptions.merge())
                return@addSnapshotListener
            }

            val rawTrigger = snap.getString(PrintingSettingsFirestore.FIELD_PRINT_TRIGGER_MODE)
            if (!PrintingSettingsFirestore.isPrintTriggerModeValid(rawTrigger)) {
                printingRef.set(
                    hashMapOf(PrintingSettingsFirestore.FIELD_PRINT_TRIGGER_MODE to PrintingSettingsFirestore.FIRST_EVENT),
                    SetOptions.merge(),
                )
                return@addSnapshotListener
            }

            val rawFilter = snap.getString(PrintingSettingsFirestore.FIELD_PRINT_ITEM_FILTER_MODE)
            if (!PrintingSettingsFirestore.isPrintItemFilterModeValid(rawFilter)) {
                printingRef.set(
                    hashMapOf(PrintingSettingsFirestore.FIELD_PRINT_ITEM_FILTER_MODE to PrintingSettingsFirestore.BY_LABEL),
                    SetOptions.merge(),
                )
                return@addSnapshotListener
            }

            val triggerMode = PrintingSettingsFirestore.printTriggerModeFromSnapshot(snap)
            val filterMode = PrintingSettingsFirestore.printItemFilterModeFromSnapshot(snap)

            applyingRemotePrintingSettings = true
            val triggerGroup = findViewById<RadioGroup>(R.id.printTriggerRadioGroup)
            when (triggerMode) {
                PrintingSettingsFirestore.ON_SEND -> triggerGroup.check(R.id.radioPrintTriggerOnSend)
                PrintingSettingsFirestore.ON_PAYMENT -> triggerGroup.check(R.id.radioPrintTriggerOnPayment)
                else -> triggerGroup.check(R.id.radioPrintTriggerFirstEvent)
            }
            val filterGroup = findViewById<RadioGroup>(R.id.printItemFilterRadioGroup)
            when (filterMode) {
                PrintingSettingsFirestore.ALL_ITEMS -> filterGroup.check(R.id.radioPrintItemFilterAllItems)
                else -> filterGroup.check(R.id.radioPrintItemFilterByLabel)
            }
            applyingRemotePrintingSettings = false
        }
    }

    override fun onStop() {
        printingListener?.remove()
        printingListener = null
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
