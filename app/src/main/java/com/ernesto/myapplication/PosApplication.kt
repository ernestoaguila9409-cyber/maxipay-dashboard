package com.ernesto.myapplication

import android.app.Application
import com.google.firebase.auth.FirebaseAuth

class PosApplication : Application() {

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        if (auth.currentUser != null) {
            PrintingSettingsCache.start()
            PrinterKitchenStyleCache.start()
            PrinterDashboardCommandListener.start(this)
            PrinterFirestoreDeletionSync.start(this)
        } else {
            PrintingSettingsCache.stop()
            PrinterKitchenStyleCache.stop()
            PrinterDashboardCommandListener.stop()
            PrinterFirestoreDeletionSync.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ImmersiveModeHelper.install(this)
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
    }
}
