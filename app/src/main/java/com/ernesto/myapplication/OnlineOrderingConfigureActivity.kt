package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class OnlineOrderingConfigureActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val onlineOrderingRef = db.collection("Settings").document("onlineOrdering")

    private lateinit var switchConfirmOrder: SwitchCompat
    private var suppressConfirmSwitch = false
    private var confirmOrderListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online_ordering_configure)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Online Ordering"

        switchConfirmOrder = findViewById(R.id.switchConfirmOrder)
        switchConfirmOrder.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfirmSwitch) return@setOnCheckedChangeListener
            onlineOrderingRef.set(
                hashMapOf("requireStaffConfirmOrder" to isChecked),
                SetOptions.merge(),
            )
        }

        findViewById<android.view.View>(R.id.optionBusinessHours).setOnClickListener {
            startActivity(Intent(this, OnlineOrderingBusinessHoursActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        confirmOrderListener = onlineOrderingRef.addSnapshotListener { snap, e ->
            if (e != null) return@addSnapshotListener
            val on = snap?.getBoolean("requireStaffConfirmOrder") == true
            suppressConfirmSwitch = true
            switchConfirmOrder.isChecked = on
            suppressConfirmSwitch = false
        }
    }

    override fun onStop() {
        confirmOrderListener?.remove()
        confirmOrderListener = null
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
