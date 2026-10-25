package io.vt.container.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import io.vt.xposed.XposedLog

const val ACTION_STOP_GUEST = "io.vt.framework.STOP_GUEST"
const val EXTRA_STOP_GUEST_ID = "vt.stop.guest.id"

abstract class BaseControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_GUEST) return
        val target = intent.getStringExtra(EXTRA_STOP_GUEST_ID)
        val current = GuestRuntime.session
        if (target != null && current != null && current.record.id != target) {
            return
        }
        XposedLog.log("收到停止指令: ${target ?: "当前 guest"}，结束容器进程")
        GuestRuntime.session?.cancelAllForegroundNotifications()
        Process.killProcess(Process.myPid())
    }
}

class ControlReceiver0 : BaseControlReceiver()
class ControlReceiver1 : BaseControlReceiver()
