package io.vt.container.stub

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * These classes exist only as launcher targets in the host manifest. GuestRuntime replaces
 * the instantiated class with the guest Activity via ActivityThread instrumentation. If this
 * base class ever runs, hook installation failed and we must fail visibly instead of showing
 * an empty window.
 */
abstract class BaseStubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "VT 容器钩子未生效，无法启动应用", Toast.LENGTH_LONG).show()
        finish()
    }
}
