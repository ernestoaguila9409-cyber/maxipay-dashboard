package com.ernesto.kds.data

import android.content.SharedPreferences

object KdsDevicePrefs {
    const val PREFS_NAME = "kds_device"
    const val KEY_DEVICE_DOC_ID = "device_doc_id"
    const val KEY_STATION_ID = "station_id"

    fun clearPairedDevice(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_DEVICE_DOC_ID)
            .remove(KEY_STATION_ID)
            .apply()
    }
}
