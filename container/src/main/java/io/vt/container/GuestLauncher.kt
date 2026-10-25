package io.vt.container

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import io.vt.container.data.GuestRecord
import io.vt.container.data.GuestStore
import io.vt.container.data.LaunchOutcome
import io.vt.container.runtime.GuestSession
import io.vt.xposed.XposedLog

class GuestLauncher(
    private val context: Context,
    private val store: GuestStore
) {
    private val prefs = context.getSharedPreferences("vt_slots", Context.MODE_PRIVATE)

    fun launch(guest: GuestRecord, activityClass: String? = null): LaunchOutcome {
        val launcher = activityClass ?: guest.launcherActivity
            ?: return LaunchOutcome.Failure("该应用没有可启动的 Activity")
        val slot = acquireSlot(guest.id)
            ?: return LaunchOutcome.Failure("两个容器进程都在运行其他应用，请先重启框架或等待应用退出")
        val stub = "io.vt.container.stub.StubStandard$slot"
        val intent = Intent()
            .setClassName(context.packageName, stub)
            .putExtra(GuestSession.EXTRA_GUEST_ID, guest.id)
            .putExtra(GuestSession.EXTRA_GUEST_CLASS, launcher)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            XposedLog.log("启动 ${guest.label} -> slot=$slot ($launcher)")
            LaunchOutcome.Success
        } catch (t: Throwable) {
            XposedLog.log("启动失败: $t")
            XposedLog.log(t)
            LaunchOutcome.Failure(t.message ?: "启动失败")
        }
    }

    fun stop(guest: GuestRecord) {
        finishGuestTasks(guest)
        val running = runningProcessNames()
        for (slot in 0..1) {
            val key = "slot_$slot"
            val assigned = prefs.getString(key, null)
            if (assigned != null && assigned != guest.id) continue
            if (!running.contains("${context.packageName}:g$slot")) {
                if (assigned == guest.id) prefs.edit().remove(key).apply()
                continue
            }
            // Unassigned slots may host a declared guest subprocess; the receiver validates
            // the guest id before killing its process.
            val receiver = "io.vt.container.runtime.ControlReceiver$slot"
            val intent = Intent(io.vt.container.runtime.ACTION_STOP_GUEST)
                .setClassName(context.packageName, receiver)
                .putExtra(io.vt.container.runtime.EXTRA_STOP_GUEST_ID, guest.id)
            context.sendBroadcast(intent)
            if (assigned == guest.id) prefs.edit().remove(key).apply()
            XposedLog.log("请求停止 ${guest.label} (slot=$slot)")
        }
    }

    private fun finishGuestTasks(guest: GuestRecord) {
        try {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            for (task in activityManager.appTasks) {
                val base = task.taskInfo?.baseIntent ?: continue
                if (base.getStringExtra(GuestSession.EXTRA_GUEST_ID) != guest.id) continue
                XposedLog.log("移除 Guest 任务: task=${task.taskInfo.taskId}")
                task.finishAndRemoveTask()
            }
        } catch (t: Throwable) {
            XposedLog.log("移除 Guest 任务失败")
            XposedLog.log(t)
        }
    }

    private fun acquireSlot(guestId: String): Int? {
        val running = runningProcessNames()
        for (slot in 0..1) {
            val key = "slot_$slot"
            val assigned = prefs.getString(key, null)
            val alive = running.contains("${context.packageName}:g$slot")
            if (!alive) {
                prefs.edit().putString(key, guestId).apply()
                return slot
            }
            if (assigned == guestId) return slot
        }
        return null
    }

    private fun runningProcessNames(): Set<String> {
        return try {
            val manager = context.getSystemService(ActivityManager::class.java)
            manager.getRunningAppProcesses()?.map { it.processName }?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
    }
}
