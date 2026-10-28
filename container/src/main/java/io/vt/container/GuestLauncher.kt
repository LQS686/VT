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
            if (!slotAlive(slot, assigned, running)) continue
            // Best-effort cleanup (foreground notifications) before the process is killed.
            val receiver = "io.vt.container.runtime.ControlReceiver$slot"
            val intent = Intent(io.vt.container.runtime.ACTION_STOP_GUEST)
                .setClassName(context.packageName, receiver)
                .putExtra(io.vt.container.runtime.EXTRA_STOP_GUEST_ID, guest.id)
            runCatching { context.sendBroadcast(intent) }
        }

        // The guest renames its process to the target package, so AMS may no longer route
        // broadcasts to :g0/:g1 components. Kill by PID instead; manager and guest share UID.
        var last = emptyList<Pair<Int, String>>()
        repeat(3) {
            val pids = guestProcesses(guest)
            if (pids.isEmpty()) return@repeat
            last = pids
            for ((pid, name) in pids) {
                runCatching { android.os.Process.killProcess(pid) }
                XposedLog.log("kill guest pid=$pid name=$name")
            }
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline && guestProcesses(guest).isNotEmpty()) {
                Thread.sleep(100)
            }
        }
        for (slot in 0..1) {
            if (prefs.getString("slot_$slot", null) == guest.id) {
                prefs.edit().remove("slot_$slot").apply()
            }
        }
        XposedLog.log(
            "停止 ${guest.label}: " +
                if (guestProcesses(guest).isEmpty()) "已退出" else "仍有进程 ${last.joinToString { it.second }}"
        )
    }

    // 主进程/子进程都已把 cmdline 改成 Guest 包名；优先 ActivityManager，必要时读 /proc。
    private fun guestProcesses(guest: GuestRecord): List<Pair<Int, String>> {
        val myUid = android.os.Process.myUid()
        val found = LinkedHashMap<Int, String>()
        fun isHostSlot(processName: String): Boolean {
            val slot = when (processName) {
                "${context.packageName}:g0" -> 0
                "${context.packageName}:g1" -> 1
                else -> return false
            }
            return prefs.getString("slot_$slot", null) == guest.id
        }
        fun isOurProcess(processName: String): Boolean =
            processName == guest.id || processName.startsWith("${guest.id}:") ||
                isHostSlot(processName)

        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getRunningAppProcesses()
                ?.forEach { info ->
                    if (info.uid == myUid && isOurProcess(info.processName)) {
                        found[info.pid] = info.processName
                    }
                }
        }
        runCatching {
            java.io.File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (found.containsKey(pid)) return@forEach
                val cmd = runCatching {
                    java.io.File(dir, "cmdline").readText().trim('\u0000', ' ', '\n')
                }.getOrNull().orEmpty()
                if (!isOurProcess(cmd)) return@forEach
                val status = runCatching {
                    java.io.File(dir, "status").readText()
                }.getOrNull().orEmpty()
                val uid = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
                if (uid == myUid) found[pid] = cmd
            }
        }
        // main process first, then subprocesses
        return found.entries.sortedBy { it.value.length }.map { it.key to it.value }
    }

    // Guest 进程会把 cmdline/进程名改成目标应用名，因此不能只按 :g0/:g1 判断槽位存活；
    // 还要看该槽位登记的 Guest 主进程及其 :subprocess 是否仍在运行。
    private fun slotAlive(slot: Int, assigned: String?, running: Set<String>): Boolean {
        if (running.contains("${context.packageName}:g$slot")) return true
        if (assigned == null) return false
        return running.any { it == assigned || it.startsWith("$assigned:") }
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
            val alive = slotAlive(slot, assigned, running)
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
