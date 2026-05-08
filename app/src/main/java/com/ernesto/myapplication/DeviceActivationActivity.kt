package com.ernesto.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DeviceActivationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_activation)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.device_activation_title)

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
        finish()
        return true
    }
}
