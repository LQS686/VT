package io.vt.samplemodule

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * AIDL interface token 探针 —— 定位 `SecurityException: Binder invocation to an incorrect interface`。
 *
 * 该异常来自 AOSP native 层：
 *   frameworks/base/core/jni/android_os_Parcel.cpp
 *     static void android_os_Parcel_enforceInterface(...) {
 *         ...
 *         jniThrowException(env, "java/lang/SecurityException",
 *                          "Binder invocation to an incorrect interface");
 *     }
 * 语义是：**客户端 writeInterfaceToken(DESCRIPTOR) 写入的 token，
 * 与服务端 Stub.onTransact 里 enforceInterface(DESCRIPTOR) 期望的字符串不相等。**
 *
 * native 层还会打一行：
 *   ALOGW("**** enforceInterface() expected '%s' but read '%s'\n", ...)
 * 但 HyperOS 会过滤第三方 tag，所以这里改为在 Java 层 hook Parcel 的两个入口，
 * 分别打印「谁写了什么 token」和「谁期望什么 token」，并附上进程名。
 */
class BinderIfaceProbe : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg != "com.dragon.read" && pkg != "com.phoenix.read" &&
            pkg != "com.ss.android.ugc.aweme"
        ) {
            return
        }
        val cl = lpparam.classLoader

        hookParcelTokens()
        hookDownloadAidl(cl)
        hookMultiProcCreater(cl)
    }

    // ------------------------------------------------------------------
    // 1) Parcel 层面：写 token / 校验 token
    // ------------------------------------------------------------------
    private fun hookParcelTokens() {
        runCatching {
            val parcel = Class.forName("android.os.Parcel")
            XposedBridge.hookAllMethods(parcel, "writeInterfaceToken", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val token = param.args?.firstOrNull() as? String ?: return
                    if (!isInteresting(token)) return
                    XposedBridge.log("VT-IFACE [${procName()}] writeInterfaceToken: $token")
                }
            })
            XposedBridge.hookAllMethods(parcel, "enforceInterface", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val expected = param.args?.firstOrNull() as? String ?: return
                    if (!isInteresting(expected)) return
                    XposedBridge.log(
                        "VT-IFACE [${procName()}] enforceInterface expect: $expected"
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val expected = param.args?.firstOrNull() as? String ?: return
                    if (!isInteresting(expected)) return
                    val t = param.throwable
                    if (t != null) {
                        val stack = Thread.currentThread().stackTrace.drop(1).take(12)
                            .joinToString("\n   ") {
                                "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                            }
                        XposedBridge.log(
                            "VT-IFACE [${procName()}] enforceInterface FAILED expect=$expected " +
                                "err=${t.javaClass.name}: ${t.message}\n   $stack"
                        )
                    }
                }
            })
        }.onFailure { XposedBridge.log("VT-IFACE Parcel hook 失败: $it") }
    }

    // ------------------------------------------------------------------
    // 2) 字节下载 SDK 的 AIDL 接口：客户端 Proxy / 服务端 Stub
    // ------------------------------------------------------------------
    private fun hookDownloadAidl(cl: ClassLoader) {
        val iface = "com.ss.android.socialbase.downloader.downloader.IDownloadAidlService"
        runCatching {
            val stub = XposedHelpers.findClass("$iface\$Stub", cl)
            val descriptor = readDescriptor(stub)
            XposedBridge.log("VT-IFACE Stub $iface DESCRIPTOR=$descriptor")
            XposedBridge.hookAllMethods(stub, "onTransact", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    XposedBridge.log(
                        "VT-IFACE [${procName()}] Stub.onTransact code=${param.args?.firstOrNull()} " +
                            "DESCRIPTOR=$descriptor"
                    )
                }
            })
        }.onFailure { XposedBridge.log("VT-IFACE Stub 查找失败: $it") }

        runCatching {
            val proxy = XposedHelpers.findClass("$iface\$Stub\$Proxy", cl)
            val descriptor = readDescriptor(proxy)
            XposedBridge.log("VT-IFACE Proxy $iface DESCRIPTOR=$descriptor")
        }.onFailure { XposedBridge.log("VT-IFACE Proxy 查找失败: $it") }

        // 客户端 onServiceConnected：看组件名与 binder 实际类型
        runCatching {
            val handler = XposedHelpers.findClass(
                "com.ss.android.socialbase.downloader.impls.IndependentProcessDownloadHandler",
                cl
            )
            XposedBridge.hookAllMethods(handler, "onServiceConnected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val name = param.args?.firstOrNull()
                    val binder = param.args?.getOrNull(1)
                    XposedBridge.log(
                        "VT-IFACE [${procName()}] onServiceConnected name=$name " +
                            "binder=${binder?.javaClass?.name}"
                    )
                }
            })
        }.onFailure { XposedBridge.log("VT-IFACE handler hook 失败: $it") }
    }

    // ------------------------------------------------------------------
    // 3) MultiProcCreater：字节靠「当前进程名」决定自己是服务端还是客户端
    // ------------------------------------------------------------------
    private fun hookMultiProcCreater(cl: ClassLoader) {
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.ss.android.socialbase.downloader.impls.MultiProcCreater",
                cl
            )
            for (m in listOf("createServiceHandler", "createProxy")) {
                XposedBridge.hookAllMethods(cls, m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        XposedBridge.log(
                            "VT-IFACE [${procName()}] MultiProcCreater.$m -> " +
                                "${param.result?.javaClass?.name}"
                        )
                    }
                })
            }
        }.onFailure { XposedBridge.log("VT-IFACE MultiProcCreater hook 失败: $it") }
    }

    // ------------------------------------------------------------------
    private fun readDescriptor(cls: Class<*>): String? = runCatching {
        val f = cls.getDeclaredField("DESCRIPTOR")
        f.isAccessible = true
        f.get(null) as? String
    }.getOrNull()

    private fun isInteresting(token: String): Boolean =
        token.contains("socialbase", true) ||
            token.contains("downloader", true) ||
            token.contains("IDownload", true) ||
            token.contains("dragon.read", true)

    private fun procName(): String =
        runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "?"
}
