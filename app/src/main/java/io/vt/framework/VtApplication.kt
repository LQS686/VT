package io.vt.framework

import android.app.Application
import io.vt.container.GuestBootstrap
import io.vt.container.HostServices

class VtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = currentProcessName() ?: ""
        if (process.endsWith(":g0") || process.endsWith(":g1")) {
            GuestBootstrap.bootstrap(this, process)
        } else {
            HostServices.bootstrap(this)
        }
    }

    private fun currentProcessName(): String? = try {
        Application.getProcessName()
    } catch (_: Throwable) {
        null
    }
}
