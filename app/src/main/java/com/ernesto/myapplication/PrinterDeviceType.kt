package com.ernesto.myapplication

enum class PrinterDeviceType(val firestoreValue: String) {
    RECEIPT("RECEIPT"),
    KITCHEN("KITCHEN");

    companion object {
        fun fromIntentExtra(value: String?): PrinterDeviceType? =
            PrinterDeviceType.values().firstOrNull { it.name == value || it.firestoreValue == value }
    }
}
