package com.ernesto.kds.data

import android.content.SharedPreferences

object KdsDevicePrefs {
    const val PREFS_NAME = "kds_device"
    const val KEY_DEVICE_DOC_ID = "device_doc_id"
    const val KEY_STATION_ID = "station_id"
    /** Firestore `Merchants/{this}/…` id; required for [MerchantFirestore]. */
    const val KEY_MERCHANT_ID = "merchant_id"

    fun clearPairedDevice(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_DEVICE_DOC_ID)
            .remove(KEY_STATION_ID)
            .remove(KEY_MERCHANT_ID)
            .apply()
        MerchantFirestore.reset()
    }
}
