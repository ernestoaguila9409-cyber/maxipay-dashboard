package com.ernesto.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Hub: Configure Tips or Configure Discounts (replaces separate Configuration entries). */
class TipsAndDiscountsHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tips_discounts_hub)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tips_discounts_hub_title)

        findViewById<android.view.View>(R.id.optionConfigureTips).setOnClickListener {
            startActivity(Intent(this, TipConfigActivity::class.java))
        }
        findViewById<android.view.View>(R.id.optionConfigureDiscounts).setOnClickListener {
            startActivity(Intent(this, DiscountsActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
