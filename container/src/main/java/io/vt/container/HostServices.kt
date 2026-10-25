package io.vt.container

import android.app.Application
import android.util.Log
import io.vt.container.data.GuestStore
import io.vt.container.data.ModuleStore

object HostServices {
    private const val TAG = "VT-Host"

    @Volatile
    var application: Application? = null
        private set

    fun bootstrap(app: Application) {
        application = app
        io.vt.xposed.XposedLog.init(java.io.File(app.filesDir, "logs"))
        // LSPlant/Dobby are only needed inside guest processes. Initializing native ART hooks
        // in the host UI process adds no value and can conflict with vendor hooking frameworks.
        val nativeReady = false
        GuestStore.get(app).reload()
        ModuleStore.get(app).reload()
        io.vt.xposed.XposedLog.log(
            "VT host process started, native deferred to guest processes"
        )
        Log.i(TAG, "host initialized: ${app.packageName}, nativeInHost=$nativeReady")
    }
}
