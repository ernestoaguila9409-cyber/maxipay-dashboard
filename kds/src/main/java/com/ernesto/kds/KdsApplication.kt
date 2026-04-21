package com.ernesto.kds

import android.app.Application

class KdsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ImmersiveModeHelper.install(this)
    }
}
