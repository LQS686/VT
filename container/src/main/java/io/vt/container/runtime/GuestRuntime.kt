package io.vt.container.runtime

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.vt.container.data.GuestStore
import io.vt.container.data.syntheticInstalledSourceDir
import io.vt.xposed.Hooker
import java.io.File
import io.vt.xposed.XposedLog
import java.lang.reflect.Field

object GuestRuntime {
    private const val TAG = "VT-GuestRuntime"

    @Volatile
    private var installed = false

    @Volatile
    private var hostApplication: Application? = null

    @Volatile
    private var hostQueryLogged = 0

    @Volatile
    private var diagnosticsInstalled = false

    private val antiDetectionHits = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val toastProxyLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val loggedUrls = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    @Volatile
    var session: GuestSession? = null
        private set
    private val remoteBindings = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<android.content.ServiceConnection, Boolean>()
    )

    fun installHooks(host: Application) {
        if (installed) return
        installed = true
        hostApplication = host
        hookStartActivity()
        hookActivityTaskManagerAttribution()
        hookPerformLaunchActivity()
        hookNewActivity()
        hookActivityAttach()
        hookApplicationLifecycle()
        hookServices()
        hookBroadcasts()
        hookReceiverInstantiation()
        hookOnNewIntent()
        hookActivityResults()
        hookRuntimePermissions()
        hookActivityManagerExtras()
        hookActivityManagerIdentity()
        hookContextIdentity()
        hookExternalStorage()
        hookStorageManager()
        hookEnvironmentStorage()
        hookFileConstructorFallback()
        hookSystemServiceShims()
        hookNotificationManager()
        hookToast()
        hookPendingIntents()
        hookLocaleManager()
        hookMediaSession()
        hookMediaKeyAttribution()
        hookProviderIdentity()
        hookIdentity()
        hookPackageManager()
        hookIdentity()
        XposedLog.log("GuestRuntime hooks installed in ${Application.getProcessName()}")
    }

    fun ensureSession(host: Application, guestId: String, stubIntent: Intent): GuestSession? {
        val existing = session
        if (existing != null) {
            if (existing.record.id == guestId) return existing
            XposedLog.log("进程已承载 ${existing.record.sourcePackage}，拒绝切换至 $guestId")
            return null
        }
        val record = GuestStore.get(host).getById(guestId)
        if (record == null) {
            XposedLog.log("找不到导入记录: $guestId")
            return null
        }
        val processName = Application.getProcessName().orEmpty()
        val slot = if (processName.endsWith(":g1")) 1 else 0
        val guestProcess = stubIntent.getStringExtra(GuestSession.EXTRA_GUEST_PROCESS)
            ?: record.sourcePackage
        // Install the native path shim before Guest classes/native libraries load, so
        // early SecSDK environment scans see real app paths instead of the container.
        if (record.sourcePackage == "com.phoenix.read") {
            runCatching {
                io.vt.xposed.VtNative.ensureLoaded()
                val realApk = record.installedSourceDir?.takeIf { it.isNotBlank() }
                    ?: record.baseApk().orEmpty()
                val shim = io.vt.xposed.VtNative.installEnvShim(
                    host.packageName, record.sourcePackage, realApk
                )
                XposedLog.log("early env shim installed=$shim apk=$realApk")
            }.onFailure { XposedLog.log("early env shim failed: $it") }
        }
        return try {
            GuestSession(host, record, slot, guestProcess).also {
                it.initialize()
                session = it
                // 进程把 cmdline 改成 Guest 包名后，管理进程无法再按 :g0/:g1 识别它。
                // 每个容器进程在会话建立时登记自己占用的槽位，停止时按槽位下发指令。
                host.getSharedPreferences("vt_slots", Context.MODE_PRIVATE).edit()
                    .putString("slot_$slot", record.id)
                    .apply()
                hookGuestAntiDetection(it)
                hookMetasecStackDetection(it)
                hookMetasecBundleVersion(it)
                installBytedanceDiagnostics(it)
            }
        } catch (t: Throwable) {
            XposedLog.log("GuestSession 初始化失败")
            XposedLog.log(t)
            null
        }
    }

    /**
     * 屏蔽字节 Metasec 安全 SDK 的“调用栈反 Hook 采样”。
     *
     * 背景（2026-10-26 实测）：番茄小说/红果首次运行（全新数据）时正文/短剧正常，
     * 运行一段时间后安全 SDK 进入 bypass/降级签名（抓到的 `x-argus` 退化为 4 字节），
     * 服务端随即对 `reader/full`、`bookmall` 等接口返回 `ILLEGAL_ACCESS`（code 110）。
     *
     * 反编译 `ms.bd.c.r2`（MSB 的父类）确认采样逻辑：
     *
     * ```
     * if (i19 == 2) {              // 2 = URL 访问事件，每次网络请求都会调用
     *     if (SwitchManager.a().c(14)) {
     *         按 host 计数：第 4 次起每 10 次取一次当前线程调用栈，
     *         逐帧比对类名/方法名（反 Hook 特征），命中则记入可疑集合
     *     }
     *     return;                  // 该分支不参与签名，只有检测副作用
     * }
     * // 其余分支：签名/初始化，必须放行
     * ```
     *
     * 容器里 Hook 派发会留下 `de.robv.android.xposed.XC_MethodHook` 等栈帧，
     * 因此采样必然命中；命中后 SDK 降级签名 → 服务端拒绝。
     *
     * 修法：只跳过"URL 访问事件"（i19==2）这一条纯检测分支，
     * 并把开关 14 关掉；签名/初始化路径保持原样。
     */
    private fun hookMetasecStackDetection(current: GuestSession) {
        val r2 = runCatching { Class.forName("ms.bd.c.r2", false, current.classLoader) }
            .getOrNull()
        if (r2 != null) {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (param.args.getOrNull(1) == 2) param.setResult(null)
                }
            }
            val n = runCatching { XposedBridge.hookAllMethods(r2, "a", hook).size }
                .getOrDefault(0)
            XposedLog.log("metasec URL-event sampling shim installed=$n")
        }
        val managerUtils = runCatching {
            Class.forName("com.bytedance.mobsec.metasec.ml.MSManagerUtils", false, current.classLoader)
        }.getOrNull()
        if (managerUtils != null) {
            val initHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    XposedLog.log("metasec init result=${param.result} throwable=${param.throwable}")
                }
            }
            val n = runCatching { XposedBridge.hookAllMethods(managerUtils, "init", initHook).size }
                .getOrDefault(0)
            XposedLog.log("metasec init observe installed=$n")
        }
        val f3 = runCatching { Class.forName("ms.bd.c.f3", false, current.classLoader) }.getOrNull()
        if (f3 != null) {
            var featureLogged = 0
            val featureHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (featureLogged >= 3) return
                    featureLogged++
                    val map = param.result as? Map<*, *>
                    val summary = map?.entries?.joinToString(",") { entry ->
                        "${entry.key}=${(entry.value as? String)?.length ?: entry.value}"
                    }
                    XposedLog.log("metasec featureHash ${param.args.getOrNull(0)} -> $summary")
                }
            }
            val n = runCatching { XposedBridge.hookAllMethods(f3, "getFeatureHash", featureHook).size }
                .getOrDefault(0)
            XposedLog.log("metasec featureHash observe installed=$n")
        }
        val switchManager = runCatching {
            Class.forName("com.bytedance.mobsec.metasec.core.SwitchManager", false, current.classLoader)
        }.getOrNull()
        if (switchManager != null) {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (param.args.getOrNull(0) == 14) param.setResult(false)
                }
            }
            val n = runCatching { XposedBridge.hookAllMethods(switchManager, "c", hook).size }
                .getOrDefault(0)
            XposedLog.log("metasec detection switch(14) disabled=$n")
        }
    }

    /**
     * 修正 Metasec 采集 SS_VERSION_NAME 时的类型错误。
     *
     * 容器里 PackageInfo.versionName 传入 Metasec 的 Bundle 后变成 Integer，
     * j3.d 里 Bundle.getString 抛 ClassCastException，采集线程随之中断，
     * x-argus 降级为 4 字节，服务端对 bookmall/reader 返回 110。
     * 只在取到的值不是 String 时用 Guest 真实 versionName 兜底。
     */
    private fun hookMetasecBundleVersion(current: GuestSession) {
        val baseBundle = runCatching {
            Class.forName("android.os.BaseBundle", false, current.classLoader)
        }.getOrNull() ?: return
        var logged = false
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                if (param.args.getOrNull(0) != "SS_VERSION_NAME") return
                val bundle = param.thisObject as? android.os.BaseBundle ?: return
                val value = runCatching { bundle.get("SS_VERSION_NAME") }.getOrNull()
                if (value is String) return
                param.result = current.record.versionName
                if (!logged) {
                    logged = true
                    XposedLog.log(
                        "metasec SS_VERSION_NAME fixed=${current.record.versionName} " +
                            "was=${value?.javaClass?.simpleName}"
                    )
                }
            }
        }
        val n = runCatching { XposedBridge.hookAllMethods(baseBundle, "getString", hook).size }
            .getOrDefault(0)
        XposedLog.log("metasec version-name shim installed=$n")
    }

    /**
     * 屏蔽 Guest 应用自带的“Xposed 检测”方法。
     *
     * 背景：番茄/红果的内容接口在容器内返回 ILLEGAL_ACCESS（直装正常）。
     * 应用内置了基于**调用栈**的 Xposed 检测：
     *
     * ```
     * DeviceUtils.isInstallXposed() {
     *     throw new Exception("hook");
     *     // 扫描 stackTraceElement.getClassName().contains("de.robv.android.xposed")
     * }
     * ```
     *
     * 容器所有 Hook 派发都在 Guest 方法之上留下 `de.robv.android.xposed.XC_MethodHook`
     * 栈帧，所以只要检测发生在任意被 Hook 的调用路径里就会命中。
     *
     * 这里只覆盖已确认存在的检测入口，不动其它设备信息逻辑。
     */
    private fun hookGuestAntiDetection(current: GuestSession) {
        val targets = listOf(
            "com.bytedance.android.standard.tools.device.DeviceUtils" to "isInstallXposed",
            "com.bytedance.common.utility.DeviceUtils" to "isInstallXposed",
            "com.ss.android.socialbase.appdownloader.util.SecurityUtils" to "isXposedExist",
            "com.tencent.tinker.lib.utils.Utils" to "isXposedExists"
        )
        var installed = 0
        for ((className, methodName) in targets) {
            val clazz = runCatching {
                Class.forName(className, false, current.classLoader)
            }.getOrNull() ?: continue
            val label = "$className.$methodName"
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (antiDetectionHits.add(label)) {
                        XposedLog.log("anti-detection hit: $label -> false")
                    }
                    param.setResult(false)
                }
            }
            val count = runCatching {
                XposedBridge.hookAllMethods(clazz, methodName, hook).size
            }.getOrDefault(0)
            if (count > 0) installed += count
        }
        XposedLog.log("Guest anti-detection shims installed=$installed")
    }

    fun installBytedanceDiagnostics(current: GuestSession) {
        if (current.record.sourcePackage != "com.dragon.read" &&
            current.record.sourcePackage != "com.phoenix.read"
        ) {
            return
        }
        if (!File(current.host.filesDir, "vt_net_trace").isFile) return
        if (diagnosticsInstalled) return
        diagnosticsInstalled = true
        runCatching {
            val errorClass = Class.forName(
                "com.dragon.read.base.http.exception.ErrorCodeException",
                false,
                current.classLoader
            )
            XposedBridge.hookAllConstructors(
                errorClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val code = param.args.filterIsInstance<Int>().firstOrNull() ?: return
                        if (code != 110) return
                        val message = param.args.filterIsInstance<String>().firstOrNull()
                        XposedLog.log(
                            "NET110 message=$message\n" +
                                android.util.Log.getStackTraceString(Throwable())
                        )
                    }
                }
            )
            val netReqClass = Class.forName(
                "com.dragon.read.util.NetReqUtil", false, current.classLoader
            )
            XposedBridge.hookAllMethods(
                netReqClass,
                "doAfterDeserialization",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val response = param.args.getOrNull(3) ?: return
                        val code = runCatching {
                            response.javaClass.getField("code").get(response) as Int
                        }.getOrNull() ?: return
                        if (code != 110) return
                        val url = param.args.getOrNull(0) as? String
                        val headers = param.args.getOrNull(4)?.toString()
                        XposedLog.log(
                            "NET110 url=$url\nheaders=$headers\nbody=$response"
                        )
                    }
                }
            )
            val httpClass = Class.forName(
                "com.bytedance.retrofit2.SsHttpCall", false, current.classLoader
            )
            XposedBridge.hookAllMethods(
                httpClass,
                "execute",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val request = getObjectField(param.thisObject, "delayRequest")
                            ?: getObjectField(param.thisObject, "originalRequest")
                        val url = runCatching {
                            request?.javaClass?.getMethod("url")?.invoke(request)?.toString()
                        }.getOrNull() ?: runCatching {
                            request?.javaClass?.getMethod("getUrl")?.invoke(request)?.toString()
                        }.getOrNull() ?: runCatching {
                            request?.javaClass?.declaredFields?.firstNotNullOfOrNull { f ->
                                f.isAccessible = true
                                val value = f.get(request)
                                value?.toString()?.takeIf { it.contains("http") }
                            }
                        }.getOrNull()
                        if (url != null && loggedUrls.add(url)) {
                            val result = param.result
                            val body = runCatching {
                                result?.javaClass?.getMethod("body")?.invoke(result)
                            }.getOrNull()
                            val code = runCatching {
                                body?.javaClass?.getField("code")?.get(body) as Int
                            }.getOrNull()
                            XposedLog.log("HTTP url=$url code=$code body=${body?.toString()?.take(300)}")
                            if (url.contains("reader/full") || url.contains("batch_full")) {
                                val requestFields = request?.javaClass?.declaredFields?.joinToString {
                                    runCatching {
                                        it.isAccessible = true
                                        it.name + "=" + it.get(request)
                                    }.getOrDefault(it.name)
                                }
                                val bodyFields = body?.javaClass?.declaredFields?.joinToString {
                                    runCatching {
                                        it.isAccessible = true
                                        it.name + "=" + it.get(body)
                                    }.getOrDefault(it.name)
                                }
                                val extraInfo = request?.let { getObjectField(it, "extraInfo") }
                                val extraFields = extraInfo?.javaClass?.declaredFields?.joinToString {
                                    runCatching {
                                        it.isAccessible = true
                                        it.name + "=" + it.get(extraInfo)
                                    }.getOrDefault(it.name)
                                }
                                val httpInfo = body?.let { getObjectField(it, "c") }
                                val httpFields = httpInfo?.javaClass?.declaredFields?.joinToString {
                                    runCatching {
                                        it.isAccessible = true
                                        it.name + "=" + it.get(httpInfo)
                                    }.getOrDefault(it.name)
                                }
                                XposedLog.log(
                                    "FULLREQ url=$url\nrequest=$requestFields\n" +
                                        "extra=$extraFields\nhttp=$httpFields\nbody=$bodyFields"
                                )
                            }
                        }
                    }
                }
            )
            XposedLog.log("Fanqie network diagnostics installed")
        }.onFailure { XposedLog.log("Fanqie diagnostics failed: $it") }
    }

    private fun patchGuestContext(context: Context?) {
        val current = session ?: return
        if (context == null) return
        try {
            if (context.packageName != current.record.sourcePackage) return
            val base = if (context is android.content.ContextWrapper) context.baseContext else context
            val resolver = getObjectField(base, "mContentResolver")
            if (resolver != null) {
                setObjectField(resolver, "mPackageName", current.host.packageName)
            }
            setObjectField(base, "mOpPackageName", current.host.packageName)
            runCatching {
                setObjectField(base, "mAttributionSource", current.host.attributionSource)
            }
        } catch (t: Throwable) {
            XposedLog.log("patchGuestContext failed: $t")
        }
    }

    private fun hookActivityAttach() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                patchGuestContext(param.args.filterIsInstance<Context>().firstOrNull())
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "attach", hook)
        }.onFailure { XposedLog.log("hook Activity.attach failed: $it") }
    }

    private fun hookStartActivity() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val args = param.args
                val intentIndex = args.indexOfFirst { it is Intent }
                if (intentIndex < 0) return
                val intent = args[intentIndex] as? Intent ?: return
                if (current.isStubIntent(intent)) return
                if (!current.isGuestTarget(intent)) return
                val requestedClass = intent.component?.className
                    ?: current.record.launcherActivity
                    ?: return
                val targetClass = current.resolveActivityClass(requestedClass) ?: return
                if (!current.record.activities.containsKey(requestedClass) &&
                    !current.record.activities.containsKey(targetClass)
                ) {
                    XposedLog.log("未声明的 guest Activity，使用默认配置: $targetClass")
                }
                val fromActivity = args.any { it is Activity }
                val targetSlot = current.targetSlotForActivity(targetClass)
                val stub = current.stubIntentFor(intent, requestedClass, fromActivity, targetSlot)
                args[intentIndex] = stub
                // ActivityTaskManager verifies that the attribution package belongs to the
                // calling UID. Replace the guest context/package with the host identity.
                val contextIndex = args.indexOfFirst { it is Context }
                if (contextIndex >= 0 && args[contextIndex] !is Activity) {
                    // Keep a Guest Activity as the result receiver. Its Context identity hooks
                    // already report the host package; replacing it with the Application would
                    // silently drop onActivityResult callbacks (e.g. login/captcha flows).
                    args[contextIndex] = current.host
                }
                for (i in args.indices) {
                    if (args[i] is String && args[i] == current.record.sourcePackage) {
                        args[i] = current.host.packageName
                    }
                }
                XposedLog.log(
                    "startActivity 路由: ${current.record.sourcePackage}/$targetClass -> " +
                        "${stub.component?.className}, process=" +
                        "${current.activityProcessName(targetClass) ?: current.record.sourcePackage}, " +
                        "slot=$targetSlot"
                )
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Instrumentation::class.java, "execStartActivity", hook)
        }.onFailure { XposedLog.log("hook execStartActivity failed: $it") }
    }

    /**
     * 修复 Guest Activity 拉起下一个 Activity 时被系统拒绝：
     *
     * ```
     * W ActivityTaskManager: Permission Denial: package=com.phoenix.read
     *     does not belong to uid=10585
     * ```
     *
     * Instrumentation.execStartActivity 会把 `who.getOpPackageName()` 作为
     * callingPackage 经 IActivityTaskManager 发给系统（Android 16 framework.jar
     * 反汇编确认，method@8fe4）。Guest 的 Context 必须对应用报告 Guest 包名
     * （否则包名视图自相矛盾），但内核 UID 仍是宿主，服务端归属校验必然失败，
     * 整个 startActivity 被丢弃 —— 表现为启动页之后直接回桌面。
     *
     * 修法：只改写 binder 调用参数，把 callingPackage 换回宿主。
     * 应用可见的 getOpPackageName 仍是 Guest 包名，不受影响。
     */
    private fun hookActivityTaskManagerAttribution() {
        val proxyClass = runCatching {
            Class.forName("android.app.IActivityTaskManager\$Stub\$Proxy")
        }.getOrNull() ?: return
        val logged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val args = param.args ?: return
                var changed = false
                for (i in args.indices) {
                    if (args[i] == current.record.sourcePackage) {
                        args[i] = current.host.packageName
                        changed = true
                    }
                }
                if (changed && logged.add(param.method?.name.orEmpty())) {
                    XposedLog.log(
                        "ActivityTaskManager attribution rewritten: " +
                            "${param.method?.name} -> ${current.host.packageName}"
                    )
                }
            }
        }
        var installed = 0
        for (method in proxyClass.declaredMethods) {
            if (!method.name.startsWith("startActivity")) continue
            installed += runCatching {
                XposedBridge.hookMethod(method, hook)
                1
            }.getOrDefault(0)
        }
        XposedLog.log("ActivityTaskManager attribution hooks installed=$installed")
    }

    private fun hookPerformLaunchActivity() {
        val activityThread = runCatching { Class.forName("android.app.ActivityThread") }.getOrNull()
            ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val recorded = param.args.getOrNull(0) ?: return
                val stubIntent = getObjectField(recorded, "intent") as? Intent ?: return
                val host = hostApplication ?: return
                val hostPackage = host.packageName
                val component = stubIntent.component ?: return
                if (component.packageName != hostPackage ||
                    !component.className.startsWith(GuestSession.STUB_PACKAGE_PREFIX) ||
                    !stubIntent.hasExtra(GuestSession.EXTRA_GUEST_ID)
                ) {
                    return
                }
                val guestId = stubIntent.getStringExtra(GuestSession.EXTRA_GUEST_ID) ?: return
                val guestClass = stubIntent.getStringExtra(GuestSession.EXTRA_GUEST_CLASS) ?: return
                val guestComponent =
                    stubIntent.getStringExtra(GuestSession.EXTRA_GUEST_COMPONENT)
                val current = ensureSession(host, guestId, stubIntent) ?: return
                // Load modules before Instrumentation.newActivity() so constructor hooks on the
                // launcher Activity are installed in time (legacy Xposed loads even earlier).
                current.loadModulesOnce(null)
                current.ensureApplication()
                setObjectField(recorded, "packageInfo", current.loadedApk)
                setObjectField(
                    recorded,
                    "activityInfo",
                    current.activityInfoFor(guestComponent ?: guestClass, resolveAlias = false)
                )
                val guestIntent = current.guestIntentFrom(stubIntent, guestClass, guestComponent)
                setObjectField(recorded, "intent", guestIntent)
                if (param.args.size > 1 && param.args[1] is Intent) {
                    param.args[1] = guestIntent
                }
                XposedLog.log(
                    "启动 guest Activity: ${current.record.sourcePackage}/$guestClass" +
                        (guestComponent?.let { " alias=$it" } ?: "")
                )
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(activityThread, "performLaunchActivity", hook)
        }.onFailure { XposedLog.log("hook performLaunchActivity failed: $it") }
    }

    private fun hookNewActivity() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val className = param.args.getOrNull(1) as? String ?: return
                val target = current.record.activities[className]?.targetActivity
                    ?.takeIf { it.isNotBlank() } ?: return
                param.args[1] = target
                XposedLog.log("Activity alias instantiated: $className -> $target")
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Instrumentation::class.java, "newActivity", hook)
        }.onFailure { XposedLog.log("hook Instrumentation.newActivity failed: $it") }
    }

    private fun hookApplicationLifecycle() {
        val newApplicationHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val app = param.result as? Application ?: return
                val context = param.args.filterIsInstance<Context>().lastOrNull()
                val samePackage = context?.packageName == current.record.sourcePackage
                val sameLoader = app.javaClass.classLoader === current.classLoader
                if (samePackage || sameLoader) {
                    current.guestApplication = app
                    XposedLog.log("创建 guest Application: ${app.javaClass.name}")
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Instrumentation::class.java, "newApplication", newApplicationHook)
        }.onFailure { XposedLog.log("hook newApplication failed: $it") }

        val onCreateHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val app = param.args.getOrNull(0) as? Application ?: return
                if (current.guestApplication === app ||
                    (app.packageName == current.record.sourcePackage &&
                        app.javaClass.classLoader === current.classLoader)
                ) {
                    current.guestApplication = app
                    patchGuestContext(app.baseContext)
                    current.loadModulesOnce(app)
                    // Providers are installed before Application.onCreate in real Android.
                    current.installProvidersOnce(app)
                    // 系统服务的 attribution 兜底必须在 Guest Application 就绪后安装：
                    // 需要 Guest 与宿主两侧的服务实例来比对。
                    runCatching { hookAttributionGuardedServices() }
                        .onFailure { XposedLog.log("hookAttributionGuardedServices failed: $it") }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val app = param.args.getOrNull(0) as? Application ?: return
                if (current.guestApplication === app ||
                    (app.packageName == current.record.sourcePackage &&
                        app.javaClass.classLoader === current.classLoader)
                ) {
                    current.installStaticReceiversOnce(app)
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(
                Instrumentation::class.java, "callApplicationOnCreate", onCreateHook
            )
        }.onFailure { XposedLog.log("hook callApplicationOnCreate failed: $it") }
    }

    private fun hookServices() {
        val contextImpl = runCatching { hostApplication?.baseContext?.javaClass }.getOrNull()
            ?: run {
                XposedLog.log("hookServices: ContextImpl not found")
                return
            }
        val startHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val intent = param.args.filterIsInstance<Intent>().firstOrNull() ?: return
                val className = current.resolveServiceClass(intent) ?: return
                val targetSlot = current.targetSlotForService(className)
                if (targetSlot != current.slot) {
                    val stub = current.stubServiceIntentFor(intent, className, targetSlot)
                    try {
                        Hooker.beginBypass()
                        current.host.startService(stub)
                        param.setResult(ComponentName(current.record.sourcePackage, className))
                        XposedLog.log("跨进程 startService: $className -> slot=$targetSlot")
                    } catch (t: Throwable) {
                        XposedLog.log("跨进程 startService 失败: $className $t")
                        XposedLog.log(t)
                    } finally {
                        Hooker.endBypass()
                    }
                    return
                }
                val component = current.startGuestService(intent) ?: return
                param.setResult(component)
                XposedLog.log("Guest service started locally: ${component.className}")
            }
        }
        var installed = 0
        installed += runCatching { XposedBridge.hookAllMethods(contextImpl, "startService", startHook).size }.getOrDefault(0)
        installed += runCatching { XposedBridge.hookAllMethods(contextImpl, "startForegroundService", startHook).size }.getOrDefault(0)

        val bindHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val intent = param.args.filterIsInstance<Intent>().firstOrNull() ?: return
                val connection = param.args.filterIsInstance<android.content.ServiceConnection>().firstOrNull() ?: return
                val className = current.resolveServiceClass(intent) ?: return
                val targetSlot = current.targetSlotForService(className)
                if (targetSlot != current.slot) {
                    val stub = current.stubServiceIntentFor(intent, className, targetSlot)
                    val flags = param.args.filterIsInstance<Int>().firstOrNull()
                        ?: Context.BIND_AUTO_CREATE
                    try {
                        Hooker.beginBypass()
                        val bound = current.host.bindService(stub, connection, flags)
                        if (bound) remoteBindings.add(connection)
                        param.setResult(bound)
                        XposedLog.log("跨进程 bindService: $className -> slot=$targetSlot result=$bound")
                    } catch (t: Throwable) {
                        XposedLog.log("跨进程 bindService 失败: $className $t")
                        XposedLog.log(t)
                    } finally {
                        Hooker.endBypass()
                    }
                    return
                }
                if (current.bindGuestService(intent, connection)) {
                    param.setResult(true)
                }
            }
        }
        installed += runCatching { XposedBridge.hookAllMethods(contextImpl, "bindService", bindHook).size }.getOrDefault(0)

        val unbindHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val connection = param.args.filterIsInstance<android.content.ServiceConnection>().firstOrNull()
                    ?: return
                if (remoteBindings.remove(connection)) {
                    try {
                        Hooker.beginBypass()
                        current.host.unbindService(connection)
                        XposedLog.log("跨进程 unbindService")
                    } catch (t: Throwable) {
                        XposedLog.log("跨进程 unbindService 失败: $t")
                    } finally {
                        Hooker.endBypass()
                    }
                    param.setResult(null)
                    return
                }
                current.unbindGuestService(connection)
                param.setResult(null)
            }
        }
        installed += runCatching { XposedBridge.hookAllMethods(contextImpl, "unbindService", unbindHook).size }.getOrDefault(0)

        val stopHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val intent = param.args.filterIsInstance<Intent>().firstOrNull() ?: return
                val className = current.resolveServiceClass(intent) ?: return
                val targetSlot = current.targetSlotForService(className)
                if (targetSlot != current.slot) {
                    val stub = current.stubServiceIntentFor(intent, className, targetSlot)
                    try {
                        Hooker.beginBypass()
                        current.host.stopService(stub)
                        param.setResult(true)
                        XposedLog.log("跨进程 stopService: $className -> slot=$targetSlot")
                    } catch (t: Throwable) {
                        XposedLog.log("跨进程 stopService 失败: $className $t")
                    } finally {
                        Hooker.endBypass()
                    }
                    return
                }
                if (current.stopGuestService(intent)) {
                    param.setResult(true)
                }
            }
        }
        installed += runCatching { XposedBridge.hookAllMethods(contextImpl, "stopService", stopHook).size }.getOrDefault(0)

        val startForegroundHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val service = param.thisObject as? android.app.Service ?: return
                if (!current.isGuestService(service)) return
                val id = param.args.firstOrNull { it is Int } as? Int ?: 0
                val notification = param.args.firstOrNull { it is android.app.Notification }
                    as? android.app.Notification
                if (notification != null) {
                    current.virtualizeStartForeground(service, id, notification)
                }
                param.setResult(null)
            }
        }
        installed += runCatching {
            XposedBridge.hookAllMethods(
                android.app.Service::class.java, "startForeground", startForegroundHook
            ).size
        }.getOrDefault(0)

        val stopForegroundHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val service = param.thisObject as? android.app.Service ?: return
                if (!current.isGuestService(service)) return
                val remove = when (val arg = param.args.getOrNull(0)) {
                    is Boolean -> arg
                    is Int -> arg and android.app.Service.STOP_FOREGROUND_REMOVE != 0
                    else -> true
                }
                current.virtualizeStopForeground(service, remove)
                param.setResult(null)
            }
        }
        for (name in listOf("stopForeground", "stopForegroundRemove")) {
            installed += runCatching {
                XposedBridge.hookAllMethods(
                    android.app.Service::class.java, name, stopForegroundHook
                ).size
            }.getOrDefault(0)
        }

        val stopSelfHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val service = param.thisObject as? android.app.Service ?: return
                if (!current.isGuestService(service)) return
                current.requestStopGuestService(service)
                val method = param.method as? java.lang.reflect.Method
                param.setResult(
                    if (method?.returnType == Boolean::class.javaPrimitiveType) true else null
                )
            }
        }
        for (name in listOf("stopSelf", "stopSelfResult")) {
            installed += runCatching {
                XposedBridge.hookAllMethods(
                    android.app.Service::class.java, name, stopSelfHook
                ).size
            }.getOrDefault(0)
        }
        XposedLog.log("Guest service hooks installed=$installed")
    }

    private fun hookBroadcasts() {
        val contextImpl = runCatching { hostApplication?.baseContext?.javaClass }.getOrNull()
            ?: return
        val registerHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val context = param.thisObject as? Context ?: return
                if (context.packageName != current.record.sourcePackage) return
                val receiver = param.args.filterIsInstance<android.content.BroadcastReceiver>().firstOrNull()
                val filter = param.args.filterIsInstance<android.content.IntentFilter>().firstOrNull()
                    ?: return
                val sticky = try {
                    Hooker.beginBypass()
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        current.host.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        current.host.registerReceiver(receiver, filter)
                    }
                } finally {
                    Hooker.endBypass()
                }
                if (receiver != null) current.markReceiverRegistered(receiver, filter)
                param.setResult(sticky)
            }
        }
        runCatching { XposedBridge.hookAllMethods(contextImpl, "registerReceiver", registerHook) }

        val unregisterHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val receiver = param.args.filterIsInstance<android.content.BroadcastReceiver>().firstOrNull()
                if (!current.isGuestReceiver(receiver)) return
                current.unmarkReceiver(receiver)
                try {
                    Hooker.beginBypass()
                    var clazz: Class<*>? = current.host.javaClass
                    while (clazz != null) {
                        try {
                            clazz.getMethod("unregisterReceiver", android.content.BroadcastReceiver::class.java)
                                .invoke(current.host, receiver)
                            break
                        } catch (_: NoSuchMethodException) {
                            clazz = clazz.superclass
                        }
                    }
                } finally {
                    Hooker.endBypass()
                }
                param.setResult(null)
            }
        }
        runCatching { XposedBridge.hookAllMethods(contextImpl, "unregisterReceiver", unregisterHook) }

        val sendHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val context = param.thisObject as? Context ?: return
                if (context.packageName != current.record.sourcePackage) return
                try {
                    val target = current.host.baseContext ?: current.host
                    val method = param.method as? java.lang.reflect.Method ?: return
                    val intent = param.args.filterIsInstance<Intent>().firstOrNull()
                    if (intent != null && current.hasGuestAction(intent.action)) {
                        intent.setPackage(current.host.packageName)
                    }
                    Hooker.beginBypass()
                    method.invoke(target, *param.args)
                    param.setResult(null)
                } catch (t: Throwable) {
                    XposedLog.log("Guest broadcast send failed: ${t.cause ?: t}")
                    XposedLog.log(t.cause ?: t)
                } finally {
                    Hooker.endBypass()
                }
            }
        }
        for (name in listOf("sendBroadcast", "sendBroadcastAsUser", "sendOrderedBroadcast", "sendOrderedBroadcastAsUser")) {
            runCatching { XposedBridge.hookAllMethods(contextImpl, name, sendHook) }
        }
        XposedLog.log("Guest broadcast hooks installed")
    }

    private fun hookActivityResults() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != current.record.sourcePackage) return
                val request = param.args.getOrNull(0) as? Int ?: 0
                val result = param.args.getOrNull(1) as? Int ?: 0
                XposedLog.log(
                    "Guest Activity result: ${activity.javaClass.simpleName} " +
                        "req=$request res=$result"
                )
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onActivityResult", hook)
        }.onFailure { XposedLog.log("hook onActivityResult failed: $it") }
    }

    /**
     * 系统把 Guest Manifest 里的静态 Receiver 直接分发给容器进程时，
     * ActivityThread 用的是宿主 LoadedApk 的 classloader，找不到 Guest 类
     * （实测红果 t41.c → ClassNotFoundException → 进程被杀 + “VT 反复停止”）。
     * 在 AppComponentFactory 实例化前用 Guest classloader 接管；
     * 类确实不存在时返回空接收器，宁可丢一次广播也不杀进程。
     */
    private fun hookReceiverInstantiation() {
        val noop = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = Unit
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val loader = param.args.getOrNull(0) as? ClassLoader ?: return
                val className = param.args.getOrNull(1) as? String ?: return
                if (runCatching { loader.loadClass(className) }.isSuccess) return
                val guestLoader = session?.classLoader
                val receiver = guestLoader?.let {
                    runCatching {
                        it.loadClass(className).getDeclaredConstructor().newInstance()
                            as android.content.BroadcastReceiver
                    }.getOrNull()
                }
                if (receiver != null) {
                    param.setResult(receiver)
                    XposedLog.log("receiver via guest loader: $className")
                } else {
                    param.setResult(noop)
                    XposedLog.log("receiver dropped (not in guest loader): $className")
                }
            }
        }
        var installed = 0
        for (name in listOf("android.app.AppComponentFactory", "androidx.core.app.CoreComponentFactory")) {
            val cls = runCatching { Class.forName(name) }.getOrNull() ?: continue
            installed += runCatching {
                XposedBridge.hookAllMethods(cls, "instantiateReceiver", hook).size
            }.getOrDefault(0)
        }
        XposedLog.log("receiver instantiation hooks installed=$installed")
    }

    private fun hookOnNewIntent() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val intent = param.args.getOrNull(0) as? Intent ?: return
                if (!current.isStubIntent(intent)) return
                val guestClass = intent.getStringExtra(GuestSession.EXTRA_GUEST_CLASS) ?: return
                param.args[0] = current.guestIntentFrom(intent, guestClass)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", hook)
        }.onFailure { XposedLog.log("hook onNewIntent failed: $it") }
    }

    private fun hookActivityManagerExtras() {
        val amClass = runCatching { Class.forName("android.app.ActivityManager") }
            .getOrNull() ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val method = param.method as? java.lang.reflect.Method ?: return
                if (!method.name.contains("ProcessExitReason")) return
                val pkg = param.args.filterIsInstance<String>().firstOrNull()
                if (pkg == null || pkg == current.record.sourcePackage) {
                    param.setResult(emptyList<Any>())
                    XposedLog.log("ActivityManager.${method.name} shimmed for guest")
                }
            }
        }
        var installed = 0
        for (name in listOf(
            "getHistoricalProcessExitReasons", "getHistoricalProcessExitReasonsForUser",
            "getProcessExitReason", "getProcessExitReasons"
        )) {
            installed += runCatching {
                XposedBridge.hookAllMethods(amClass, name, hook).size
            }.getOrDefault(0)
        }
        XposedLog.log("ActivityManager extras shims installed=$installed")
    }

    private fun hookActivityManagerIdentity() {
        val amClass = runCatching { Class.forName("android.app.ActivityManager") }
            .getOrNull() ?: return
        val runningHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                @Suppress("UNCHECKED_CAST")
                val list = param.result as? List<ActivityManager.RunningAppProcessInfo>
                    ?: return
                val filtered = list.filter {
                    it.pid != Process.myPid() &&
                        !it.processName.startsWith(current.host.packageName)
                }
                val info = ActivityManager.RunningAppProcessInfo()
                info.processName = current.guestProcessName
                info.pid = Process.myPid()
                info.uid = Process.myUid()
                info.pkgList = arrayOf(current.record.sourcePackage)
                param.result = ArrayList(filtered).apply { add(0, info) }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(amClass, "getRunningAppProcesses", runningHook)
        }
        XposedLog.log("ActivityManager running-process identity installed")
    }

    private fun hookRuntimePermissions() {
        val requestHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != current.record.sourcePackage) return
                val raw = param.args.getOrNull(0) as? Array<*> ?: return
                val requestCode = param.args.getOrNull(1) as? Int ?: return
                val permissions = raw.mapNotNull { it as? String }.toTypedArray()
                if (permissions.isEmpty()) return
                // The process runs as the host UID, so host grants are the real capability set.
                val results = IntArray(permissions.size) { index ->
                    if (current.host.checkSelfPermission(permissions[index]) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        PackageManager.PERMISSION_GRANTED
                    } else {
                        PackageManager.PERMISSION_DENIED
                    }
                }
                val ref = java.lang.ref.WeakReference(activity)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    ref.get()?.onRequestPermissionsResult(requestCode, permissions, results)
                }
                XposedLog.log(
                    "Guest requestPermissions shimmed: ${permissions.joinToString()} " +
                        "granted=${results.count { it == PackageManager.PERMISSION_GRANTED }}"
                )
                param.setResult(null)
            }
        }
        var installed = 0
        installed += runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "requestPermissions", requestHook)
                .size
        }.getOrDefault(0)

        val rationaleHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName == current.record.sourcePackage) {
                    param.result = false
                }
            }
        }
        for (name in listOf(
            "shouldShowRequestPermissionRationale", "isPermissionRevokedByPolicy"
        )) {
            installed += runCatching {
                XposedBridge.hookAllMethods(Activity::class.java, name, rationaleHook).size
            }.getOrDefault(0)
        }
        XposedLog.log("Runtime permission shims installed=$installed")
    }

    private fun hookContextIdentity() {
        val contextImpl = runCatching { hostApplication?.baseContext?.javaClass }.getOrNull()
            ?: return
        val opPackage = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val context = param.thisObject as? Context ?: return
                if (context.packageName == current.record.sourcePackage) {
                    // Must stay the guest package: ActivityThread.currentOpPackageName
                    // already reports it, and a host package name here makes the guest
                    // look up a package that is hidden from its PackageManager view.
                    param.result = current.record.sourcePackage
                }
            }
        }
        for (name in listOf("getOpPackageName", "getBasePackageName")) {
            runCatching { XposedBridge.hookAllMethods(contextImpl, name, opPackage) }
        }
        val codePath = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val context = param.thisObject as? Context ?: return
                if (context.packageName != current.record.sourcePackage) return
                // 优先真机安装路径；真机路径失效时用同形态的占位路径。
                // 不能 return 让框架回落到宿主 APK 路径——那是更明显的容器特征。
                val real = current.record.installedSourceDir?.takeIf { it.isNotBlank() }
                    ?: syntheticInstalledSourceDir(current.record.sourcePackage)
                param.result = real
            }
        }
        for (name in listOf("getPackageCodePath", "getPackageResourcePath")) {
            runCatching { XposedBridge.hookAllMethods(contextImpl, name, codePath) }
        }
        val resolverHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val context = param.thisObject as? Context ?: return
                patchGuestContext(context)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(contextImpl, "getContentResolver", resolverHook)
        }
        val contentResolverClass = runCatching { Class.forName("android.content.ContentResolver") }
            .getOrNull()
        if (contentResolverClass != null) {
            val resolverCtor = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val current = session ?: return
                    val context = getObjectField(param.thisObject, "mContext") as? Context ?: return
                    if (context.packageName == current.record.sourcePackage) {
                        try {
                            setObjectField(param.thisObject, "mPackageName", current.host.packageName)
                        } catch (t: Throwable) {
                            XposedLog.log("patch ContentResolver package failed: $t")
                        }
                    }
                }
            }
            runCatching { XposedBridge.hookAllConstructors(contentResolverClass, resolverCtor) }
        }
        XposedLog.log("ContextImpl identity hooks installed")
    }

    private fun hookExternalStorage() {
        val contextImpl = runCatching { hostApplication?.baseContext?.javaClass }.getOrNull()
            ?: return
        var logged = 0
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val context = param.thisObject as? Context ?: return
                if (context.packageName != current.record.sourcePackage) {
                    if (logged < 20) {
                        logged++
                        XposedLog.log(
                            "external miss: ${param.method.name} ctx=${context.packageName}"
                        )
                    }
                    return
                }
                val name = param.method.name
                val root = File(current.record.dataDir, "external")
                val one = when {
                    name == "getExternalCacheDir" -> File(root, "cache")
                    name == "getExternalFilesDir" -> {
                        val type = param.args.getOrNull(0) as? String
                        if (type.isNullOrBlank()) File(root, "files") else File(root, "files/$type")
                    }
                    // getObbDirs 之前没有对应分支，会落到 else -> return，
                    // 于是返回的是宿主真实路径，直接暴露容器结构。
                    name == "getObbDir" || name == "getObbDirs" -> File(root, "obb")
                    name == "getExternalMediaDirs" || name == "getExternalCacheDirs" ||
                        name == "getExternalFilesDirs" -> {
                        val type = param.args.getOrNull(0) as? String
                        if (name == "getExternalCacheDirs") File(root, "cache")
                        else if (type.isNullOrBlank()) File(root, "files") else File(root, "files/$type")
                    }
                    else -> return
                }
                one.mkdirs()
                if (logged < 20) {
                    logged++
                    XposedLog.log("external hit: $name ctx=${context.packageName} -> $one")
                }
                val returnType = (param.method as? java.lang.reflect.Method)?.returnType
                if (returnType != null && returnType.isArray) {
                    param.setResult(arrayOf(one))
                } else {
                    param.setResult(one)
                }
            }
        }
        for (name in listOf(
            "getExternalCacheDir", "getExternalCacheDirs",
            "getExternalFilesDir", "getExternalFilesDirs",
            "getObbDir", "getObbDirs", "getExternalMediaDirs"
        )) {
            runCatching { XposedBridge.hookAllMethods(contextImpl, name, hook) }
        }
        XposedLog.log("external storage virtualization installed")
    }

    private fun hookStorageManager() {
        val smClass = runCatching { Class.forName("android.os.storage.StorageManager") }
            .getOrNull() ?: return
        var storageLogs = 0
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                if (session == null) return
                val method = param.method as? java.lang.reflect.Method ?: return
                val hostManager = hostApplication?.getSystemService(
                    android.os.storage.StorageManager::class.java
                ) ?: return
                try {
                    Hooker.beginBypass()
                    method.isAccessible = true
                    val result = method.invoke(hostManager, *param.args)
                    if (storageLogs < 6) {
                        storageLogs++
                        XposedLog.log("StorageManager.${method.name} -> $result")
                    }
                    param.setResult(result)
                } catch (t: Throwable) {
                    XposedLog.log("StorageManager.${method.name} shim: ${t.cause ?: t}")
                    param.setResult(zeroValue(method.returnType))
                } finally {
                    Hooker.endBypass()
                }
            }
        }
        var installed = 0
        for (name in listOf(
            "getVolumeList", "getStorageVolumes", "getStorageVolume", "getPrimaryStorageVolume"
        )) {
            installed += runCatching {
                XposedBridge.hookAllMethods(smClass, name, hook).size
            }.getOrDefault(0)
        }
        XposedLog.log("StorageManager shims installed=$installed")
    }

    private fun hookEnvironmentStorage() {
        val envClass = runCatching { Class.forName("android.os.Environment") }.getOrNull()
            ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                if (session == null) return
                val method = param.method as? java.lang.reflect.Method ?: return
                val path = param.args.filterIsInstance<File>().firstOrNull()?.absolutePath
                when (method.name) {
                    "isExternalStorageEmulated" ->
                        if (path == null || path.startsWith("/storage/emulated") ||
                            path.startsWith("/storage/self")
                        ) {
                            param.setResult(true)
                        }
                    "isExternalStorageRemovable" ->
                        if (path == null || path.startsWith("/storage/emulated") ||
                            path.startsWith("/storage/self")
                        ) {
                            param.setResult(false)
                        }
                }
            }
        }
        var installed = 0
        for (name in listOf("isExternalStorageEmulated", "isExternalStorageRemovable")) {
            installed += runCatching {
                XposedBridge.hookAllMethods(envClass, name, hook).size
            }.getOrDefault(0)
        }
        XposedLog.log("Environment storage shims installed=$installed")
    }

    private fun hookFileConstructorFallback() {
        var logs = 0
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                if (param.args.none { it == null }) return
                val constructor = param.method as? java.lang.reflect.Constructor<*> ?: return
                val types = constructor.parameterTypes
                val fallbackRoot = File(current.record.dataDir, "files/vt_fallback")
                    .apply { mkdirs() }
                val fallback = fallbackRoot.absolutePath
                var changed = false
                for (i in param.args.indices) {
                    if (param.args[i] != null) continue
                    val type = types.getOrNull(i)
                    param.args[i] = when {
                        type == String::class.java && types.size == 1 -> fallback
                        type == String::class.java && i == 0 -> fallback
                        type == String::class.java -> "vt_fallback"
                        type == File::class.java -> fallbackRoot
                        else -> continue
                    }
                    changed = true
                }
                if (changed && logs < 8) {
                    logs++
                    XposedLog.log("File null path substituted: ${constructor.toGenericString()}")
                    XposedLog.log(Throwable("null File path"))
                }
            }
        }
        val count = runCatching {
            XposedBridge.hookAllConstructors(java.io.File::class.java, hook).size
        }.getOrDefault(0)
        XposedLog.log("File constructor fallback hooks installed=$count")
    }

    private fun hookSystemServiceShims() {
        val pmClass = runCatching { Class.forName("android.app.ApplicationPackageManager") }.getOrNull()
        if (pmClass != null) {
            val disableHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val current = session ?: return
                    val component = param.args.getOrNull(0) as? ComponentName ?: return
                    if (component.packageName == current.record.sourcePackage) {
                        param.setResult(null)
                    }
                }
            }
            for (name in listOf("setComponentEnabledSetting", "setApplicationEnabledSetting")) {
                runCatching { XposedBridge.hookAllMethods(pmClass, name, disableHook) }
            }
            val enabledHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val current = session ?: return
                    val component = param.args.getOrNull(0) as? ComponentName ?: return
                    if (component.packageName == current.record.sourcePackage) {
                        param.result = 0 // COMPONENT_ENABLED_STATE_DEFAULT
                    } else if (param.args.getOrNull(0) is String &&
                        param.args[0] == current.record.sourcePackage) {
                        param.result = 0
                    }
                }
            }
            for (name in listOf("getComponentEnabledSetting", "getApplicationEnabledSetting")) {
                runCatching { XposedBridge.hookAllMethods(pmClass, name, enabledHook) }
            }
        }

        val schedulerClasses = LinkedHashSet<Class<*>>()
        runCatching { Class.forName("android.app.job.JobScheduler") }
            .getOrNull()?.let(schedulerClasses::add)
        hostApplication?.getSystemService(Context.JOB_SCHEDULER_SERVICE)?.javaClass
            ?.let(schedulerClasses::add)
        if (schedulerClasses.isNotEmpty()) {
            val jobHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (session == null) return
                    val method = param.method as? java.lang.reflect.Method ?: return
                    val successInt = method.name == "schedule" || method.name == "enqueue" ||
                        method.name == "scheduleAsPackage"
                    param.setResult(zeroValue(method.returnType, successInt))
                }
            }
            var installedJobs = 0
            for (clazz in schedulerClasses) {
                for (name in listOf(
                    "schedule", "scheduleAsPackage", "enqueue",
                    "cancel", "cancelAll", "getAllPendingJobs", "getPendingJob",
                    "getPendingJobReasons", "getPendingJobReason", "getStartedJobs",
                    "getAllJobSnapshots"
                )) {
                    installedJobs += runCatching {
                        XposedBridge.hookAllMethods(clazz, name, jobHook).size
                    }.getOrDefault(0)
                }
            }
            XposedLog.log(
                "JobScheduler shims installed=$installedJobs classes=" +
                    schedulerClasses.joinToString { it.name }
            )
        }
        XposedLog.log("system service shims installed")
    }

    private fun hookNotificationManager() {
        val nmClass = runCatching { Class.forName("android.app.NotificationManager") }
            .getOrNull() ?: return
        val redirect = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val manager = param.thisObject ?: return
                val context = getObjectField(manager, "mContext") as? Context ?: return
                if (context.packageName != current.record.sourcePackage) return
                val method = param.method as? java.lang.reflect.Method ?: return
                val hostManager = current.host.getSystemService(Context.NOTIFICATION_SERVICE)
                    ?: return
                for (i in param.args.indices) {
                    if (param.args[i] is String &&
                        param.args[i] == current.record.sourcePackage
                    ) {
                        param.args[i] = current.host.packageName
                    }
                }
                try {
                    Hooker.beginBypass()
                    param.setResult(method.invoke(hostManager, *param.args))
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    XposedLog.log("NotificationManager.${method.name} shim: $cause")
                    param.setResult(zeroValue(method.returnType))
                } finally {
                    Hooker.endBypass()
                }
            }
        }
        var installed = 0
        for (method in nmClass.declaredMethods) {
            if (!java.lang.reflect.Modifier.isPublic(method.modifiers) ||
                java.lang.reflect.Modifier.isStatic(method.modifiers) ||
                method.isSynthetic || method.isBridge
            ) {
                continue
            }
            installed += runCatching {
                XposedBridge.hookMethod(method, redirect)
                1
            }.getOrDefault(0)
        }
        XposedLog.log("NotificationManager identity shims installed=$installed")
    }

    private fun hookToast() {
        val tnClass = runCatching { Class.forName("android.widget.Toast\$TN") }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllConstructors(
                tnClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val current = session ?: return
                        // Toast builds its TN with context.getPackageName(); the window
                        // session rejects that guest package because the process UID
                        // belongs to the host. ToastPresenter derives the window package
                        // from the same context, so both must use host identity.
                        for (i in param.args.indices) {
                            val arg = param.args[i]
                            if (arg is String && arg == current.record.sourcePackage) {
                                param.args[i] = current.host.packageName
                            } else if (arg is Context && arg.packageName == current.record.sourcePackage) {
                                param.args[i] = current.host
                            }
                        }
                    }
                }
            )
            XposedLog.log("Toast identity shim installed")
        }.onFailure { XposedLog.log("Toast identity shim failed: $it") }

        // Toast.show() 会把 mContext.getOpPackageName() 作为 callingPackage 直接发给
        // NotificationManagerService.enqueueToast()；Guest 的 Context 报告 Guest 包名，
        // 服务端 checkCallerIsSameApp 会抛
        //   SecurityException: Package com.x.y is not owned by uid <host>
        // 并直接杀掉 Guest 进程（实测番茄/红果播放失败提示 Toast 时崩溃）。
        // TN 构造参数改写管不到这条路径，必须在 binder proxy 上改写 callingPackage。
        val proxyClass = runCatching {
            Class.forName("android.app.INotificationManager\$Stub\$Proxy")
        }.getOrNull() ?: return
        val proxyHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val args = param.args ?: return
                var changed = false
                for (i in args.indices) {
                    if (args[i] == current.record.sourcePackage) {
                        args[i] = current.host.packageName
                        changed = true
                    }
                }
                if (changed && toastProxyLogged.add(param.method?.name.orEmpty())) {
                    XposedLog.log(
                        "Toast callingPackage rewritten: ${param.method?.name} -> " +
                            current.host.packageName
                    )
                }
            }
        }
        var proxyHooks = 0
        for (method in proxyClass.declaredMethods) {
            if (!method.name.contains("Toast")) continue
            proxyHooks += runCatching {
                XposedBridge.hookMethod(method, proxyHook)
                1
            }.getOrDefault(0)
        }
        XposedLog.log("Toast callingPackage proxy hooks installed=$proxyHooks")
    }

    private fun hookPendingIntents() {
        val piClass = runCatching { Class.forName("android.app.PendingIntent") }
            .getOrNull()
        XposedLog.log("hookPendingIntents: class=" + (piClass?.name ?: "null"))
        if (piClass == null) return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session
                val method = param.method as? java.lang.reflect.Method ?: return
                val contextIndex = param.args.indexOfFirst { it is Context }
                val context = if (contextIndex >= 0) param.args[contextIndex] as? Context else null
                if (current == null || contextIndex < 0) return
                if (context?.packageName != current.record.sourcePackage) return
                val activityType = method.name == "getActivity" || method.name == "getActivities"
                for (i in param.args.indices) {
                    when (val value = param.args[i]) {
                        is Intent -> {
                            param.args[i] = rewritePendingIntent(current, value, activityType)
                        }
                        is Array<*> -> if (value.isNotEmpty() && value.all { it is Intent }) {
                            param.args[i] = value.map {
                                rewritePendingIntent(current, it as Intent, activityType)
                            }.toTypedArray()
                        }
                    }
                }
                param.args[contextIndex] = current.host
                for (i in param.args.indices) {
                    if (param.args[i] is String &&
                        param.args[i] == current.record.sourcePackage
                    ) {
                        param.args[i] = current.host.packageName
                    }
                }
                XposedLog.log("PendingIntent.${method.name} remapped to host identity")
            }
        }
        var installed = 0
        for (name in listOf(
            "getActivity", "getActivities", "getBroadcast", "getService", "getForegroundService"
        )) {
            installed += runCatching {
                XposedBridge.hookAllMethods(piClass, name, hook).size
            }.getOrDefault(0)
        }
        XposedLog.log(
            "PendingIntent identity hooks installed=$installed hooked=" +
                listOf("getActivity", "getActivities", "getBroadcast", "getService",
                    "getForegroundService").joinToString { name ->
                    val hooked = piClass.declaredMethods.any {
                        it.name == name && XposedBridge.isMethodHooked(it)
                    }
                    "$name=$hooked"
                }
        )
    }

    private fun hookLocaleManager() {
        val lmClass = runCatching { Class.forName("android.app.LocaleManager") }.getOrNull()
            ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val manager = param.thisObject ?: return
                val context = getObjectField(manager, "mContext") as? Context ?: return
                val method = param.method as? java.lang.reflect.Method ?: return
                if (context.packageName != current.record.sourcePackage) {
                    XposedLog.log(
                        "LocaleManager.${method.name} not guest: ctx=${context.packageName}"
                    )
                    return
                }
                // The locale service requires CHANGE_CONFIGURATION for packages the caller does
                // not own. The guest package is not installed, so per-app locale switching is
                // accepted locally and applied on the next cold start instead.
                when (method.name) {
                    "setApplicationLocales" -> {
                        XposedLog.log("LocaleManager.setApplicationLocales shimmed for guest")
                        param.setResult(null)
                    }
                    "getApplicationLocales" -> {
                        XposedLog.log("LocaleManager.getApplicationLocales shimmed for guest")
                        param.setResult(android.os.LocaleList.getEmptyLocaleList())
                    }
                }
            }
        }
        var installed = 0
        for (name in listOf(
            "setApplicationLocales", "getApplicationLocales", "setOverrideLocaleConfig"
        )) {
            installed += runCatching {
                XposedBridge.hookAllMethods(lmClass, name, hook).size
            }.getOrDefault(0)
        }
        XposedLog.log(
            "LocaleManager shims installed=$installed hooked=" +
                listOf("setApplicationLocales", "getApplicationLocales").joinToString { name ->
                    val hooked = lmClass.declaredMethods.any {
                        it.name == name && XposedBridge.isMethodHooked(it)
                    }
                    "$name=$hooked(${lmClass.declaredMethods.count { it.name == name }})"
                }
        )
    }

    private fun hookMediaSession() {
        val managerClass = runCatching {
            Class.forName("android.media.session.MediaSessionManager")
        }.getOrNull() ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val manager = param.thisObject ?: return
                val context = getObjectField(manager, "mContext") as? Context ?: return
                if (context.packageName != current.record.sourcePackage) return
                // SessionManagerService enforces that the package passed to createSession is
                // owned by the calling UID. Sessions are registered under the host identity.
                setObjectField(manager, "mContext", current.host)
                XposedLog.log("MediaSessionManager.createSession remapped to host identity")
            }
        }
        val installed = runCatching {
            XposedBridge.hookAllMethods(managerClass, "createSession", hook).size
        }.getOrDefault(0)
        XposedLog.log("MediaSessionManager hooks installed=$installed")
    }

    /**
     * 媒体键（耳机/蓝牙/系统音量键的播放暂停）走的是
     * MediaSessionManager.getOpPackageName() → ISessionManager.dispatchMediaKeyEvent()，
     * MediaServerUtils.enforcePackageName 会拿 Guest 包名核对宿主 UID，不符就抛
     * IllegalArgumentException 并杀掉 Guest 进程（实测按一次暂停键即崩）。
     * 这里同样只改写 binder 参数，不改 Context 视图。
     */
    private fun hookMediaKeyAttribution() {
        val proxyClass = runCatching {
            Class.forName("android.media.session.ISessionManager\$Stub\$Proxy")
        }.getOrNull() ?: return
        val logged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val args = param.args ?: return
                var changed = false
                for (i in args.indices) {
                    if (args[i] == current.record.sourcePackage) {
                        args[i] = current.host.packageName
                        changed = true
                    }
                }
                if (changed && logged.add(param.method?.name.orEmpty())) {
                    XposedLog.log(
                        "MediaSession attribution rewritten: ${param.method?.name} -> " +
                            current.host.packageName
                    )
                }
            }
        }
        var installed = 0
        for (method in proxyClass.declaredMethods) {
            if (method.parameterTypes.none { it == String::class.java }) continue
            installed += runCatching {
                XposedBridge.hookMethod(method, hook)
                1
            }.getOrDefault(0)
        }
        XposedLog.log("MediaSession attribution hooks installed=$installed")
    }

    private fun rewritePendingIntent(
        current: GuestSession,
        original: Intent,
        activityType: Boolean
    ): Intent {
        val guest = current.record.sourcePackage
        val component = original.component
        if (activityType && component != null && component.packageName == guest &&
            current.record.activities.containsKey(component.className)
        ) {
            return current.stubIntentFor(original, component.className, false)
        }
        // Non-activity targets stay inside the guest namespace. The system only validates the
        // creator identity at creation time; delivery to an uninstalled package is dropped.
        return Intent(original).apply {
            if (`package` == guest) setPackage(current.host.packageName)
        }
    }

    /**
     * 修复"系统服务按调用方包名做归属校验 → 抛 SecurityException"导致 Guest 崩溃。
     *
     * 背景（真机 Android 16 实测，抖音/番茄/红果全部复现）：
     *
     * ```
     * FATAL EXCEPTION: #TransmitThreadPoolExecutor1
     * Process: com.ss.android.ugc.aweme, PID: 8140
     * java.lang.SecurityException: Package com.ss.android.ugc.aweme does not belong to 10585
     *     at android.net.IConnectivityManager$Stub$Proxy.getNetworkCapabilities(...)
     *     at android.net.ConnectivityManager.getNetworkCapabilities(...)
     *     at com.bytedance.badger.collect.impl.utils.NetworkTypeUtils.getNetworkStatus(...)
     * ```
     *
     * 服务端（AppOpsService.verifyAndGetBypass）的判定逻辑是：
     *
     * ```
     * int callingUid = Binder.getCallingUid();
     * if (resolveNonAppUid(packageName) != UserHandle.getAppId(callingUid))
     *     throw new SecurityException("Specified package \"" + packageName +
     *         "\" under uid " + uid + " but it is not ...");
     * ```
     *
     * `resolveNonAppUid()` 查的是 **PMS 里真实的包归属**。容器 Guest 进程的内核 UID
     * 是宿主 UID（10585），而 Guest 包名在真机 PMS 里要么不存在、要么归属别的 UID，
     * 于是校验必然失败。
     *
     * 注意：这条校验发生在 **服务端**，用的是真实 PMS 数据，
     * 所以客户端侧的 PackageManager 视图修复（本轮 28 项断言）**管不到这里**。
     * 这是容器架构的固有约束，不是 PM 视图回归。
     *
     * 修法：把这类调用**转发给宿主的服务实例**执行。
     * 宿主实例的 attribution 是宿主包名，服务端校验通过；
     * Guest 拿到的是宿主视角的真实结果（网络能力、Telephony 信息等），
     * 这些数据本来就不属于"Guest 私有"，转发的语义是正确的。
     *
     * 覆盖的服务按"是否会因 attribution 校验而抛异常"选择，
     * 不做全量代理 —— 全量代理会改变行为且掩盖真实问题。
     */
    /**
     * 修复"系统服务按调用方包名做归属校验 → 抛 SecurityException"导致 Guest 崩溃。
     *
     * 背景（真机 Android 16 实测，抖音/番茄/红果全部复现）：
     *
     * ```
     * FATAL EXCEPTION: #TransmitThreadPoolExecutor1
     * Process: com.ss.android.ugc.aweme, PID: 8140
     * java.lang.SecurityException: Package com.ss.android.ugc.aweme does not belong to 10585
     *     at android.net.IConnectivityManager$Stub$Proxy.getNetworkCapabilities(...)
     *     at android.net.ConnectivityManager.getNetworkCapabilities(...)
     *     at com.bytedance.badger.collect.impl.utils.NetworkTypeUtils.getNetworkStatus(...)
     * ```
     *
     * 服务端（AppOpsService.verifyAndGetBypass）的判定逻辑是：
     *
     * ```
     * int callingUid = Binder.getCallingUid();
     * if (resolveNonAppUid(packageName) != UserHandle.getAppId(callingUid))
     *     throw new SecurityException("Specified package \"" + packageName +
     *         "\" under uid " + uid + " but it is not ...");
     * ```
     *
     * `resolveNonAppUid()` 查的是 **PMS 里真实的包归属**。容器 Guest 进程的内核 UID
     * 是宿主 UID（10585），而 Guest 包名在真机 PMS 里要么不存在、要么归属别的 UID，
     * 于是校验必然失败。
     *
     * 注意：这条校验发生在 **服务端**，用的是真实 PMS 数据，
     * 所以客户端侧的 PackageManager 视图修复（本轮 28 项断言）**管不到这里**。
     * 这是容器架构的固有约束，不是 PM 视图回归。
     *
     * 修法：客户端调用系统服务时，会把 `AttributionSource` 作为参数传给 binder proxy。
     * 这个 AttributionSource 里的 packageName 就是服务端校验的对象。
     * 把它换成**宿主的** AttributionSource，服务端校验就通过了：
     * 宿主 UID 确实拥有宿主包。
     *
     * 用容器已有的成熟模式（见 hookProviderIdentity 里 acquireProvider 的处理）：
     * 把"代表 Guest 身份"的参数换成"代表宿主身份"的。
     */
    private fun hookAttributionGuardedServices() {
        val current = session ?: return
        val host = hostApplication ?: return
        val guestApp = current.guestApplication ?: return
        // 每个 (服务, 方法) 只记一次日志。
        val logged = HashSet<String>()

        // 服务名 -> 需要转发的方法名。
        // 选取标准：这些方法在服务端会走 attribution/包归属校验，
        // 在容器里必然抛 SecurityException 或返回被拒的结果。
        val targets = mapOf(
            Context.CONNECTIVITY_SERVICE to listOf(
                "getNetworkCapabilities",
                "getNetworkInfo",
                "getActiveNetworkInfo",
                "getActiveNetwork",
                "getAllNetworks",
                "getNetwork",
                "getActiveNetworkInfoForType",
                "getNetworkInfoForType"
            ),
            Context.TELEPHONY_SERVICE to listOf(
                "getDataNetworkType",
                "getVoiceNetworkType",
                "getNetworkType",
                "getNetworkOperator",
                "getNetworkOperatorName",
                "getSimOperator",
                "getSimOperatorName",
                "getSimCountryIso",
                "getNetworkCountryIso",
                "getPhoneType",
                "isNetworkRoaming",
                "getDataState",
                "getDataActivity",
                "getAllCellInfo",
                "getCellLocation",
                "getNeighboringCellInfo"
            ),
            // AlarmManager.set 的第一个 AIDL 参数就是 callingPackage，
            // 服务端 AlarmManagerService.setImpl 会拿它查 PMS 归属，
            // 与 callingUid（宿主 UID）不符就抛
            //   SecurityException: Package xxx does not belong to the calling uid 10585
            // 小米推送（com.xiaomi.push.service.XMPushService）用 AlarmManager 定时唤醒，
            // 在容器里这个异常会直接杀掉 :push 进程。
            Context.ALARM_SERVICE to listOf(
                "set", "setExact", "setRepeating", "setInexactRepeating",
                "setExactAndAllowWhileIdle", "setAndAllowWhileIdle",
                "setWindow", "setAlarmClock", "cancel", "canScheduleExactAlarms"
            )
        )

        var installed = 0
        for ((serviceName, methods) in targets) {
            val guestService = runCatching { guestApp.getSystemService(serviceName) }
                .getOrNull() ?: continue
            val hostService = runCatching { host.getSystemService(serviceName) }
                .getOrNull() ?: continue

            // ── 主修法：hook binder proxy，把 callingPackage 换成宿主包名 ──
            //
            // ConnectivityManager.getNetworkCapabilities 的实现是：
            //
            //   mService.getNetworkCapabilities(network,
            //       mContext.getOpPackageName(),   ← 服务端拿它做归属校验
            //       mContext.getAttributionTag())
            //
            // 服务端 AppOpsService.verifyAndGetBypass 用这个包名查 PMS 真实归属，
            // 与 callingUid（宿主 UID）不符就抛 SecurityException。
            //
            // 容器把 Context.getOpPackageName() 改成了 Guest 包名（PM 视图自洽需要），
            // 于是这里必然被拒。修法是在 proxy 层把包名换回宿主的 ——
            // 宿主 UID 确实拥有宿主包，服务端校验通过。
            //
            // 这里换的是**参数**，不改 Context 本身，
            // 所以 Guest 自己的 PM 视图不受影响。
            val proxy = extractBinderProxy(guestService)
            if (proxy != null) {
                val proxyHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val cur = session ?: return
                        val args = param.args ?: return
                        var changed = false
                        for (i in args.indices) {
                            val a = args[i] as? String ?: continue
                            if (a == cur.record.sourcePackage) {
                                args[i] = cur.host.packageName
                                changed = true
                            }
                        }
                        if (changed && logged.add("proxy#${param.method?.name}")) {
                            XposedLog.log(
                                "attribution proxy rewritten: ${proxy.javaClass.name}" +
                                    ".${param.method?.name} -> ${cur.host.packageName}"
                            )
                        }
                    }
                }
                for (method in proxy.javaClass.methods) {
                    // 只处理带 String 参数的接口方法（AIDL 的 callingPackage/attributionTag）
                    if (!method.parameterTypes.any { it == String::class.java }) continue
                    if (method.name.startsWith("equals") || method.name.startsWith("hashCode")) continue
                    installed += runCatching {
                        XposedBridge.hookMethod(method, proxyHook)
                        1
                    }.getOrDefault(0)
                }
            }

            // ── 兜底：万一还有别的路径触发 attribution 校验，捕获异常降级 ──
            val guard = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val t = param.throwable ?: return
                    if (!isAttributionDenial(t)) return
                    val method = param.method as? java.lang.reflect.Method ?: return
                    val fallback = hostFallback(method, hostService, current)
                    // 同一个方法可能每秒被调几十次，只在首次记录，
                    // 否则容器日志会被刷爆（实测 6000+ 行）。
                    if (logged.add("$serviceName#${method.name}")) {
                        XposedLog.log(
                            "attribution denial on ${serviceName}.${method.name}: " +
                                "${t.javaClass.simpleName} -> ${fallback?.javaClass?.simpleName ?: "null"}"
                        )
                    }
                    param.setResult(fallback)
                }
            }
            for (name in methods) {
                installed += runCatching {
                    XposedBridge.hookAllMethods(guestService.javaClass, name, guard)
                    1
                }.getOrDefault(0)
            }
        }
        XposedLog.log("attribution-guarded service guards installed=$installed")
    }

    /**
     * 判断异常是否是"包名不属于调用方 UID"这类 attribution 校验拒绝。
     * 这类异常的 message 形如：
     *   "Specified package \"com.x.y\" under uid 10585 but it is not ..."
     *   "Package com.x.y does not belong to 10585"
     */
    private fun isAttributionDenial(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is SecurityException) {
                val msg = cur.message.orEmpty()
                if (msg.contains("does not belong to") ||
                    msg.contains("under uid") ||
                    msg.contains("but it is not")
                ) {
                    return true
                }
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 计算降级结果。
     *
     * 主修法（proxy 层替换 callingPackage）生效时不会走到这里。
     * 这里只作为最后兜底：返回类型匹配的零值，
     * 保证 Guest 不因系统服务权限而崩溃。
     */
    private fun hostFallback(
        method: java.lang.reflect.Method,
        hostService: Any,
        current: GuestSession
    ): Any? = zeroValue(method.returnType)

    /** 沿继承链查找字段。 */
    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        var depth = 0
        while (c != null && depth < 6) {
            runCatching { return c.getDeclaredField(name) }
            c = c.superclass
            depth++
        }
        return null
    }

    /**
     * 从系统服务实例里取出 binder proxy（AIDL 的 $Stub$Proxy）。
     *
     * 系统服务实例通常持有一个类型为 `IXxx` 的字段，运行时实际是
     * `android.net.IConnectivityManager$Stub$Proxy` 这类代理对象，
     * proxy 的方法参数里带着 callingPackage / attributionTag。
     */
    private fun extractBinderProxy(service: Any): Any? {
        var clazz: Class<*>? = service.javaClass
        var depth = 0
        while (clazz != null && depth < 4) {
            for (f in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(service)
                }.getOrNull() ?: continue
                if (v == null) continue
                val name = v.javaClass.name
                if (name.contains("\$Stub\$Proxy") || name.contains("\$Proxy")) {
                    return v
                }
            }
            clazz = clazz.superclass
            depth++
        }
        return null
    }



    private fun zeroValue(type: Class<*>, intSuccess: Boolean = false): Any? = when {
        type == Void.TYPE -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Int::class.javaPrimitiveType -> if (intSuccess) 1 else 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Char::class.javaPrimitiveType -> '\u0000'
        type == IntArray::class.java -> IntArray(0)
        type == LongArray::class.java -> LongArray(0)
        type.isArray -> java.lang.reflect.Array.newInstance(type.componentType, 0)
        java.util.List::class.java.isAssignableFrom(type) -> emptyList<Any>()
        java.util.Set::class.java.isAssignableFrom(type) -> emptySet<Any>()
        else -> null
    }

    private fun hookProviderIdentity() {
        val activityThread = runCatching { Class.forName("android.app.ActivityThread") }.getOrNull()
            ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val authority = param.args.getOrNull(1) as? String
                val transport = current.transportForAuthority(authority)
                if (transport != null) {
                    param.setResult(transport)
                    XposedLog.log("Guest provider routed locally: $authority")
                    return
                }
                val contextIndex = param.args.indexOfFirst { it is Context }
                if (contextIndex < 0) return
                // AMS verifies that the calling package belongs to the calling UID. The guest
                // package is not installed, so system providers must be requested as the host.
                param.args[contextIndex] = current.host
            }
        }
        runCatching { XposedBridge.hookAllMethods(activityThread, "acquireProvider", hook) }
            .onFailure { XposedLog.log("hook acquireProvider failed: $it") }
        XposedLog.log("provider identity hook installed")
    }

    private fun hookIdentity() {
        val activityThread = runCatching { Class.forName("android.app.ActivityThread") }.getOrNull()
            ?: return
        val packageName = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                session?.let { param.result = it.record.sourcePackage }
            }
        }
        val processName = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                session?.let { param.result = it.guestProcessName }
            }
        }
        val application = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                session?.guestApplication?.let { param.result = it }
            }
        }
        for (name in listOf("currentPackageName", "currentOpPackageName")) {
            runCatching { XposedBridge.hookAllMethods(activityThread, name, packageName) }
        }
        runCatching { XposedBridge.hookAllMethods(activityThread, "currentProcessName", processName) }
        runCatching { XposedBridge.hookAllMethods(activityThread, "currentApplication", application) }
        runCatching {
            XposedBridge.hookAllMethods(Application::class.java, "getProcessName", processName)
        }
        runCatching {
            XposedBridge.hookAllMethods(Process::class.java, "myProcessName", processName)
        }
    }

    /**
     * 接管 PackageManager 上的**静态**查询方法。
     *
     * 这条路径此前完全绕过容器，是两个问题的共同根因：
     *
     * 1. 崩溃：LoadedApk.initializeJavaContextClassLoader() 通过
     *    PackageManager.getPackageInfoAsUserCached(mPackageName, MATCH_DEBUG_TRIAGED_MISSING, userId)
     *    确认包是否存在。这是 android.content.pm.PackageManager 上的静态方法，
     *    内部经 PropertyInvalidatedCache 直接走 IPackageManager binder，
     *    **不经过 ApplicationPackageManager**，所以上面所有实例方法 hook 都拦不住它。
     *    目标应用在真机上被卸载后 PMS 返回 null，框架直接抛
     *    "Unable to get package info for X; is package not installed?"，Guest 起不来。
     *
     * 2. 视图泄漏：该路径返回的是 PMS 的真实结果（真实安装态、真实 UID、真实 dataDir），
     *    与 ApplicationPackageManager 路径返回的容器视图互相矛盾。
     *    应用只要调一次静态方法就能拿到两套不一致的数据。
     */
    private fun hookPackageManagerStaticQueries() {
        val basePmClass = runCatching { Class.forName("android.content.pm.PackageManager") }
            .getOrNull() ?: return

        val cachedInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.host.packageName) {
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, pkg)
                    param.setThrowable(PackageManager.NameNotFoundException(pkg))
                    return
                }
                if (pkg != current.record.sourcePackage) return
                // 静态方法签名：(String, long flags, int userId)
                val flags = resolveFlags(param.args.getOrNull(1))
                param.setResult(current.packageInfoFor(flags))
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(basePmClass, "getPackageInfoAsUserCached", cachedInfoHook)
        }.onFailure { XposedLog.log("hook getPackageInfoAsUserCached failed: $it") }
    }

    private fun hookPackageManager() {
        val pmClass = runCatching { Class.forName("android.app.ApplicationPackageManager") }
            .getOrNull() ?: return

        hookPackageManagerStaticQueries()

        val packageInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.host.packageName) {
                    // A Guest must not see the container package; on a real device
                    // it would simply be absent from this app's package manager view.
                    //
                    // 必须用 setThrowable 而不是 afterHookedMethod 里 throw：
                    // after 回调抛出的异常会被 Hooker 捕获并回滚 result/throwable，
                    // 导致"隐藏宿主包"实际上完全失效，Guest 仍能直接查到宿主包名、
                    // APK 路径与签名。setThrowable 会置 returnEarly=true，
                    // 在原方法执行前短路并抛出 NameNotFoundException。
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, pkg)
                    param.setThrowable(PackageManager.NameNotFoundException(pkg))
                    return
                }
                if (pkg != current.record.sourcePackage) return
                val flags = resolveFlags(param.args.getOrNull(1))
                param.setResult(current.packageInfoFor(flags))
            }
        }
        for (name in listOf("getPackageInfo", "getPackageInfoAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, packageInfoHook) }
        }

        val applicationInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.host.packageName) {
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, pkg)
                    param.setThrowable(PackageManager.NameNotFoundException(pkg))
                    return
                }
                if (pkg == current.record.sourcePackage) {
                    // 对 Guest 暴露"真机视图"：安装路径指向真机安装位置。
                    // metaData 按 flags 决定是否携带（真机 PMS 语义）。
                    val flags = resolveFlags(param.args.getOrNull(1))
                    param.setResult(current.publicAppInfoFor(flags))
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getApplicationInfo", applicationInfoHook) }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getApplicationInfoAsUser", applicationInfoHook) }

        // 组件级查询（Activity / Service / Receiver / Provider）。
        //
        // 这四个方法在真机上有一个共同点：目标应用真机上被卸载后，原方法抛
        // NameNotFoundException。如果在 after 阶段改写 param.result，
        // Hooker.invoke() 仍会因为 param.throwable != null 而重新抛出异常
        // （见 Hooker.java:172-174），改写结果被丢弃 —— 等于没改。
        // 因此这里一律用 before + setResult 短路。
        val activityInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val component = param.args.getOrNull(0) as? ComponentName ?: return
                if (component.packageName == current.host.packageName) {
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, component.packageName)
                    param.setThrowable(PackageManager.NameNotFoundException(component.packageName))
                    return
                }
                if (component.packageName != current.record.sourcePackage) return
                // 严格查询：查不到必须抛 NameNotFoundException，与真机一致。
                // 必须用 setThrowable 而不是 throw：before 回调抛出的异常同样会被
                // Hooker.invoke() 捕获并回滚 result/throwable、取消短路
                // （见 Hooker.java:127-133），于是原方法照常执行 ——
                // 真机上应用已安装时会返回真实 ActivityInfo，严格语义直接失效。
                val info = current.queryActivityInfo(component.className)
                if (info == null) {
                    param.setThrowable(
                        PackageManager.NameNotFoundException(component.flattenToString())
                    )
                    return
                }
                param.setResult(info)
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getActivityInfo", activityInfoHook) }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getActivityInfoAsUser", activityInfoHook) }

        val serviceInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val component = param.args.getOrNull(0) as? ComponentName ?: return
                if (component.packageName == current.host.packageName) {
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, component.packageName)
                    param.setThrowable(PackageManager.NameNotFoundException(component.packageName))
                    return
                }
                if (component.packageName != current.record.sourcePackage) return
                val info = current.serviceInfoFor(component.className)
                if (info == null) {
                    param.setThrowable(
                        PackageManager.NameNotFoundException(component.flattenToString())
                    )
                    return
                }
                param.setResult(info)
            }
        }
        for (name in listOf("getServiceInfo", "getServiceInfoAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, serviceInfoHook) }
        }

        val receiverInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val component = param.args.getOrNull(0) as? ComponentName ?: return
                if (component.packageName == current.host.packageName) {
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, component.packageName)
                    param.setThrowable(PackageManager.NameNotFoundException(component.packageName))
                    return
                }
                if (component.packageName != current.record.sourcePackage) return
                val info = current.receiverInfoFor(component.className)
                if (info == null) {
                    param.setThrowable(
                        PackageManager.NameNotFoundException(component.flattenToString())
                    )
                    return
                }
                param.setResult(info)
            }
        }
        for (name in listOf("getReceiverInfo", "getReceiverInfoAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, receiverInfoHook) }
        }

        // getProviderInfo 的参数是 ComponentName（authority 由 ProviderInfo 暴露）。
        val providerInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val component = param.args.getOrNull(0) as? ComponentName ?: return
                if (component.packageName == current.host.packageName) {
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, component.packageName)
                    param.setThrowable(PackageManager.NameNotFoundException(component.packageName))
                    return
                }
                if (component.packageName != current.record.sourcePackage) return
                val info = current.providerInfoFor(component.className)
                if (info == null) {
                    param.setThrowable(
                        PackageManager.NameNotFoundException(component.flattenToString())
                    )
                    return
                }
                param.setResult(info)
            }
        }
        for (name in listOf("getProviderInfo", "getProviderInfoAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, providerInfoHook) }
        }

        // checkSignatures 有 (String,String) 与 (int,int) 两个重载。
        // 只处理前者的话，int 版本会走系统实现，按宿主真实证书比较，
        // 结果与 String 版本矛盾。
        val signaturesHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val guest = current.record.sourcePackage
                val first = param.args.getOrNull(0)
                val second = param.args.getOrNull(1)
                if (first is Int && second is Int) {
                    // UID 形式：Guest 与自己比较必然匹配；与宿主 UID 比较必然不匹配。
                    // 用 before 短路：原方法在目标应用已卸载时会抛异常。
                    val myUid = Process.myUid()
                    param.setResult(
                        when {
                            first == myUid && second == myUid -> PackageManager.SIGNATURE_MATCH
                            first == myUid || second == myUid -> PackageManager.SIGNATURE_NO_MATCH
                            else -> return
                        }
                    )
                    return
                }
                if (first !is String || second !is String) return
                if (first == guest && second == guest) {
                    param.setResult(PackageManager.SIGNATURE_MATCH)
                    return
                }
                if (first != guest && second != guest) return
                val other = if (first == guest) second else first
                param.setResult(
                    when {
                        other == hostApplication?.packageName -> PackageManager.SIGNATURE_NO_MATCH
                        else -> compareWithInstalled(current, other)
                    }
                )
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "checkSignatures", signaturesHook) }

        // hasSigningCertificate(String, byte[], int) 与 (int, byte[], int)
        // 用于校验"某包是否由指定证书签名"，同样要按 Guest 视角回答。
        val hasCertHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val guest = current.record.sourcePackage
                val target = param.args.getOrNull(0)
                val cert = param.args.getOrNull(1) as? ByteArray ?: return
                val guestCerts = current.signatures().map { it.toByteArray() }
                val matches = guestCerts.any { it.contentEquals(cert) }
                when (target) {
                    is String -> {
                        if (target != guest) return
                        param.setResult(matches)
                    }
                    is Int -> {
                        if (target != Process.myUid()) return
                        param.setResult(matches)
                    }
                    else -> return
                }
            }
        }
        for (name in listOf("hasSigningCertificate", "hasSigningCertificateAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, hasCertHook) }
        }

        val uidHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.host.packageName) {
                    // 宿主包在 Guest 视角里必须完全不存在：查不到包名，也就无从查 UID。
                    if (!isGuestCaller(current, param)) return
                    logHiddenHostQuery(current, pkg)
                    param.setThrowable(PackageManager.NameNotFoundException(pkg))
                    return
                }
                if (pkg != current.record.sourcePackage) return
                // 必须在 before 阶段短路：目标应用在真机上被卸载时，
                // 原方法会先抛 NameNotFoundException，after 阶段再改结果已经来不及。
                // Guest 自己正在运行，getPackageUid 应当像真机一样返回自己的 uid。
                param.setResult(Process.myUid())
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getPackageUid", uidHook) }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getPackageUidAsUser", uidHook) }

        val installedPackagesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                @Suppress("UNCHECKED_CAST")
                val original = param.result as? List<PackageInfo> ?: return
                val flags = resolveFlags(param.args.getOrNull(0))
                val filtered = original.filter { it.packageName != current.host.packageName }
                // 必须用容器自己构造的 Guest 条目替换系统原始条目。
                // 保留系统条目会暴露真实 UID 与真实 dataDir，
                // 与 getApplicationInfo 返回的宿主 UID/容器 dataDir 直接矛盾。
                val result = ArrayList<PackageInfo>(filtered.size + 1)
                var replaced = false
                for (item in filtered) {
                    if (item.packageName == current.record.sourcePackage) {
                        result.add(current.packageInfoFor(flags))
                        replaced = true
                    } else {
                        result.add(item)
                    }
                }
                if (!replaced) {
                    result.add(current.packageInfoFor(flags))
                }
                // 必须用 setResult 而不是直接赋值 param.result：
                // setResult 会把 throwable 一起清掉，而直接赋值不会。
                // 宿主查询时原方法可能因权限抛异常，此时直接赋值的结果会被
                // Hooker.invoke() 重新抛出原异常丢弃（见 Hooker.java:172-174）。
                param.setResult(result)
            }
        }
        for (name in listOf("getInstalledPackages", "getInstalledPackagesAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, installedPackagesHook) }
        }

        val installedAppsHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                @Suppress("UNCHECKED_CAST")
                val original = param.result as? List<ApplicationInfo> ?: return
                val filtered = original.filter { it.packageName != current.host.packageName }
                val result = ArrayList<ApplicationInfo>(filtered.size + 1)
                var replaced = false
                for (item in filtered) {
                    if (item.packageName == current.record.sourcePackage) {
                        result.add(current.publicAppInfo())
                        replaced = true
                    } else {
                        result.add(item)
                    }
                }
                if (!replaced) {
                    result.add(current.publicAppInfo())
                }
                param.setResult(result)
            }
        }
        for (name in listOf("getInstalledApplications", "getInstalledApplicationsAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, installedAppsHook) }
        }

        val launchIntentHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg != current.record.sourcePackage) return
                val launcher = current.record.launcherActivity ?: return
                param.setResult(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(current.record.sourcePackage, launcher)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(pmClass, "getLaunchIntentForPackage", launchIntentHook)
        }

        val labelHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val info = param.args.getOrNull(0) as? ApplicationInfo ?: return
                if (info.packageName == current.record.sourcePackage) {
                    param.setResult(current.record.label)
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getApplicationLabel", labelHook) }

        val resourcesHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val target = param.args.getOrNull(0)
                val pkg = when (target) {
                    is String -> target
                    is ApplicationInfo -> target.packageName
                    else -> null
                }
                if (pkg == current.record.sourcePackage) {
                    // 必须用 before 短路：真机上目标应用被卸载时原方法抛
                    // NameNotFoundException，after 阶段改写的结果会被丢弃。
                    param.setResult(current.resources)
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(pmClass, "getResourcesForApplication", resourcesHook)
        }

        val gidsHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (uid != Process.myUid()) return
                // The real app has its own UID; the kernel sees the host UID.
                // Expose only the Guest package for this UID so the app does
                // not discover the container package through its own UID.
                //
                // 用 before 短路：目标应用在真机上被卸载时原方法会抛
                // NameNotFoundException，after 阶段已无法补救。
                param.setResult(arrayOf(current.record.sourcePackage))
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getPackagesForUid", gidsHook) }

        val namesHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (uid == Process.myUid()) {
                    param.setResult(arrayOf(current.record.sourcePackage))
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getNamesForUid", namesHook) }

        // getNameForUid 之前完全没被接管，返回的是宿主包名。
        // 应用只要做一次 uid -> 包名反查，就能确认自己跑在容器里。
        val nameForUidHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (uid == Process.myUid()) {
                    param.setResult(current.record.sourcePackage)
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getNameForUid", nameForUidHook) }

        // 安装来源：导入时已记录真机的 installerPackage，
        // 若不接管 getInstallerForUid/getInstallerPackageName，
        // Guest 会读到宿主的安装来源（例如应用商店），与真机不符。
        val installerUidHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (uid != Process.myUid()) return
                val installer = current.record.installerPackage ?: return
                param.setResult(installer)
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getInstallerForUid", installerUidHook) }

        val installerPkgHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg != current.record.sourcePackage) return
                val installer = current.record.installerPackage ?: return
                param.setResult(installer)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(pmClass, "getInstallerPackageName", installerPkgHook)
        }
    }

    /**
     * 解析 PackageManager 的 flags 参数。
     *
     * API 33+ 新增了 PackageInfoFlags / ApplicationInfoFlags 重载，
     * 其值通过 getValue(): long 暴露。之前只按 Int 解析，
     * 导致新版重载恒为 0，进而丢失 GET_ACTIVITIES / GET_SIGNING_CERTIFICATES 等语义。
     */
    private fun resolveFlags(raw: Any?): Int {
        return when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            null -> 0
            else -> {
                val value = runCatching {
                    raw.javaClass.getMethod("getValue").invoke(raw) as? Long
                }.getOrNull()
                value?.toInt() ?: 0
            }
        }
    }

    /**
     * 判断当前 PackageManager 调用是否来自 Guest 代码。
     *
     * 宿主框架自身（ActivityThread、ResourcesManager 等）也会查询宿主包，
     * 如果一律抛 NameNotFoundException 会把宿主自己打死。
     * 这里通过调用栈里是否出现 Guest 自己的类（由 Guest ClassLoader 加载）来区分。
     */
    private fun isGuestCaller(current: GuestSession, param: XC_MethodHook.MethodHookParam<*>): Boolean {
        // 优先用 thisObject 上的 Context 判断：ApplicationPackageManager 持有的
        // mContext 就是发起查询的 Context，Guest 侧查询时它的 packageName 是 Guest 包名。
        // 静态方法（thisObject == null）没有 mContext，直接走调用栈判断。
        val context = param.thisObject
            ?.let { getObjectField(it, "mContext") } as? Context
        if (context != null) {
            val pkg = runCatching { context.packageName }.getOrNull()
            if (pkg == current.record.sourcePackage) return true
            if (pkg == current.host.packageName) return false
        }
        // 退回到调用栈判断：Guest 的类由 Guest ClassLoader 加载，
        // 框架类的 classLoader 则是 boot/parent，两者可区分。
        // 注意 Guest 内部第三方 SDK 的包名未必以 Guest 包名开头，
        // 所以不能只按包名前缀过滤，要逐帧用 Guest ClassLoader 试加载。
        val loader = current.classLoader
        val frames = Thread.currentThread().stackTrace
        var checked = 0
        for (frame in frames) {
            if (checked++ > 24) break
            val name = frame.className
            if (name.startsWith("android.") || name.startsWith("java.")) continue
            val clazz = runCatching {
                Class.forName(name, false, loader)
            }.getOrNull() ?: continue
            if (clazz.classLoader === loader) return true
        }
        return false
    }

    private fun logHiddenHostQuery(current: GuestSession, pkg: String) {
        if (hostQueryLogged >= 8) return
        if (!File(current.host.filesDir, "vt_net_trace").isFile) return
        hostQueryLogged++
        XposedLog.log(
            "hidden host package query #$hostQueryLogged ($pkg):\n" +
                android.util.Log.getStackTraceString(Throwable())
        )
    }

    private fun compareWithInstalled(current: GuestSession, otherPackage: String): Int {
        val pm = current.host.packageManager
        @Suppress("DEPRECATION")
        val other = runCatching {
            pm.getPackageInfo(otherPackage, PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
        @Suppress("DEPRECATION")
        val otherSignatures = other.signatures ?: return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
        val mine = current.signatures()
        for (signature in otherSignatures) {
            if (mine.any { it.toCharsString() == signature.toCharsString() }) {
                return PackageManager.SIGNATURE_MATCH
            }
        }
        return PackageManager.SIGNATURE_NO_MATCH
    }

    private fun getObjectField(target: Any, name: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    private fun setObjectField(target: Any, name: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        XposedLog.log("Field not found: $name on ${target.javaClass.name}")
    }
}
