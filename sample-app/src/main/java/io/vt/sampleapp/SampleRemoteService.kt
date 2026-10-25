package io.vt.sampleapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

class SampleRemoteService : Service() {
    private val binder = object : IRemotePing.Stub() {
        override fun ping(): String = "pong-from-remote-service:${Process.myPid()}"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("VTSample", "remote service created pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("VTSample", "remote service started pid=${Process.myPid()}")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i("VTSample", "remote service onBind pid=${Process.myPid()}")
        return binder
    }

    override fun onDestroy() {
        Log.i("VTSample", "remote service destroyed pid=${Process.myPid()}")
        super.onDestroy()
    }
}
