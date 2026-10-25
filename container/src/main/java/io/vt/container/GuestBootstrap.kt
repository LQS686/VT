package io.vt.container

import android.app.Application
import android.util.Log
import io.vt.container.runtime.GuestRuntime

object GuestBootstrap {
    private const val TAG = "VT-Guest"

    @Volatile
    var application: Application? = null
        private set

    fun bootstrap(app: Application, processName: String) {
        application = app
        val nativeOnly = java.io.File(app.filesDir, "native_only").isFile
        val ready = io.vt.xposed.XposedRuntime.init(app)
        io.vt.xposed.XposedLog.log(
            "guest process $processName bootstrapped, LSPlant ready=$ready, api=${android.os.Build.VERSION.SDK_INT}"
        )
        Log.i(TAG, "guest process initialized: $processName, lsplant=$ready")
        if (nativeOnly) {
            io.vt.xposed.XposedLog.log("native-only debug mode: container hooks skipped")
            return
        }
        installCrashProbe(app)
        installMainDumpProbe(app)
        GuestRuntime.installHooks(app)
    }

    fun installMainDumpProbe(app: Application) {
        if (!java.io.File(app.filesDir, "dump_main").isFile) return
        Thread {
            repeat(3) { index ->
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val main = android.os.Looper.getMainLooper()?.thread ?: return@repeat
                io.vt.xposed.XposedLog.log(
                    "MAIN STACK #${index + 1} state=${main.state}"
                )
                main.stackTrace.take(40).forEach {
                    io.vt.xposed.XposedLog.log("  at $it")
                }
                if (index == 1) {
                    Thread.getAllStackTraces().forEach { (t, stack) ->
                        if (t === main) return@forEach
                        val top = stack.firstOrNull()?.toString() ?: ""
                        if (top.contains("wait") || top.contains("park") ||
                            t.name.contains("rpc", true) || t.name.contains("init", true)
                        ) {
                            io.vt.xposed.XposedLog.log(
                                "THREAD ${t.name} state=${t.state} top=$top"
                            )
                            stack.take(14).forEach {
                                io.vt.xposed.XposedLog.log("  at $it")
                            }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "VT-main-dump"
            start()
        }
        io.vt.xposed.XposedLog.log("Main thread dump probe started")
    }

    fun installCrashProbe(app: Application) {
        if (!java.io.File(app.filesDir, "keep_alive").isFile) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            io.vt.xposed.XposedLog.log("Guest uncaught on ${thread.name}: $throwable")
            io.vt.xposed.XposedLog.log(throwable)
            Thread.getAllStackTraces().forEach { (t, stack) ->
                if (t === thread) return@forEach
                io.vt.xposed.XposedLog.log("Thread ${t.name} state=${t.state}")
                stack.take(30).forEach { io.vt.xposed.XposedLog.log("  at $it") }
            }
            previous?.uncaughtException(thread, throwable)
        }
        io.vt.xposed.XposedLog.log("Guest crash probe installed (keep_alive)")
    }
}
