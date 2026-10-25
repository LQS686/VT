package io.vt.samplemodule

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "io.vt.sampleapp" -> hookSampleApp(lpparam)
            "org.schabi.newpipe" -> hookRealApp(lpparam)
            "com.ss.android.ugc.aweme" -> hookDouyin(lpparam)
        }
    }

    private fun hookSampleApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("VT 示例模块已进入 ${lpparam.packageName}@${lpparam.processName}, classLoader=${lpparam.classLoader}")
        XposedHelpers.findAndHookMethod(
            "io.vt.sampleapp.MainActivity",
            lpparam.classLoader,
            "getMessage",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    param.result = "【Xposed 已注入】${param.result}"
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            "io.vt.sampleapp.SampleApp",
            lpparam.classLoader,
            "appTag",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    param.result = "${param.result} | hooked=true"
                }
            }
        )
    }

    private fun hookDouyin(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log(
            "VT 示例模块已进入抖音 ${lpparam.packageName}@${lpparam.processName}, " +
                "classLoader=${lpparam.classLoader}"
        )
        runCatching {
            val activityClass = XposedHelpers.findClass(
                "com.ss.android.ugc.aweme.main.MainActivity",
                lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                activityClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        XposedBridge.log("VT 抖音模块 Hook 生效: MainActivity.onCreate")
                        val activity = param.thisObject as? android.app.Activity ?: return
                        runCatching {
                            activity.window.statusBarColor = android.graphics.Color.MAGENTA
                            val decor = activity.window.decorView as? android.view.ViewGroup
                                ?: return@runCatching
                            val badge = android.widget.TextView(activity).apply {
                                text = "VT MODULE ACTIVE"
                                setTextColor(android.graphics.Color.WHITE)
                                setBackgroundColor(android.graphics.Color.MAGENTA)
                                textSize = 16f
                                setPadding(28, 14, 28, 14)
                            }
                            val lp = android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                                topMargin = 200
                            }
                            decor.addView(badge, lp)
                        }.onFailure {
                            XposedBridge.log("VT 抖音模块浮层失败: $it")
                        }
                    }
                }
            )
            XposedBridge.log("VT 抖音模块 Hook 安装数=${hooks.size}")
        }.onFailure {
            XposedBridge.log("VT 抖音模块 Hook 安装失败: $it")
        }
    }

    private fun hookRealApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log(
            "VT 示例模块已进入真实应用 ${lpparam.packageName}@${lpparam.processName}, " +
                "classLoader=${lpparam.classLoader}"
        )
        val activityClass = XposedHelpers.findClass(
            "org.schabi.newpipe.MainActivity",
            lpparam.classLoader
        )
        val hooks = XposedBridge.hookAllMethods(
            activityClass,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    XposedBridge.log("VT 真实应用 Hook 生效: NewPipe MainActivity.onCreate")
                }
            }
        )
        XposedBridge.log("VT 真实应用 Hook 安装数=${hooks.size}")
        val ctorHooks = XposedBridge.hookAllConstructors(
            activityClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    XposedBridge.log("VT 真实应用构造方法 Hook 生效: NewPipe MainActivity.<init>")
                }
            }
        )
        XposedBridge.log("VT 真实应用构造 Hook 安装数=${ctorHooks.size}")
    }
}
