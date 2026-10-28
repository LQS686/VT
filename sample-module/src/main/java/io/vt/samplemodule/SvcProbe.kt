package io.vt.samplemodule

import android.content.Context
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 一次性诊断探针：打印 Guest 进程里 ConnectivityManager / TelephonyManager 的
 * 内部字段结构，以及 attribution 来源，用于确定修复切入点。
 */
class SvcProbe : IXposedHookLoadPackage {

    private val targets = setOf(
        "com.ss.android.ugc.aweme",
        "com.dragon.read",
        "com.phoenix.read"
    )

    @Volatile
    private var dumped = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in targets) return
        if (lpparam.processName != lpparam.packageName) return
        XposedBridge.log("VT-SVCPROBE handleLoadPackage ${lpparam.packageName}")

        // 直接 hook ConnectivityManager.getNetworkCapabilities：
        // 在方法入口打印签名与实例字段（不依赖 Application 生命周期）。
        runCatching {
            val cmClass = XposedHelpers.findClass(
                "android.net.ConnectivityManager", lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                cmClass, "getNetworkCapabilities", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (dumped) return
                        dumped = true
                        val sb = StringBuilder()
                        sb.append("CM class = ").append(param.thisObject?.javaClass?.name).append('\n')
                        val m = param.method
                        if (m is java.lang.reflect.Method) {
                            sb.append("sig = ").append(m.name).append('(')
                                .append(m.parameterTypes.joinToString(", ") { it.name })
                                .append(") -> ").append(m.returnType.name).append('\n')
                        }
                        sb.append("args = ")
                            .append(param.args?.joinToString(", ") { it?.javaClass?.name ?: "null" })
                            .append('\n')
                        val inst = param.thisObject
                        if (inst != null) {
                            var clazz: Class<*>? = inst.javaClass
                            var depth = 0
                            while (clazz != null && depth < 3) {
                                for (f in clazz.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val v = runCatching {
                                        f.isAccessible = true
                                        f.get(inst)
                                    }.getOrNull()
                                    sb.append("    ").append(f.name).append(" : ")
                                        .append(f.type.name).append(" = ")
                                        .append(describe(v)).append('\n')
                                }
                                clazz = clazz.superclass
                                depth++
                            }
                        }
                        Log.i("VTSVCPROBE", "=== CM ENTRY ===")
                        sb.lines().filter { it.isNotBlank() }.forEach {
                            Log.i("VTSVCPROBE", it)
                            // HyperOS 会过滤第三方 tag 的 logcat，
                            // 容器自己的 xposed.log 才是可靠通道。
                            XposedBridge.log("VT-SVCPROBE $it")
                        }
                        Log.i("VTSVCPROBE", "=== CM ENTRY END ===")
                    }
                })
            XposedBridge.log("VT-SVCPROBE CM hook installed n=${hooks.size}")
        }.onFailure { XposedBridge.log("VT-SVCPROBE CM hook failed: $it") }
    }

    private fun dump(app: android.app.Application) {
        if (dumped) return
        dumped = true
        val sb = StringBuilder()

        // 关键实验：同一个 ConnectivityManager 实例，在 Guest 进程里调用时
        // attribution 用的是 Guest 包名，会被服务端拒绝。
        // 这里测试：能否通过"把 Context 换成宿主"绕开。
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE)
            sb.append("CM class = ").append(cm?.javaClass?.name).append('\n')
            sb.append("CM identity = ").append(System.identityHashCode(cm)).append('\n')
            // 尝试调用，观察是否抛异常
            try {
                val m = cm?.javaClass?.getMethod("getNetworkCapabilities", Int::class.javaPrimitiveType)
                m?.isAccessible = true
                val r = m?.invoke(cm, 1)
                sb.append("guest getNetworkCapabilities(1) = ").append(describe(r)).append('\n')
            } catch (t: Throwable) {
                sb.append("guest getNetworkCapabilities threw ")
                    .append(t.javaClass.simpleName).append(": ").append(t.message).append('\n')
            }
            // 关键：ActivityThread.currentOpPackageName 是什么
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                for (m in listOf("currentPackageName", "currentOpPackageName")) {
                    val mm = at.getDeclaredMethod(m)
                    mm.isAccessible = true
                    sb.append("AT.$m = ").append(describe(mm.invoke(null))).append('\n')
                }
            }
        }

        for ((label, svc) in listOf(
            "CONNECTIVITY" to app.getSystemService(Context.CONNECTIVITY_SERVICE),
            "TELEPHONY" to app.getSystemService(Context.TELEPHONY_SERVICE)
        )) {
            if (svc == null) {
                sb.append("--- $label: null\n")
                continue
            }
            sb.append("--- $label: ").append(svc.javaClass.name).append('\n')
            var clazz: Class<*>? = svc.javaClass
            var depth = 0
            while (clazz != null && depth < 3) {
                sb.append("  [").append(clazz.name).append("]\n")
                for (f in clazz.declaredFields) {
                    // 只看实例字段：静态常量没有诊断价值
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(svc)
                    }.getOrNull()
                    sb.append("    ").append(f.name).append(" : ")
                        .append(f.type.simpleName).append(" = ")
                        .append(describe(v)).append('\n')
                }
                clazz = clazz.superclass
                depth++
            }
            // 关键方法签名
            sb.append("  methods:\n")
            for (m in svc.javaClass.methods) {
                if (!m.name.contains("etwork") && !m.name.contains("Network")) continue
                sb.append("    ").append(m.name).append('(')
                    .append(m.parameterTypes.joinToString(", ") { it.simpleName })
                    .append(") -> ").append(m.returnType.simpleName).append('\n')
            }
        }

        Log.i("VTSVCPROBE", "=== SVC DUMP BEGIN ===")
        sb.lines().filter { it.isNotBlank() }.forEach {
            Log.i("VTSVCPROBE", it)
            XposedBridge.log("VT-SVCPROBE $it")
        }
        Log.i("VTSVCPROBE", "=== SVC DUMP END ===")
        XposedBridge.log("VT-SVCPROBE dumped")
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Int -> v.toString()
        is Boolean -> v.toString()
        else -> v.javaClass.name
    }
}
