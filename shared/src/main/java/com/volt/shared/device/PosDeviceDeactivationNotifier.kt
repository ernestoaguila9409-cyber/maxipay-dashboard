package com.volt.shared.device

object PosDeviceDeactivationNotifier {
    @Volatile
    var onForceActivation: (() -> Unit)? = null

    fun notifyForceActivation() {
        onForceActivation?.invoke()
    }
}
