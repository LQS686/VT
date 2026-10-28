package io.vt.lightvm

import android.app.Application

class LightVmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LightVmApp
            private set
    }
}
