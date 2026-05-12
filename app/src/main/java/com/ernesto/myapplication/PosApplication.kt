package com.ernesto.myapplication

import android.app.Application
import android.util.Log
import com.ernesto.myapplication.payments.PaymentTerminalReachabilitySync
import com.ernesto.myapplication.payments.PaymentTerminalRepository
import com.google.firebase.auth.FirebaseAuth

class PosApplication : Application() {

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        if (auth.currentUser != null) {
            ensureMerchantInitialized()
            if (MerchantFirestore.isInitialized) {
                doStartListeners()
            }
        } else {
            stopListeners()
        }
    }

    private fun ensureMerchantInitialized() {
        if (MerchantFirestore.isInitialized) return
        val mid = PosDeviceIdentity.getMerchantId(this)
        if (mid.isNotEmpty()) {
            MerchantFirestore.init(mid)
            Log.d(TAG, "MerchantFirestore initialized from prefs: $mid")
        }
    }

    private fun doStartListeners() {
        PrintingSettingsCache.start()
        PrinterKitchenStyleCache.start(this)
        PrinterDashboardCommandListener.start(this)
        RemoteVoidCommandListener.start(this)
        OnlineTerminalPaymentListener.start(this)
        PrinterFirestoreDeletionSync.start(this)
        PaymentTerminalRepository.start()
        PaymentTerminalReachabilitySync.start(this)
        PosDevicePresenceSync.start(this)
        PosDeviceDeactivationWatch.start(this)
    }

    private fun stopListeners() {
        PrintingSettingsCache.stop()
        PrinterKitchenStyleCache.stop()
        PrinterDashboardCommandListener.stop()
        RemoteVoidCommandListener.stop()
        OnlineTerminalPaymentListener.stop()
        PrinterFirestoreDeletionSync.stop()
        PaymentTerminalRepository.stop()
        PaymentTerminalReachabilitySync.stop()
        PosDevicePresenceSync.stop()
        PosDeviceDeactivationWatch.stop()
    }

    override fun onCreate() {
        super.onCreate()
        ImmersiveModeHelper.install(this)
        ensureMerchantInitialized()
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
    }

    companion object {
        private const val TAG = "PosApplication"
    }
}
