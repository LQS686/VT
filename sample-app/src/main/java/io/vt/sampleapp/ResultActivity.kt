package io.vt.sampleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.postDelayed({
            setResult(RESULT_OK, Intent().putExtra("vt_result", "result-ok"))
            finish()
        }, 600)
    }
}
