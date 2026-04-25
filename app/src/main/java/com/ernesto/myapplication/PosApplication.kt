package com.ernesto.myapplication

import android.app.Application
import com.ernesto.myapplication.payments.PaymentTerminalReachabilitySync
import com.ernesto.myapplication.payments.PaymentTerminalRepository
import com.google.firebase.auth.FirebaseAuth

class PosApplication : Application() {

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        if (auth.currentUser != null) {
            PrintingSettingsCache.start()
            PrinterKitchenStyleCache.start()
            PrinterDashboardCommandListener.start(this)
            RemoteVoidCommandListener.start(this)
            OnlineTerminalPaymentListener.start(this)
            PrinterFirestoreDeletionSync.start(this)
            PaymentTerminalRepository.start()
            PaymentTerminalReachabilitySync.start(this)
        } else {
            PrintingSettingsCache.stop()
            PrinterKitchenStyleCache.stop()
            PrinterDashboardCommandListener.stop()
            RemoteVoidCommandListener.stop()
            OnlineTerminalPaymentListener.stop()
            PrinterFirestoreDeletionSync.stop()
            PaymentTerminalRepository.stop()
            PaymentTerminalReachabilitySync.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ImmersiveModeHelper.install(this)
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
    }
}
