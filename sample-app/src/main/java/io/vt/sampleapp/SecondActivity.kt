package io.vt.sampleapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class SecondActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)
        findViewById<TextView>(R.id.second_text).text =
            "第二个页面\n${SampleApp.openCountText()}\n返回后主页面状态应保持"
    }
}
