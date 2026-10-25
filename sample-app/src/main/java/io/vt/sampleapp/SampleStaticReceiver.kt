package io.vt.sampleapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SampleStaticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        SampleApp.staticCount += 1
        Log.i("VTSample", "static receiver count=${SampleApp.staticCount}")
    }
}
