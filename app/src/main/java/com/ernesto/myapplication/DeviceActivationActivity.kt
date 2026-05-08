package com.ernesto.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class DeviceActivationActivity : AppCompatActivity() {

    private var forceLock: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forceLock = intent.getBooleanExtra(EXTRA_FORCE_LOCK, false)

        setContentView(R.layout.activity_device_activation)

        val instructions = findViewById<TextView>(R.id.txtActivationInstructions)
        if (forceLock) {
            supportActionBar?.show()
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.title = getString(R.string.device_activation_forced_title)
            instructions.text = getString(R.string.device_activation_forced_instructions)
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        // Block back while device is deactivated
                    }
                },
            )
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = getString(R.string.device_activation_title)
            instructions.text = getString(R.string.device_activation_instructions)
        }

        val input = findViewById<EditText>(R.id.editActivationCode)
        val btn = findViewById<Button>(R.id.btnSubmitActivation)

        btn.setOnClickListener {
            val raw = input.text?.toString().orEmpty()
            btn.isEnabled = false
            PosDeviceActivation.redeemCode(
                this,
                raw,
                onSuccess = {
                    runOnUiThread {
                        btn.isEnabled = true
                        Toast.makeText(
                            this,
                            R.string.device_activation_success,
                            Toast.LENGTH_LONG,
                        ).show()
                        if (forceLock) {
                            startActivity(
                                Intent(this, LoginActivity::class.java).apply {
                                    flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                },
                            )
                        }
                        finish()
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        btn.isEnabled = true
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (forceLock) return true
        finish()
        return true
    }

    companion object {
        const val EXTRA_FORCE_LOCK = "extra_force_device_activation"

        fun launchForceLock(context: Context) {
            context.startActivity(
                Intent(context, DeviceActivationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_FORCE_LOCK, true)
                },
            )
        }
    }
}
