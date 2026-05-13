package com.volt.maximobile

/**
 * Main thread: [MainActivity] assigns [onForceActivation] to jump back to the activation flow
 * when the dashboard marks this install as deactivated in Firestore.
 */
object PosDeviceDeactivationNotifier {
    @Volatile
    var onForceActivation: (() -> Unit)? = null

    fun notifyForceActivation() {
        onForceActivation?.invoke()
    }
}
