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
    private var hostQueryLogged = false

    @Volatile
    private var diagnosticsInstalled = false

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
        hookPerformLaunchActivity()
        hookNewActivity()
        hookActivityAttach()
        hookApplicationLifecycle()
        hookServices()
        hookBroadcasts()
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
        hookProviderIdentity()
        hookIdentity()
        hookPackageManager()
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
        return try {
            GuestSession(host, record, slot, guestProcess).also {
                it.initialize()
                session = it
                installBytedanceDiagnostics(it)
            }
        } catch (t: Throwable) {
            XposedLog.log("GuestSession 初始化失败")
            XposedLog.log(t)
            null
        }
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
                    param.result = current.host.packageName
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
                val real = current.record.installedSourceDir?.takeIf { File(it).isFile }
                    ?: return
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
                    name == "getObbDir" -> File(root, "obb")
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

    private fun hookPackageManager() {
        val pmClass = runCatching { Class.forName("android.app.ApplicationPackageManager") }
            .getOrNull() ?: return

        val packageInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.host.packageName) {
                    // A Guest must not see the container package; on a real device
                    // it would simply be absent from this app's package manager view.
                    if (!hostQueryLogged &&
                        File(current.host.filesDir, "vt_net_trace").isFile
                    ) {
                        hostQueryLogged = true
                        XposedLog.log(
                            "hidden host package query:\n" +
                                android.util.Log.getStackTraceString(Throwable())
                        )
                    }
                    throw PackageManager.NameNotFoundException(pkg)
                }
                if (pkg != current.record.sourcePackage) return
                val flags = (param.args.getOrNull(1) as? Int) ?: 0
                param.result = current.packageInfoFor(flags)
            }
        }
        for (name in listOf("getPackageInfo", "getPackageInfoAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, packageInfoHook) }
        }

        val applicationInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.host.packageName) {
                    throw PackageManager.NameNotFoundException(pkg)
                }
                if (pkg == current.record.sourcePackage) {
                    param.result = current.appInfo
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getApplicationInfo", applicationInfoHook) }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getApplicationInfoAsUser", applicationInfoHook) }

        val activityInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val component = param.args.getOrNull(0) as? ComponentName ?: return
                if (component.packageName == current.record.sourcePackage) {
                    param.result = current.activityInfoFor(component.className)
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getActivityInfo", activityInfoHook) }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getActivityInfoAsUser", activityInfoHook) }

        val signaturesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val first = param.args.getOrNull(0) as? String ?: return
                val second = param.args.getOrNull(1) as? String ?: return
                val guest = current.record.sourcePackage
                if (first == guest && second == guest) {
                    param.result = PackageManager.SIGNATURE_MATCH
                    return
                }
                if (first != guest && second != guest) return
                val other = if (first == guest) second else first
                param.result = when {
                    other == hostApplication?.packageName -> PackageManager.SIGNATURE_NO_MATCH
                    else -> compareWithInstalled(current, other)
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "checkSignatures", signaturesHook) }

        val uidHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg == current.record.sourcePackage) param.result = Process.myUid()
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getPackageUid", uidHook) }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getPackageUidAsUser", uidHook) }

        val installedPackagesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                @Suppress("UNCHECKED_CAST")
                val original = param.result as? List<PackageInfo> ?: return
                val filtered = original.filter { it.packageName != current.host.packageName }
                if (filtered.any { it.packageName == current.record.sourcePackage }) {
                    param.result = filtered
                } else {
                    param.result = ArrayList(filtered).apply { add(current.packageInfoFor(0)) }
                }
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
                if (filtered.any { it.packageName == current.record.sourcePackage }) {
                    param.result = filtered
                } else {
                    param.result = ArrayList(filtered).apply { add(current.appInfo) }
                }
            }
        }
        for (name in listOf("getInstalledApplications", "getInstalledApplicationsAsUser")) {
            runCatching { XposedBridge.hookAllMethods(pmClass, name, installedAppsHook) }
        }

        val launchIntentHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val pkg = param.args.getOrNull(0) as? String ?: return
                if (pkg != current.record.sourcePackage) return
                val launcher = current.record.launcherActivity ?: return
                param.result = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(current.record.sourcePackage, launcher)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(pmClass, "getLaunchIntentForPackage", launchIntentHook)
        }

        val labelHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val info = param.args.getOrNull(0) as? ApplicationInfo ?: return
                if (info.packageName == current.record.sourcePackage) {
                    param.result = current.record.label
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getApplicationLabel", labelHook) }

        val resourcesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val target = param.args.getOrNull(0)
                val pkg = when (target) {
                    is String -> target
                    is ApplicationInfo -> target.packageName
                    else -> null
                }
                if (pkg == current.record.sourcePackage) {
                    param.result = current.resources
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(pmClass, "getResourcesForApplication", resourcesHook)
        }

        val gidsHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (uid != Process.myUid()) return
                // The real app has its own UID; the kernel sees the host UID.
                // Expose only the Guest package for this UID so the app does
                // not discover the container package through its own UID.
                param.result = arrayOf(current.record.sourcePackage)
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getPackagesForUid", gidsHook) }

        val namesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                val current = session ?: return
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (uid == Process.myUid()) {
                    param.result = arrayOf(current.record.sourcePackage)
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(pmClass, "getNamesForUid", namesHook) }
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
