package io.vt.sampleapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.File

class SampleService : Service() {
    inner class LocalBinder : Binder() {
        fun ping(): String = "pong-from-guest-service"
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        SampleApp.serviceStarted = true
        applicationContext?.let { File(it.filesDir, "service_created").writeText("yes") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOREGROUND -> showForegroundNotification()
            ACTION_STOP_FOREGROUND -> {
                stopForeground(true)
                stopSelf()
                SampleApp.foregroundStopped = true
            }
        }
        return START_NOT_STICKY
    }

    private fun showForegroundNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "VT 前台测试",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val launch = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vt_notify)
                .setContentTitle("VT Guest 前台服务")
                .setContentText("来自 Guest 的前台通知")
                .setContentIntent(launch)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
            SampleApp.foregroundStarted = true
            Log.i("VTSample", "startForeground requested")
        } catch (t: Throwable) {
            SampleApp.foregroundError = t.toString()
            Log.e("VTSample", "startForeground failed", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        SampleApp.foregroundStopped = true
    }

    companion object {
        const val ACTION_FOREGROUND = "io.vt.sampleapp.action.FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "io.vt.sampleapp.action.STOP_FOREGROUND"
        const val CHANNEL_ID = "vt_sample_foreground"
        const val NOTIFICATION_ID = 7
    }
}
