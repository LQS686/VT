package io.vt.samplemodule

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * SafeMode 降级模式判定探针。
 *
 * 背景：番茄/红果进入 com.dragon.read.app.SafeModeActivity 后，主界面永远起不来。
 * 字节的 SafeMode 判定集中在 com.dragon.read.app.i2：
 *   - i2.e() 读 SharedPreferences("novel_safe_mode_<versionCode>") 里的
 *     "combo_crash_count_<versionCode>"，> 0 且 SafeModeConfig.turnOn 即进入降级模式；
 *   - i2.b() 打印 "report enter safe mode"；
 *   - i2.g(int) 打印 "report fix type:"。
 * 但 HyperOS 会过滤第三方 tag 的 logcat，所以这里直接 hook 方法本体取返回值。
 */
class SafeModeProbe : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg != "com.dragon.read" && pkg != "com.phoenix.read") return

        val cl = lpparam.classLoader

        // 1) hook i2.e() —— 进入 SafeMode 的总判定
        hookIfPresent(cl, "com.dragon.read.app.i2", "e", "SAFEMODE") { param ->
            val v = param.result
            XposedBridge.log("VT-SAFEMODE i2.e() -> $v")
            // 顺带把 crash count 读出来
            readCrashCount(lpparam)
            v
        }

        // 2) hook i2.b() —— "report enter safe mode"
        hookIfPresent(cl, "com.dragon.read.app.i2", "b", "SAFEMODE") {
            XposedBridge.log("VT-SAFEMODE i2.b() 被调用（report enter safe mode）")
        }

        // 3) hook i2.g(int) —— "report fix type"
        hookIfPresent(cl, "com.dragon.read.app.i2", "g", "SAFEMODE") { param ->
            XposedBridge.log("VT-SAFEMODE i2.g(fixType=${param.args?.firstOrNull()}) 被调用")
        }

        // 4) hook i2.c() —— 判定是否已在 SafeMode 页
        hookIfPresent(cl, "com.dragon.read.app.i2", "c", "SAFEMODE") { param ->
            XposedBridge.log("VT-SAFEMODE i2.c() -> ${param.result}")
            param.result
        }

        // 5) hook 字节 LogWrapper.info，把 SafeModeController 相关日志抓出来
        hookLogWrapper(cl)

        // 6) hook SafeModeActivity.onCreate —— 确认真的进了降级页
        runCatching {
            val act = XposedHelpers.findClass("com.dragon.read.app.SafeModeActivity", cl)
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam<*>) {
                    val act0 = param.thisObject as? android.app.Activity
                    val extras = runCatching {
                        act0?.intent?.extras?.keySet()?.joinToString(",")
                    }.getOrNull()
                    XposedBridge.log("VT-SAFEMODE SafeModeActivity.onCreate extras=$extras")
                }
            })
        }.onFailure { XposedBridge.log("VT-SAFEMODE SafeModeActivity hook 失败: $it") }

        // 7) 决定性探针：hook startActivity，抓「谁发起了 SafeModeActivity」的调用栈。
        //    字节的日志被系统过滤，但调用栈不会。
        runCatching {
            val actCls = XposedHelpers.findClass("android.app.Activity", cl)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val intent = param.args?.firstOrNull { it is android.content.Intent } as? android.content.Intent
                        ?: return
                    val cls = runCatching { intent.component?.className }.getOrNull() ?: return
                    if (!cls.contains("SafeMode")) return
                    val stack = Thread.currentThread().stackTrace
                        .drop(1)
                        .joinToString("\n   ") { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                    XposedBridge.log("VT-SAFEMODE startActivity -> $cls\n   $stack")
                }
            }
            XposedBridge.hookAllMethods(actCls, "startActivity", hook)
            XposedBridge.hookAllMethods(actCls, "startActivityForResult", hook)
        }.onFailure { XposedBridge.log("VT-SAFEMODE startActivity hook 失败: $it") }
    }

    private fun hookIfPresent(
        cl: ClassLoader,
        className: String,
        method: String,
        tag: String,
        after: (XC_MethodHook.MethodHookParam<*>) -> Any?
    ) {
        runCatching {
            val cls = XposedHelpers.findClass(className, cl)
            XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam<*>) {
                    try {
                        after(param)
                    } catch (t: Throwable) {
                        XposedBridge.log("VT-SAFEMODE $className.$method 探针异常: $t")
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("VT-SAFEMODE $className.$method 未找到: $it")
        }
    }

    /** 直接读 SharedPreferences 里的崩溃计数。 */
    private fun readCrashCount(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val app = callStatic(lpparam.classLoader, "android.app.ActivityThread", "currentApplication")
                as? android.app.Application ?: return
            val ctx = (callStatic(
                lpparam.classLoader, "com.dragon.read.app.App", "context"
            ) as? android.content.Context) ?: app
            val verCode = (runCatching {
                val sac = callStatic(
                    lpparam.classLoader, "com.dragon.read.app.SingleAppContext", "inst", ctx
                )
                sac?.let { callInstance(it, "getUpdateVersionCode") }
            }.getOrNull() as? Int) ?: 0
            val spName = "novel_safe_mode_$verCode"
            val sp = ctx.getSharedPreferences(spName, android.content.Context.MODE_PRIVATE)
            val key = "combo_crash_count_$verCode"
            val count = sp.getInt(key, -1)
            XposedBridge.log("VT-SAFEMODE crashCount sp=$spName key=$key value=$count")
            runCatching {
                XposedBridge.log("VT-SAFEMODE sp all = ${sp.all}")
            }
        }.onFailure { XposedBridge.log("VT-SAFEMODE 读 crashCount 失败: $it") }
    }

    /** 反射调用静态方法，避开 XposedHelpers 不同版本签名差异。 */
    private fun callStatic(cl: ClassLoader, className: String, method: String, vararg args: Any?): Any? {
        val cls = XposedHelpers.findClass(className, cl)
        val m = cls.methods.firstOrNull {
            it.name == method && it.parameterCount == args.size
        } ?: cls.methods.first { it.name == method }
        m.isAccessible = true
        return m.invoke(null, *args)
    }

    private fun callInstance(target: Any, method: String): Any? {
        val m = target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }
            ?: target.javaClass.methods.first { it.name == method }
        m.isAccessible = true
        return m.invoke(target)
    }

    /** 字节自己的日志通道被系统过滤，这里把 LogWrapper 的输出转到 Xposed 日志。 */
    private fun hookLogWrapper(cl: ClassLoader) {
        runCatching {
            val cls = XposedHelpers.findClass("com.dragon.read.base.util.LogWrapper", cl)
            XposedBridge.hookAllMethods(cls, "info", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val args = param.args ?: return
                    val joined = args.filter { it is String }.joinToString(" | ")
                    if (joined.contains("safe", true) || joined.contains("SafeMode", true)) {
                        XposedBridge.log("VT-SAFEMODE LogWrapper.info: $joined")
                    }
                }
            })
        }.onFailure { XposedBridge.log("VT-SAFEMODE LogWrapper hook 失败: $it") }
    }
}
