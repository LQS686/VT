package io.vt.container.stub

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.vt.container.runtime.GuestRuntime
import io.vt.container.runtime.GuestSession
import io.vt.xposed.XposedLog

/**
 * Host-side service placeholder used to place a Guest Service in a specific process slot.
 * The system binds/starts this class; GuestRuntime forwards lifecycle calls to the real
 * Guest service loaded by a GuestSession in the same process.
 */
abstract class BaseStubGuestService : Service() {
    private var session: GuestSession? = null
    private val startedClasses = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val startedServices = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun ensureReady(stubIntent: Intent?): GuestSession? {
        session?.let { return it }
        if (stubIntent == null) return null
        val guestId = stubIntent.getStringExtra(GuestSession.EXTRA_GUEST_ID) ?: return null
        val className = stubIntent.getStringExtra(GuestSession.EXTRA_GUEST_SERVICE_CLASS)
            ?: return null
        val runtime = GuestRuntime.ensureSession(application, guestId, stubIntent)
        if (runtime == null) {
            XposedLog.log("StubService 无法初始化 GuestSession: $guestId")
            stopSelf()
            return null
        }
        runtime.loadModulesOnce(null)
        runtime.ensureApplication()
        runtime.setServiceStopCallback(className) {
            XposedLog.log("Guest 服务自行停止，结束 StubService: $className")
            stopSelf()
        }
        session = runtime
        startedClasses.add(className)
        XposedLog.log("StubService ready: $className in ${Application.getProcessName()}")
        return runtime
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = ensureReady(intent) ?: return START_NOT_STICKY
        val className = intent?.getStringExtra(GuestSession.EXTRA_GUEST_SERVICE_CLASS)
            ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val original = intent.getParcelableExtra<Intent>(GuestSession.EXTRA_GUEST_INTENT)
        startedClasses.add(className)
        startedServices.add(className)
        XposedLog.log("StubService onStartCommand: $className")
        return runtime.startGuestServiceByClass(className, original, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        val runtime = ensureReady(intent) ?: return null
        val className = intent?.getStringExtra(GuestSession.EXTRA_GUEST_SERVICE_CLASS) ?: return null
        @Suppress("DEPRECATION")
        val original = intent.getParcelableExtra<Intent>(GuestSession.EXTRA_GUEST_INTENT)
        startedClasses.add(className)
        XposedLog.log("StubService onBind: $className")
        return runtime.bindGuestServiceByClass(className, original)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val runtime = session
        val className = intent?.getStringExtra(GuestSession.EXTRA_GUEST_SERVICE_CLASS)
        if (runtime != null && className != null && !startedServices.contains(className)) {
            runtime.stopGuestServiceByClass(className)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        val runtime = session
        for (className in startedClasses.toList()) {
            runtime?.stopGuestServiceByClass(className)
        }
        startedClasses.clear()
        startedServices.clear()
        session = null
        XposedLog.log("StubService destroyed in ${Application.getProcessName()}")
        super.onDestroy()
    }
}

class StubGuestService0 : BaseStubGuestService()
class StubGuestService1 : BaseStubGuestService()
