package io.vt.sampleapp

import android.app.Application
import android.os.Process

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createCount++
        createdAt = System.currentTimeMillis()
        val counter = java.io.File(filesDir, "launch_count")
        launchCount = (counter.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        counter.writeText(launchCount.toString())
    }

    fun appTag(): String {
        return "guestApplication=${javaClass.name}, process=${Process.myPid()}, dataDir=$dataDir"
    }

    companion object {
        @JvmField
        var createCount: Int = 0

        @JvmField
        var createdAt: Long = 0L

        @JvmField
        var launchCount: Int = 0

        @JvmField
        var providerInitialized: Boolean = false

        @JvmField
        var serviceStarted: Boolean = false

        @JvmField
        var boundResult: String? = null

        @JvmField
        var broadcastCount: Int = 0

        @JvmField
        var staticCount: Int = 0

        @JvmField
        var foregroundStarted: Boolean = false

        @JvmField
        var foregroundStopped: Boolean = false

        @JvmField
        var foregroundError: String? = null

        @JvmStatic
        fun openCountText(): String = "已打开页面次数：$createCount"
    }
}
