package io.vt.samplemodule

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 真实应用的 PackageManager 视图探针。
 *
 * 目的：在**真实第三方应用**（抖音 / 番茄小说 / 红果短剧）的容器进程里，
 * 直接读它们看到的 PM 视图，逐项做自洽性检查。
 *
 * 与示例应用的 PmAudit 的区别：
 * - PmAudit 验证的是"示例应用自己声明的组件"；
 * - 本探针验证的是"应用主动查询**别的包**时看到的视图"，
 *   以及"查自己的组件时是否与查包信息一致"。
 *
 * 后者才是真实风控 SDK 常用的探测方式：
 * 先 getPackageInfo(自己) 拿 uid/sourceDir，
 * 再 getActivityInfo(自己某个组件) 拿 applicationInfo，
 * 比对两者的 dataDir 是否一致；不一致即判定为虚拟环境。
 */
class PmProbe : IXposedHookLoadPackage {

    private val targets = setOf(
        "com.ss.android.ugc.aweme",
        "com.dragon.read",
        "com.phoenix.read",
        "com.ss.android.ugc.aweme.lite"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in targets) return
        if (lpparam.processName != lpparam.packageName) {
            // 只在主进程探测一次，避免子进程重复刷屏
            return
        }
        // 等 Application 创建后再探测：此时容器已经把 PM 视图装好
        hookApplicationCreate(lpparam)
    }

    private fun hookApplicationCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val appClass = XposedHelpers.findClass(
                "android.app.Application", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val app = param.thisObject as? android.app.Application ?: return
                    runCatching { probe(app, lpparam.packageName) }
                        .onFailure { XposedBridge.log("VT-PMPROBE 探测异常: $it") }
                }
            })
        }.onFailure {
            XposedBridge.log("VT-PMPROBE hook Application.onCreate 失败: $it")
        }
    }

    private fun probe(app: android.app.Application, pkg: String) {
        val pm = app.packageManager
        val out = StringBuilder()
        var pass = 0
        var fail = 0

        fun check(name: String, ok: Boolean, detail: String) {
            if (ok) pass++ else fail++
            out.append(if (ok) "PASS " else "FAIL ").append(name)
                .append(": ").append(detail).append('\n')
        }

        // P1. 查自己的包信息：拿到 uid / dataDir / sourceDir
        var selfInfo: android.content.pm.ApplicationInfo? = null
        var selfUid = -1
        var selfDataDir: String? = null
        runCatching {
            selfInfo = pm.getApplicationInfo(pkg, 0)
            selfUid = selfInfo?.uid ?: -1
            selfDataDir = selfInfo?.dataDir
        }.onFailure {
            check("self/package-info", false, "throw ${it.javaClass.simpleName}: ${it.message}")
        }
        if (selfInfo != null) {
            check(
                "self/package-info",
                selfUid > 0 && !selfDataDir.isNullOrEmpty(),
                "uid=$selfUid dataDir=$selfDataDir sourceDir=${selfInfo?.sourceDir}"
            )
        }

        // P2. 宿主包必须不可见（容器包名泄漏 = 直接暴露虚拟环境）
        val hostPkg = "io.vt.framework"
        var hostVisible = false
        var hostDetail = ""
        try {
            val ai = pm.getApplicationInfo(hostPkg, 0)
            hostVisible = ai != null
            hostDetail = "visible uid=${ai?.uid} dataDir=${ai?.dataDir}"
        } catch (t: Throwable) {
            hostDetail = "throw ${t.javaClass.simpleName}"
        }
        check("host/invisible", !hostVisible, hostDetail)

        // P3. 组件查询与包查询必须返回同一份视图（关键项）
        //     风控 SDK 的典型探测：比对 getActivityInfo().applicationInfo.dataDir
        //     与 getApplicationInfo().dataDir。
        var componentChecked = false
        runCatching {
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            val act = pi.activities?.firstOrNull { it.name != null }
            if (act != null) {
                val cName = ComponentName(pkg, act.name)
                val activityInfo = pm.getActivityInfo(cName, 0)
                val compData = activityInfo?.applicationInfo?.dataDir
                val compUid = activityInfo?.applicationInfo?.uid
                componentChecked = true
                check(
                    "component/view-consistency",
                    compData != null && compData == selfDataDir && compUid == selfUid,
                    "component(uid=$compUid,data=$compData) self(uid=$selfUid,data=$selfDataDir)"
                )
            }
        }.onFailure {
            if (!componentChecked) {
                check("component/view-consistency", false, "throw ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        // P4. getPackageUid 与 getApplicationInfo.uid 必须一致
        runCatching {
            val uid = pm.getPackageUid(pkg, 0)
            check("uid/self-consistency", uid == selfUid, "getPackageUid=$uid applicationInfo.uid=$selfUid")
        }.onFailure {
            check("uid/self-consistency", false, "throw ${it.javaClass.simpleName}")
        }

        // P5. 已安装列表里不能出现宿主包
        runCatching {
            val installed = pm.getInstalledPackages(0) ?: emptyList()
            val names = installed.mapNotNull { it.packageName }
            val leak = names.contains(hostPkg)
            check(
                "installed/no-host",
                !leak,
                "installed=${names.size} hostLeaked=$leak"
            )
        }.onFailure {
            check("installed/no-host", false, "throw ${it.javaClass.simpleName}")
        }

        // P6. 查询一个绝对不存在的包必须抛 NameNotFoundException
        try {
            val fake = pm.getApplicationInfo("com.vt.nonexistent.probe.pkg", 0)
            check(
                "missing/throws",
                false,
                "unexpectedly returned uid=${fake?.uid}"
            )
        } catch (t: Throwable) {
            check(
                "missing/throws",
                t is PackageManager.NameNotFoundException ||
                    t.cause is PackageManager.NameNotFoundException,
                "throw ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        // P7. 静态路径与实例方法必须返回同一份视图
        //     getPackageInfoAsUserCached 是静态方法，绕过 ApplicationPackageManager。
        runCatching {
            val m = findStaticCachedQueryMethod()
            if (m == null) {
                out.append("SKIP static/cached-view-consistency: not present on this API level\n")
            } else {
                val staticInfo = m.invoke(null, pkg, 0L, 0) as? android.content.pm.PackageInfo
                val staticUid = staticInfo?.applicationInfo?.uid
                val staticData = staticInfo?.applicationInfo?.dataDir
                check(
                    "static/cached-view-consistency",
                    staticUid == selfUid && staticData == selfDataDir,
                    "static(uid=$staticUid,data=$staticData) self(uid=$selfUid,data=$selfDataDir)"
                )
            }
        }.onFailure {
            out.append("SKIP static/cached-view-consistency: ${it.javaClass.simpleName}\n")
        }

        // P8. 静态路径查宿主包同样不可见
        try {
            val m = findStaticCachedQueryMethod()
            if (m != null) {
                val hostInfo = m.invoke(null, hostPkg, 0L, 0) as? android.content.pm.PackageInfo
                check(
                    "static/host-invisible",
                    hostInfo == null,
                    "host visible via static path=${hostInfo != null}"
                )
            }
        } catch (t: Throwable) {
            check(
                "static/host-invisible",
                t is PackageManager.NameNotFoundException ||
                    t.cause is PackageManager.NameNotFoundException,
                "throw ${t.javaClass.simpleName}"
            )
        }

        Log.i("VTPMPROBE", "PM_PROBE passed=$pass failed=$fail")
        out.lines().filter { it.isNotBlank() }.forEach {
            Log.i("VTPMPROBE", "PM_PROBE $it")
        }
        XposedBridge.log("VT-PMPROBE $pkg passed=$pass failed=$fail")
    }

    /**
     * 查找 PackageManager.getPackageInfoAsUserCached(String, long, int)。
     * 该方法是 @hide / hiddenapi BLOCKED，编译期 SDK 里没有，
     * 必须遍历 declaredMethods 反射获取，并 setAccessible 才能调用。
     */
    private fun findStaticCachedQueryMethod(): java.lang.reflect.Method? {
        var found: java.lang.reflect.Method? = null
        for (method in PackageManager::class.java.declaredMethods) {
            if (method.name != "getPackageInfoAsUserCached") continue
            val params = method.parameterTypes
            if (params.size != 3) continue
            if (params[0] != String::class.java) continue
            if (params[1] != Long::class.javaPrimitiveType) continue
            if (params[2] != Int::class.javaPrimitiveType) continue
            found = method
            break
        }
        return runCatching { found?.apply { isAccessible = true } }.getOrNull()
    }
}
