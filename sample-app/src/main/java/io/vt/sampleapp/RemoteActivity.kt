package io.vt.sampleapp

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class RemoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            textSize = 20f
            text = "跨进程 Guest Activity\n" +
                "pid=${Process.myPid()}\n" +
                "application=${application.javaClass.name}\n" +
                "process=${android.app.Application.getProcessName()}\n" +
                "dataDir=$filesDir"
        })
        layout.addView(Button(this).apply {
            text = "关闭跨进程页面"
            setOnClickListener { finish() }
        })
        setContentView(layout)
        Log.i("VTSample", "RemoteActivity opened pid=${Process.myPid()}")
    }
}
