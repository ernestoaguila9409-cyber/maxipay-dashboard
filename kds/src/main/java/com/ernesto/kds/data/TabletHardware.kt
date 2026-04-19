package com.ernesto.kds.data

import android.os.Build

/** Human-readable tablet identity for Firestore deviceModel (dashboard list). */
object TabletHardware {
    fun modelLabel(): String {
        val model = Build.MODEL.trim()
        val man = Build.MANUFACTURER.trim()
        return when {
            man.isNotBlank() && model.isNotBlank() -> "$man $model"
            model.isNotBlank() -> model
            else -> "Android tablet"
        }
    }
}
