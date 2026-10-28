package io.vt.lightvm

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import io.twoyi.Renderer
import kotlin.math.roundToInt

/**
 * VM 帧缓冲渲染窗口：Surface 交给 libtwoyi 的 OpenGL renderer，
 * 触摸/按键通过 JNI 转发到 VM 内部。
 */
class VmRenderActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private var rendererStarted = false
    private var rendererReady = false
    private var dpiScale = RENDER_SCALE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        // SurfaceView 按降采样尺寸布局，创建出来的 Surface 缓冲区即为该尺寸；
        // 再用 View 缩放填满屏幕（SurfaceControl 合成，不增加 guest 负担）。
        val bufferWidth = (metrics.widthPixels * RENDER_SCALE).roundToInt() and 1.inv()
        val bufferHeight = (metrics.heightPixels * RENDER_SCALE).roundToInt() and 1.inv()
        dpiScale = bufferWidth.toFloat() / metrics.widthPixels

        surfaceView = SurfaceView(this)
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        container.addView(
            surfaceView,
            FrameLayout.LayoutParams(bufferWidth, bufferHeight, Gravity.CENTER)
        )
        container.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            surfaceView.scaleX = (r - l).toFloat() / bufferWidth
            surfaceView.scaleY = (b - t).toFloat() / bufferHeight
        }
        surfaceView.holder.addCallback(this)
        surfaceView.isFocusable = true
        setContentView(container)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (rendererStarted) return
        val metrics = resources.displayMetrics
        runCatching {
            Renderer.init(
                holder.surface,
                VmManager.loaderPath(this),
                metrics.xdpi * dpiScale,
                metrics.ydpi * dpiScale,
                60
            )
            rendererStarted = true
        }
        // 两仪只调用一次 init：setNativeWindow + resetSubWindow 会创建 subwin 并启动渲染。
        rendererReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // renderer 未就绪时 resetWindow 会报 "No subwin exist" 并可能影响后续绘制。
        if (rendererReady) {
            runCatching { Renderer.resetWindow(holder.surface, 0, 0, width, height) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (rendererStarted) {
            runCatching { Renderer.removeWindow(holder.surface) }
            rendererStarted = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 事件坐标已由 View 变换换算到 Surface 缓冲区坐标系，直接转发。
        if (rendererStarted) Renderer.handleTouch(event)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (rendererStarted) Renderer.sendKeycode(keyCode)
        return true
    }

    private companion object {
        const val RENDER_SCALE = 1f
    }
}
